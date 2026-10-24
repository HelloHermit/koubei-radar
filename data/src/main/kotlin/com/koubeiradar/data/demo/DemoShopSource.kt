package com.koubeiradar.data.demo

import com.koubeiradar.core.model.GeoPoint
import com.koubeiradar.core.model.Shop
import com.koubeiradar.data.NearbyQuery
import com.koubeiradar.data.ShopSource
import kotlin.math.roundToInt

/**
 * Offline, deterministic sample data so the app is usable (and demo-able) without any API key.
 * Contains deliberately tricky shops (new shop with a perfect rating, a hygiene complaint, huge-volume 4.6,
 * unknown rating count, closed shop ...) so the scoring behaviour is visible. Spec: `docs/DESIGN.md` §3.2.
 *
 * The shops sit at fixed offsets (150 m - 4.6 km) from [NearbyQuery.center], so the same query always yields the same
 * shops and moving the center moves them along. [DemoShopIds] names the showcase shops.
 */
class DemoShopSource : ShopSource {
    override val id: String = DemoCatalog.SOURCE_ID
    override val displayName: String = "演示数据（离线）"

    override suspend fun nearby(query: NearbyQuery): List<Shop> {
        val keyword = query.keyword?.trim()?.takeIf { it.isNotEmpty() }
        return DemoCatalog.entries.asSequence()
            .filter { keyword == null || it.shop.matches(keyword) }
            .map { it.placedAround(query.center) }
            .filter { it.distance <= query.radiusMeters }
            .sortedWith(compareBy<Placed> { it.distance }.thenBy { it.shop.id })
            .take(query.limit.coerceAtLeast(0))
            .map { it.shop }
            .toList()
    }

    private class Placed(val distance: Int, val shop: Shop)

    private fun DemoEntry.placedAround(center: GeoPoint): Placed {
        val exact = GeoMath.destination(center, bearingDegrees.toDouble(), offsetMeters.toDouble())
        val location = GeoPoint(lat = roundToMicrodegrees(exact.lat), lng = roundToMicrodegrees(exact.lng))
        val distance = GeoMath.distanceMeters(center, location).roundToInt()
        return Placed(distance, shop.copy(location = location, distanceMeters = distance))
    }

    private fun Shop.matches(keyword: String): Boolean =
        (listOfNotNull(name, category) + tags).any { it.contains(keyword, ignoreCase = true) }

    private fun roundToMicrodegrees(degrees: Double): Double = Math.round(degrees * 1_000_000.0) / 1_000_000.0
}
