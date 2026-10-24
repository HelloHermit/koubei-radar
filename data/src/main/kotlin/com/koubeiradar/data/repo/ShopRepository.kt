package com.koubeiradar.data.repo

import com.koubeiradar.core.model.Shop
import com.koubeiradar.data.NearbyQuery
import com.koubeiradar.data.ShopSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong

/**
 * The candidate pool shared by the UI and the agent tools: nearby shops from a [ShopSource] plus shops the user
 * imported (screenshots / pasted text). Thread-safe. Spec: `docs/DESIGN.md` §3.3.
 *
 * CONTRACT (frozen):
 *  - [refreshNearby] replaces ONLY the nearby part of the pool; imported shops survive a refresh;
 *  - identical (source id + query) requests within [cacheTtlMillis] are served from cache unless `force`;
 *  - [all] = nearby ∪ imported, de-duplicated by normalised shop name: when an imported shop matches a nearby shop
 *    the two are MERGED (imported takeout-specific fields win: rating, ratingCount, monthlySales, delivery*, minOrder,
 *    reviews, platforms; nearby keeps location/address/phone/category when the import lacks them) and the merged shop
 *    keeps the imported id;
 *  - [shops] always mirrors [all].
 *
 * A failed [refreshNearby] throws and leaves the pool as it was. Refreshes may overlap; the pool ends up with the
 * result of the one that STARTED last, so a slow, outdated response never overwrites a newer one.
 */
class ShopRepository(
    private val clock: () -> Long = System::currentTimeMillis,
    private val cacheTtlMillis: Long = 10 * 60_000L,
) : ShopPool {
    private data class CacheKey(
        val sourceId: String,
        val latitudeE4: Long,
        val longitudeE4: Long,
        val radiusMeters: Int,
        val keyword: String?,
        val limit: Int,
    )

    private class CacheEntry(val shops: List<Shop>, val loadedAtMillis: Long)

    // Everything below up to `published` is guarded by `lock`; `published` is what lock-free readers see.
    private val lock = Any()
    private var nearby: List<Shop> = emptyList()
    private var nearbyOrigin = 0L
    private var clearedOrigin = 0L
    private val imported = LinkedHashMap<String, Shop>()
    private val cache = LinkedHashMap<CacheKey, CacheEntry>()
    private val published = MutableStateFlow<List<Shop>>(emptyList())

    /** Orders refreshes (and [clearAll]) by when they started. */
    private val startedRefreshes = AtomicLong()

    val shops: StateFlow<List<Shop>> = published.asStateFlow()

    override suspend fun refreshNearby(source: ShopSource, query: NearbyQuery, force: Boolean): List<Shop> {
        val origin = startedRefreshes.incrementAndGet()
        val key = cacheKey(source.id, query)
        if (!force) {
            val now = clock()
            val cached = synchronized(lock) {
                cache[key]?.takeIf { isFresh(it, now) }?.shops?.also { replaceNearby(it, origin) }
            }
            if (cached != null) return cached
        }
        val loaded = source.nearby(query).distinctBy { it.id }
        val loadedAt = clock()
        synchronized(lock) {
            if (origin > clearedOrigin) remember(key, CacheEntry(loaded, loadedAt))
            replaceNearby(loaded, origin)
        }
        return loaded
    }

    /** Adds [shops] to the imported part of the pool; a shop whose id is already imported replaces the earlier one. */
    fun addImported(shops: List<Shop>) {
        if (shops.isEmpty()) return
        synchronized(lock) {
            shops.forEach { imported[it.id] = it }
            publish()
        }
    }

    fun clearImported() {
        synchronized(lock) {
            imported.clear()
            publish()
        }
    }

    /** Empties the pool and the cache; refreshes that started earlier and are still running will not repopulate it. */
    fun clearAll() {
        synchronized(lock) {
            nearby = emptyList()
            clearedOrigin = startedRefreshes.incrementAndGet()
            nearbyOrigin = clearedOrigin
            imported.clear()
            cache.clear()
            publish()
        }
    }

    override fun all(): List<Shop> = published.value

    override fun find(id: String): Shop? = published.value.firstOrNull { it.id == id }

    private fun replaceNearby(shops: List<Shop>, origin: Long) {
        if (origin < nearbyOrigin) return
        nearby = shops
        nearbyOrigin = origin
        publish()
    }

    private fun publish() {
        published.value = ShopMerger.merge(nearby, imported.values.toList()).distinctBy { it.id }
    }

    private fun isFresh(entry: CacheEntry, now: Long): Boolean = now - entry.loadedAtMillis in 0 until cacheTtlMillis

    private fun remember(key: CacheKey, entry: CacheEntry) {
        cache.values.removeAll { !isFresh(it, entry.loadedAtMillis) }
        cache.remove(key)
        cache[key] = entry
        while (cache.size > MAX_CACHE_ENTRIES) cache.remove(cache.keys.first())
    }

    private fun cacheKey(sourceId: String, query: NearbyQuery) = CacheKey(
        sourceId = sourceId,
        latitudeE4 = Math.round(query.center.lat * 10_000),
        longitudeE4 = Math.round(query.center.lng * 10_000),
        radiusMeters = query.radiusMeters,
        keyword = query.keyword?.trim()?.ifEmpty { null },
        limit = query.limit,
    )

    private companion object {
        const val MAX_CACHE_ENTRIES = 16
    }
}
