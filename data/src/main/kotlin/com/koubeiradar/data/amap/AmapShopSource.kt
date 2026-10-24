package com.koubeiradar.data.amap

import com.koubeiradar.core.model.Shop
import com.koubeiradar.data.NearbyQuery
import com.koubeiradar.data.ShopSource
import com.koubeiradar.data.ShopSourceException
import com.koubeiradar.data.ShopSourceException.Kind
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.roundToInt

/**
 * Nearby restaurants from the official Amap Web Service "POI search 2.0" (`/v5/place/around`),
 * requested with `types=050000` (餐饮服务) and `show_fields=business` which adds `rating` and `cost`.
 * Needs a **Web服务** type key. Spec: `docs/DESIGN.md` §3.1.
 */
class AmapShopSource(
    apiKey: String,
    httpClient: OkHttpClient = OkHttpClient(),
    /** Overridable for tests (MockWebServer). */
    baseUrl: String = "https://restapi.amap.com",
    /** Decides [Shop.openNow]; injectable so tests do not depend on the wall clock. */
    private val clock: () -> Long = System::currentTimeMillis,
) : ShopSource {
    override val id: String = SOURCE_ID
    override val displayName: String = "高德地图"

    private val api = AmapApi(apiKey, httpClient, baseUrl)

    override suspend fun nearby(query: NearbyQuery): List<Shop> {
        if (query.limit <= 0) return emptyList()
        val center = query.center
        if (!center.lat.isFinite() || !center.lng.isFinite()) {
            throw ShopSourceException("查询坐标无效", Kind.OTHER)
        }
        val now = clock()
        val shops = LinkedHashMap<String, Shop>()
        for (pageNumber in 1..MAX_PAGES) {
            val page = fetchPage(query, pageNumber)
            for (poi in page) {
                val shop = (poi as? JsonObject)?.toShop(now) ?: continue
                shops.putIfAbsent(shop.id, shop)
            }
            if (page.size < PAGE_SIZE || shops.size >= query.limit) break
        }
        return shops.values.sortedBy { it.distanceMeters ?: Int.MAX_VALUE }.take(query.limit)
    }

    private suspend fun fetchPage(query: NearbyQuery, pageNumber: Int): JsonArray {
        val params = buildList {
            add("location" to "${formatCoordinate(query.center.lng)},${formatCoordinate(query.center.lat)}")
            add("radius" to query.radiusMeters.coerceIn(1, MAX_RADIUS_METERS).toString())
            add("types" to RESTAURANT_TYPE)
            add("sortrule" to "distance")
            add("page_size" to PAGE_SIZE.toString())
            add("page_num" to pageNumber.toString())
            add("show_fields" to "business")
            query.keyword?.trim()?.takeIf { it.isNotEmpty() }?.let { add("keywords" to it) }
        }
        return when (val pois = api.get("v5/place/around", params)["pois"]) {
            is JsonArray -> pois
            null, is JsonNull -> JsonArray(emptyList())
            is JsonPrimitive -> if (pois.content.isBlank()) JsonArray(emptyList()) else throw malformedPois()
            is JsonObject -> throw malformedPois()
        }
    }

    private fun malformedPois() = ShopSourceException("高德响应中的 pois 格式异常", Kind.PARSE)

    /** `null` when the POI lacks an id, a name or a usable location. */
    private fun JsonObject.toShop(nowMillis: Long): Shop? {
        val poiId = text("id") ?: return null
        val name = text("name") ?: return null
        val location = parseLngLat(text("location")) ?: return null
        val business = this["business"].asObject()
        val category = text("type")?.split(';')?.map { it.trim() }?.lastOrNull { it.isNotEmpty() }
        return Shop(
            id = "$SOURCE_ID:$poiId",
            name = name,
            source = SOURCE_ID,
            category = category,
            rating = business?.text("rating")?.toFiniteDoubleOrNull()?.takeIf { it > 0.0 && it <= 5.0 },
            avgPrice = business?.text("cost")?.toFiniteDoubleOrNull()?.takeIf { it > 0.0 },
            distanceMeters = text("distance")?.let(::parseMeters),
            address = text("address"),
            location = location,
            tags = tagsOf(business?.text("tag"), category),
            openNow = business?.text("opentime_today")?.let { OpeningHours.isOpenAt(it, nowMillis) },
            phone = business?.text("tel")?.split(*LIST_SEPARATORS)?.map { it.trim() }?.firstOrNull { it.isNotEmpty() },
        )
    }

    private fun tagsOf(rawTags: String?, category: String?): List<String> {
        val tags = rawTags.orEmpty().split(*LIST_SEPARATORS).map { it.trim() }.filter { it.isNotEmpty() }
        return (tags + listOfNotNull(category)).distinct().take(MAX_TAGS)
    }

    private fun parseMeters(text: String): Int? =
        text.toIntOrNull()?.takeIf { it >= 0 }
            ?: text.toFiniteDoubleOrNull()?.takeIf { it >= 0.0 && it <= Int.MAX_VALUE }?.roundToInt()

    /** At most six decimals (Amap rejects more), without trailing zeros and never in exponent notation. */
    private fun formatCoordinate(value: Double): String {
        val plain = BigDecimal.valueOf(value).setScale(6, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
        return if ('.' in plain) plain else "$plain.0"
    }

    private companion object {
        const val SOURCE_ID = "amap"
        const val RESTAURANT_TYPE = "050000"
        const val PAGE_SIZE = 25
        const val MAX_PAGES = 8
        const val MAX_RADIUS_METERS = 50_000
        const val MAX_TAGS = 6
        val LIST_SEPARATORS = charArrayOf(';', ',', '，', '、', '；')
    }
}
