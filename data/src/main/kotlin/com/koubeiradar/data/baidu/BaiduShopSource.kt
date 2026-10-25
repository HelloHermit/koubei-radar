package com.koubeiradar.data.baidu

import com.koubeiradar.core.model.GeoPoint
import com.koubeiradar.core.model.Shop
import com.koubeiradar.data.NearbyQuery
import com.koubeiradar.data.ShopSource
import com.koubeiradar.data.ShopSourceException
import com.koubeiradar.data.ShopSourceException.Kind
import com.koubeiradar.data.amap.OpeningHours
import com.koubeiradar.data.amap.asObject
import com.koubeiradar.data.amap.formatCoordinate
import com.koubeiradar.data.amap.text
import com.koubeiradar.data.amap.toFiniteDoubleOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import kotlin.math.roundToInt

/**
 * Nearby restaurants from Baidu's Place API 3.0 circle search (`/place/v3/around`, `scope=2`), which unlike Amap returns
 * the number of ratings (`comment_num`). Input and output coordinates are GCJ-02 (`coord_type=2`,
 * `ret_coordtype=gcj02ll`; Baidu defaults to BD-09). Needs a **服务端** AK. Spec: `docs/DESIGN.md` §3.4.
 */
class BaiduShopSource(
    apiKey: String,
    httpClient: OkHttpClient = OkHttpClient(),
    /** Overridable for tests (MockWebServer). */
    baseUrl: String = "https://api.map.baidu.com",
    /** Decides [Shop.openNow]; injectable so tests do not depend on the wall clock. */
    private val clock: () -> Long = System::currentTimeMillis,
) : ShopSource {
    override val id: String = SOURCE_ID
    override val displayName: String = "百度地图"

    private val api = BaiduApi(apiKey, httpClient, baseUrl)

    override suspend fun nearby(query: NearbyQuery): List<Shop> {
        if (query.limit <= 0) return emptyList()
        val center = query.center
        if (!center.lat.isFinite() || !center.lng.isFinite()) {
            throw ShopSourceException("查询坐标无效", Kind.OTHER)
        }
        val radius = query.radiusMeters.coerceIn(1, MAX_RADIUS_METERS)
        val now = clock()
        val shops = LinkedHashMap<String, Shop>()
        for (pageIndex in 0 until MAX_PAGES) {
            val page = fetchPage(query, radius, pageIndex)
            for (poi in page.results) {
                val shop = (poi as? JsonObject)?.toShop(now) ?: continue
                if (shop.distanceMeters?.let { it > radius } == true) continue
                shops.putIfAbsent(shop.id, shop)
            }
            // A page may hold fewer than PAGE_SIZE results (the docs say "at most 10 per request"), so only `total` or an empty page ends the search.
            val exhausted = page.results.isEmpty() ||
                (page.total?.let { (pageIndex + 1) * PAGE_SIZE >= it } ?: (page.results.size < PAGE_SIZE))
            if (exhausted || shops.size >= query.limit) break
        }
        return shops.values.sortedBy { it.distanceMeters ?: Int.MAX_VALUE }.take(query.limit)
    }

    private class Page(val results: JsonArray, val total: Int?)

    private suspend fun fetchPage(query: NearbyQuery, radius: Int, pageIndex: Int): Page {
        val params = buildList {
            add("query" to RESTAURANT_QUERY)
            query.keyword?.trim()?.takeIf { it.isNotEmpty() }?.let { add("type" to it) }
            add("location" to "${formatCoordinate(query.center.lat)},${formatCoordinate(query.center.lng)}")
            add("radius" to radius.toString())
            add("radius_limit" to "true")
            add("coord_type" to "2")
            add("ret_coordtype" to "gcj02ll")
            add("scope" to "2")
            add("filter" to "industry_type:cater|sort_name:distance|sort_rule:1")
            add("page_size" to PAGE_SIZE.toString())
            add("page_num" to pageIndex.toString())
        }
        val envelope = api.get("place/v3/around", params)
        val results = when (val results = envelope["results"]) {
            is JsonArray -> results
            null, is JsonNull -> JsonArray(emptyList())
            is JsonPrimitive -> if (results.content.isBlank()) JsonArray(emptyList()) else throw malformedResults()
            is JsonObject -> throw malformedResults()
        }
        return Page(results, envelope.text("total")?.toIntOrNull())
    }

    private fun malformedResults() = ShopSourceException("百度地图响应中的 results 格式异常", Kind.PARSE)

    /** `null` when the POI lacks a uid, a name or a usable location. */
    private fun JsonObject.toShop(nowMillis: Long): Shop? {
        val uid = text("uid") ?: return null
        val name = text("name") ?: return null
        val location = parseLocation(this["location"].asObject()) ?: return null
        // The docs list the rating fields after `detail_info` without showing the nesting and have no example reply, so read them from `detail_info` (may be missing) and from the POI itself.
        val detail = this["detail_info"].asObject()
        fun field(key: String): String? = detail?.text(key) ?: text(key)

        val tagSegments = (field("classified_poi_tag") ?: field("tag"))
            ?.split(*LIST_SEPARATORS)?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
        val category = tagSegments.lastOrNull() ?: field("type")?.takeIf { type -> type.any { it.code > MAX_ASCII } }
        return Shop(
            id = "$SOURCE_ID:$uid",
            name = name,
            source = SOURCE_ID,
            category = category,
            rating = field("overall_rating")?.toFiniteDoubleOrNull()?.takeIf { it > 0.0 && it <= 5.0 },
            ratingCount = field("comment_num")?.let(::parseCount),
            avgPrice = field("price")?.toFiniteDoubleOrNull()?.takeIf { it > 0.0 },
            distanceMeters = field("distance")?.let(::parseCount),
            address = text("address"),
            location = location,
            tags = (tagSegments + listOfNotNull(category)).distinct().take(MAX_TAGS),
            openNow = field("shop_hours")?.let { OpeningHours.isOpenAt(it, nowMillis) },
            phone = text("telephone")?.split(*LIST_SEPARATORS)?.map { it.trim() }?.firstOrNull { it.isNotEmpty() },
        )
    }

    private fun parseLocation(location: JsonObject?): GeoPoint? {
        val lat = location?.text("lat")?.toFiniteDoubleOrNull() ?: return null
        val lng = location.text("lng")?.toFiniteDoubleOrNull() ?: return null
        if (lat !in -90.0..90.0 || lng !in -180.0..180.0) return null
        return GeoPoint(lat = lat, lng = lng)
    }

    /** Baidu sends counts and distances as strings (`"1234"`), sometimes as numbers or `""`. */
    private fun parseCount(text: String): Int? =
        text.toIntOrNull()?.takeIf { it >= 0 }
            ?: text.toFiniteDoubleOrNull()?.takeIf { it >= 0.0 && it <= Int.MAX_VALUE }?.roundToInt()

    private companion object {
        const val SOURCE_ID = "baidu"
        const val RESTAURANT_QUERY = "美食"
        /** Documented range is 10-20, but a request "returns at most 10 results"; 10 is unambiguous. */
        const val PAGE_SIZE = 10
        /** `total` is capped at 150 (documented), i.e. 15 pages of 10. */
        const val MAX_PAGES = 15
        /** Baidu documents no maximum radius; this is only a sanity bound. */
        const val MAX_RADIUS_METERS = 50_000
        const val MAX_TAGS = 6
        const val MAX_ASCII = 127
        val LIST_SEPARATORS = charArrayOf(';', ',', '，', '、', '；')
    }
}
