package com.koubeiradar.data.repo

import com.koubeiradar.core.model.GeoPoint
import com.koubeiradar.core.model.Platform
import com.koubeiradar.core.model.Review
import com.koubeiradar.core.model.Shop
import com.koubeiradar.data.FakeShopSource
import com.koubeiradar.data.MutableClock
import com.koubeiradar.data.NearbyQuery
import com.koubeiradar.data.ShopSourceException
import com.koubeiradar.data.ShopSourceException.Kind
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ShopRepositoryTest {
    private val clock = MutableClock(1_000_000L)
    private val repo = ShopRepository(clock = clock, cacheTtlMillis = TTL)
    private val query = NearbyQuery(GeoPoint(lat = 39.9087, lng = 116.3975), radiusMeters = 3000, keyword = null, limit = 50)

    private fun nearbyShop(n: Int, name: String = "附近店$n") =
        Shop(id = "fake:$n", name = name, source = "fake", rating = 4.0, location = GeoPoint(39.9, 116.4), address = "地址$n")

    private fun importedShop(n: Int, name: String = "导入店$n") =
        Shop(id = "import:$n", name = name, source = "import", rating = 4.8, ratingCount = 100, monthlySales = 300)

    private fun sourceOf(vararg shops: Shop, id: String = "fake") = FakeShopSource(id, shops.toList())

    private fun ids(shops: List<Shop>) = shops.map { it.id }

    // ---- pool basics ----

    @Test
    fun `starts empty`() {
        assertEquals(emptyList(), repo.all())
        assertEquals(emptyList(), repo.shops.value)
        assertNull(repo.find("fake:1"))
    }

    @Test
    fun `refresh loads the nearby shops and returns them`() = runTest {
        val source = sourceOf(nearbyShop(1), nearbyShop(2))

        val loaded = repo.refreshNearby(source, query)

        assertEquals(listOf("fake:1", "fake:2"), ids(loaded))
        assertEquals(loaded, repo.all())
        assertEquals(nearbyShop(2), repo.find("fake:2"))
        assertEquals(listOf(query), source.queries)
    }

    @Test
    fun `refresh replaces the nearby shops but not the imported ones`() = runTest {
        repo.addImported(listOf(importedShop(1)))
        repo.refreshNearby(sourceOf(nearbyShop(1), nearbyShop(2)), query)

        repo.refreshNearby(sourceOf(nearbyShop(3)), query.copy(keyword = "川菜"))

        assertEquals(listOf("fake:3", "import:1"), ids(repo.all()))
    }

    @Test
    fun `imported shops that match nothing come after the nearby shops`() = runTest {
        repo.addImported(listOf(importedShop(1)))

        repo.refreshNearby(sourceOf(nearbyShop(1), nearbyShop(2)), query)

        assertEquals(listOf("fake:1", "fake:2", "import:1"), ids(repo.all()))
    }

    @Test
    fun `clearImported removes only the imported shops`() = runTest {
        repo.refreshNearby(sourceOf(nearbyShop(1)), query)
        repo.addImported(listOf(importedShop(1), importedShop(2)))

        repo.clearImported()

        assertEquals(listOf("fake:1"), ids(repo.all()))
    }

    @Test
    fun `clearImported restores a nearby shop that an import had been merged into`() = runTest {
        repo.refreshNearby(sourceOf(nearbyShop(1, name = "老王川菜馆")), query)
        repo.addImported(listOf(importedShop(1, name = "老王川菜馆")))
        assertEquals(listOf("import:1"), ids(repo.all()))

        repo.clearImported()

        assertEquals(listOf(nearbyShop(1, name = "老王川菜馆")), repo.all())
    }

    @Test
    fun `clearAll empties the pool and forgets the cache`() = runTest {
        val source = sourceOf(nearbyShop(1))
        repo.refreshNearby(source, query)
        repo.addImported(listOf(importedShop(1)))

        repo.clearAll()

        assertEquals(emptyList(), repo.all())
        assertEquals(emptyList(), repo.shops.value)
        repo.refreshNearby(source, query)
        assertEquals(2, source.calls)
        assertEquals(listOf("fake:1"), ids(repo.all()))
    }

    @Test
    fun `shops that share an id inside one source answer are collapsed`() = runTest {
        val loaded = repo.refreshNearby(sourceOf(nearbyShop(1), nearbyShop(2), nearbyShop(1, name = "重复")), query)

        assertEquals(listOf("fake:1", "fake:2"), ids(loaded))
        assertEquals(loaded, repo.all())
    }

    @Test
    fun `lists handed out earlier are never modified`() = runTest {
        repo.refreshNearby(sourceOf(nearbyShop(1)), query)
        val before = repo.all()

        repo.addImported(listOf(importedShop(1)))
        repo.refreshNearby(sourceOf(nearbyShop(2)), query.copy(limit = 7))

        assertEquals(listOf("fake:1"), ids(before))
    }

    // ---- imports ----

    @Test
    fun `importing the same id again replaces the earlier shop`() {
        repo.addImported(listOf(importedShop(1), importedShop(2)))

        repo.addImported(listOf(importedShop(1).copy(rating = 3.0, name = "改名后")))

        assertEquals(listOf("import:1", "import:2"), ids(repo.all()))
        assertEquals(3.0, repo.find("import:1")?.rating)
        assertEquals("改名后", repo.find("import:1")?.name)
    }

    @Test
    fun `the last of several imports with one id wins`() {
        repo.addImported(listOf(importedShop(1), importedShop(1).copy(rating = 2.0)))

        assertEquals(1, repo.all().size)
        assertEquals(2.0, repo.all().single().rating)
    }

    @Test
    fun `importing nothing changes nothing`() {
        repo.addImported(emptyList())

        assertEquals(emptyList(), repo.all())
    }

    // ---- merging ----

    @Test
    fun `an import is merged into the matching nearby shop and keeps its id`() = runTest {
        repo.refreshNearby(sourceOf(nearbyShop(1, name = "老王川菜馆(中关村店)").copy(rating = 4.4, category = "川菜馆", phone = "010-1")), query)

        repo.addImported(
            listOf(
                importedShop(1, name = "老王川菜馆（中关村店）").copy(
                    rating = 4.8, ratingCount = 3200, monthlySales = 4200, deliveryMinutes = 30, deliveryFee = 2.0, minOrder = 20.0,
                    reviews = listOf(Review("好吃", 5.0)), platforms = setOf(Platform.MEITUAN),
                ),
            ),
        )

        val merged = repo.all().single()
        assertEquals("import:1", merged.id)
        assertEquals(4.8, merged.rating)
        assertEquals(3200, merged.ratingCount)
        assertEquals(4200, merged.monthlySales)
        assertEquals(30, merged.deliveryMinutes)
        assertEquals(2.0, merged.deliveryFee)
        assertEquals(20.0, merged.minOrder)
        assertEquals(listOf(Review("好吃", 5.0)), merged.reviews)
        assertEquals(setOf(Platform.MEITUAN), merged.platforms)
        assertEquals(GeoPoint(39.9, 116.4), merged.location)
        assertEquals("地址1", merged.address)
        assertEquals("川菜馆", merged.category)
        assertEquals("010-1", merged.phone)
        assertEquals(merged, repo.find("import:1"))
        assertNull(repo.find("fake:1"))
    }

    @Test
    fun `an import made before the nearby shops arrive is merged once they do`() = runTest {
        repo.addImported(listOf(importedShop(1, name = "老王川菜馆")))
        assertEquals(listOf("import:1"), ids(repo.all()))

        repo.refreshNearby(sourceOf(nearbyShop(1, name = "老王川菜馆(中关村店)"), nearbyShop(2)), query)

        assertEquals(listOf("import:1", "fake:2"), ids(repo.all()))
        assertEquals(GeoPoint(39.9, 116.4), repo.find("import:1")?.location)
    }

    @Test
    fun `an ambiguous import is not merged`() = runTest {
        repo.refreshNearby(sourceOf(nearbyShop(1, name = "老王川菜馆(中关村店)"), nearbyShop(2, name = "老王川菜馆(五道口店)")), query)

        repo.addImported(listOf(importedShop(1, name = "老王川菜馆")))

        assertEquals(listOf("fake:1", "fake:2", "import:1"), ids(repo.all()))
    }

    @Test
    fun `importing a merged shop twice still yields one shop`() = runTest {
        repo.refreshNearby(sourceOf(nearbyShop(1, name = "老王川菜馆")), query)

        repo.addImported(listOf(importedShop(1, name = "老王川菜馆")))
        repo.addImported(listOf(importedShop(1, name = "老王川菜馆").copy(rating = 4.1)))

        assertEquals(listOf("import:1"), ids(repo.all()))
        assertEquals(4.1, repo.all().single().rating)
    }

    // ---- cache ----

    @Test
    fun `identical requests within the ttl are served from the cache`() = runTest {
        val source = sourceOf(nearbyShop(1))
        val first = repo.refreshNearby(source, query)

        clock.nowMillis += TTL - 1
        val second = repo.refreshNearby(source, query)

        assertEquals(1, source.calls)
        assertEquals(first, second)
    }

    @Test
    fun `identical requests are fetched again once the ttl has passed`() = runTest {
        val source = sourceOf(nearbyShop(1))
        repo.refreshNearby(source, query)

        clock.nowMillis += TTL
        repo.refreshNearby(source, query)
        assertEquals(2, source.calls)

        clock.nowMillis += TTL - 1
        repo.refreshNearby(source, query)
        assertEquals(2, source.calls)
    }

    @Test
    fun `a clock that jumps backwards never serves old data`() = runTest {
        val source = sourceOf(nearbyShop(1))
        repo.refreshNearby(source, query)

        clock.nowMillis -= 5
        repo.refreshNearby(source, query)

        assertEquals(2, source.calls)
    }

    @Test
    fun `force bypasses the cache and renews it`() = runTest {
        val source = FakeShopSource { listOf(nearbyShop(it.limit)) }
        repo.refreshNearby(source, query.copy(limit = 1))

        val forced = repo.refreshNearby(source, query.copy(limit = 1), force = true)
        assertEquals(2, source.calls)
        assertEquals(listOf("fake:1"), ids(forced))

        clock.nowMillis += TTL - 1
        repo.refreshNearby(source, query.copy(limit = 1))
        assertEquals(2, source.calls)
    }

    @Test
    fun `source radius keyword and limit each make a different request`() = runTest {
        val source = sourceOf(nearbyShop(1))
        val other = sourceOf(nearbyShop(1), id = "other")
        repo.refreshNearby(source, query)

        repo.refreshNearby(source, query.copy(radiusMeters = 2000))
        repo.refreshNearby(source, query.copy(keyword = "川菜"))
        repo.refreshNearby(source, query.copy(limit = 20))
        repo.refreshNearby(other, query)

        assertEquals(4, source.calls)
        assertEquals(1, other.calls)
    }

    @Test
    fun `centers that round to the same four decimals share a cache entry`() = runTest {
        val source = sourceOf(nearbyShop(1))
        repo.refreshNearby(source, query.copy(center = GeoPoint(39.90871, 116.39749)))

        repo.refreshNearby(source, query.copy(center = GeoPoint(39.90869, 116.39751)))
        assertEquals(1, source.calls)

        repo.refreshNearby(source, query.copy(center = GeoPoint(39.9089, 116.3975)))
        assertEquals(2, source.calls)
    }

    @Test
    fun `no keyword and a blank keyword are the same request`() = runTest {
        val source = sourceOf(nearbyShop(1))
        repo.refreshNearby(source, query.copy(keyword = null))

        repo.refreshNearby(source, query.copy(keyword = "  "))

        assertEquals(1, source.calls)
    }

    @Test
    fun `a cache hit puts that request's shops back into the pool`() = runTest {
        val general = sourceOf(nearbyShop(1), nearbyShop(2))
        val milkTea = sourceOf(nearbyShop(3))
        repo.refreshNearby(general, query)
        repo.refreshNearby(milkTea, query.copy(keyword = "奶茶"))
        assertEquals(listOf("fake:3"), ids(repo.all()))

        val again = repo.refreshNearby(general, query)

        assertEquals(1, general.calls)
        assertEquals(listOf("fake:1", "fake:2"), ids(again))
        assertEquals(again, repo.all())
    }

    @Test
    fun `a zero ttl disables caching`() = runTest {
        val uncached = ShopRepository(clock = clock, cacheTtlMillis = 0)
        val source = sourceOf(nearbyShop(1))

        uncached.refreshNearby(source, query)
        uncached.refreshNearby(source, query)

        assertEquals(2, source.calls)
    }

    @Test
    fun `old cache entries are eventually forgotten`() = runTest {
        val source = FakeShopSource { listOf(nearbyShop(it.limit)) }
        for (limit in 1..100) repo.refreshNearby(source, query.copy(limit = limit))
        assertEquals(100, source.calls)

        repo.refreshNearby(source, query.copy(limit = 100))
        assertEquals(100, source.calls)

        repo.refreshNearby(source, query.copy(limit = 1))
        assertEquals(101, source.calls)
    }

    // ---- failures ----

    @Test
    fun `a failed refresh keeps the previous data and rethrows`() = runTest {
        repo.refreshNearby(sourceOf(nearbyShop(1)), query)
        repo.addImported(listOf(importedShop(1)))
        val failing = FakeShopSource { throw ShopSourceException("boom", Kind.NETWORK) }

        val error = assertFailsWith<ShopSourceException> { repo.refreshNearby(failing, query.copy(radiusMeters = 100)) }

        assertEquals(Kind.NETWORK, error.kind)
        assertEquals(listOf("fake:1", "import:1"), ids(repo.all()))
    }

    @Test
    fun `a failed forced refresh keeps the cached data usable`() = runTest {
        var broken = false
        val source = FakeShopSource { if (broken) throw ShopSourceException("boom", Kind.QUOTA) else listOf(nearbyShop(1)) }
        repo.refreshNearby(source, query)
        broken = true

        assertFailsWith<ShopSourceException> { repo.refreshNearby(source, query, force = true) }
        val fromCache = repo.refreshNearby(source, query)

        assertEquals(listOf("fake:1"), ids(fromCache))
        assertEquals(2, source.calls)
    }

    @Test
    fun `a failure is not cached`() = runTest {
        var broken = true
        val source = FakeShopSource { if (broken) throw ShopSourceException("boom", Kind.NETWORK) else listOf(nearbyShop(1)) }
        assertFailsWith<ShopSourceException> { repo.refreshNearby(source, query) }
        broken = false

        val loaded = repo.refreshNearby(source, query)

        assertEquals(listOf("fake:1"), ids(loaded))
        assertEquals(2, source.calls)
    }

    @Test
    fun `a cancelled refresh keeps the previous data`() = runTest {
        repo.refreshNearby(sourceOf(nearbyShop(1)), query)
        val gate = CompletableDeferred<List<Shop>>()
        val slow = FakeShopSource { gate.await() }

        val refresh = launch { repo.refreshNearby(slow, query.copy(radiusMeters = 100)) }
        runCurrent()
        refresh.cancelAndJoin()

        assertEquals(listOf("fake:1"), ids(repo.all()))
    }

    // ---- observing ----

    @Test
    fun `shops mirrors all after every change`() = runTest {
        val seen = mutableListOf<List<Shop>>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { repo.shops.toList(seen) }
        assertEquals(listOf(emptyList()), seen)

        repo.refreshNearby(sourceOf(nearbyShop(1), nearbyShop(2)), query)
        assertEquals(repo.all(), seen.last())

        repo.addImported(listOf(importedShop(1), importedShop(2, name = "附近店2")))
        assertEquals(repo.all(), seen.last())
        assertEquals(listOf("fake:1", "import:2", "import:1"), ids(seen.last()))

        repo.clearImported()
        assertEquals(repo.all(), seen.last())

        repo.clearAll()
        assertEquals(emptyList(), seen.last())
        assertEquals(repo.all(), repo.shops.value)
    }

    // ---- overlapping and concurrent use ----

    @Test
    fun `a refresh that finishes after a newer one does not overwrite it`() = runTest {
        val gate = CompletableDeferred<List<Shop>>()
        val slow = FakeShopSource("slow") { gate.await() }
        val fast = sourceOf(nearbyShop(1), id = "fast")
        val slowRefresh = async { repo.refreshNearby(slow, query) }
        runCurrent()
        assertEquals(1, slow.calls)

        repo.refreshNearby(fast, query)
        gate.complete(listOf(nearbyShop(2)))

        assertEquals(listOf("fake:2"), ids(slowRefresh.await()))
        assertEquals(listOf("fake:1"), ids(repo.all()))
    }

    @Test
    fun `imports made while a refresh is running survive it`() = runTest {
        val gate = CompletableDeferred<List<Shop>>()
        val slow = FakeShopSource { gate.await() }
        val refresh = async { repo.refreshNearby(slow, query) }
        runCurrent()

        repo.addImported(listOf(importedShop(1)))
        gate.complete(listOf(nearbyShop(1)))
        refresh.await()

        assertEquals(listOf("fake:1", "import:1"), ids(repo.all()))
    }

    @Test
    fun `clearAll is not undone by a refresh that was already running`() = runTest {
        val gate = CompletableDeferred<List<Shop>>()
        val slow = FakeShopSource { gate.await() }
        val refresh = async { repo.refreshNearby(slow, query) }
        runCurrent()

        repo.clearAll()
        gate.complete(listOf(nearbyShop(1)))
        refresh.await()
        assertEquals(emptyList(), repo.all())

        repo.refreshNearby(sourceOf(nearbyShop(2)), query)
        assertEquals(listOf("fake:2"), ids(repo.all()))
    }

    @Test
    fun `concurrent refreshes imports and reads leave a consistent pool`() = runTest {
        val workers = 6
        val sources = List(workers) { w -> FakeShopSource("src$w", List(5) { n -> Shop(id = "src$w:$n", name = "src$w-店$n", source = "src$w") }) }

        withContext(Dispatchers.Default) {
            coroutineScope {
                for (w in 0 until workers) {
                    launch {
                        repeat(100) { i -> repo.refreshNearby(sources[w], query.copy(limit = i % 20 + 1), force = i % 3 == 0) }
                    }
                    launch {
                        repeat(100) { i -> repo.addImported(listOf(importedShop(w * 1000 + i), importedShop(100_000 + i, name = "共用$i"))) }
                    }
                    launch {
                        repeat(300) {
                            val snapshot = repo.all()
                            check(snapshot.size == snapshot.map { shop -> shop.id }.toSet().size) { "duplicate ids in a snapshot" }
                        }
                    }
                }
            }
        }

        val all = repo.all()
        assertEquals(all, repo.shops.value)
        assertEquals(all.size, all.map { it.id }.toSet().size)
        val imported = all.filter { it.source == "import" }
        assertEquals(workers * 100 + 100, imported.size)
        val nearby = all.filter { it.source != "import" }
        assertEquals(5, nearby.size)
        assertEquals(1, nearby.map { it.source }.toSet().size, "nearby shops must come from a single refresh")
    }

    private companion object {
        const val TTL = 60_000L
    }
}
