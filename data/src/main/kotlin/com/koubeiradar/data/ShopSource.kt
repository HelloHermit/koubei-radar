package com.koubeiradar.data

import com.koubeiradar.core.model.GeoPoint
import com.koubeiradar.core.model.Shop

data class NearbyQuery(
    val center: GeoPoint,
    val radiusMeters: Int = 3000,
    /** Free-text keyword such as "川菜" or "奶茶"; `null` = all restaurants. */
    val keyword: String? = null,
    val limit: Int = 50,
)

/** A provider of nearby shops. Implementations must be safe to call from any coroutine dispatcher. */
interface ShopSource {
    /** Stable id, also used as the `Shop.source` and the id prefix: "amap", "demo". */
    val id: String
    val displayName: String

    /** Shops within [NearbyQuery.radiusMeters] of the center, nearest first, at most [NearbyQuery.limit]. */
    @Throws(ShopSourceException::class)
    suspend fun nearby(query: NearbyQuery): List<Shop>
}

class ShopSourceException(
    message: String,
    val kind: Kind,
    cause: Throwable? = null,
) : Exception(message, cause) {
    enum class Kind {
        /** Invalid / missing key, wrong key type, IP or package restrictions. */
        AUTH,

        /** Daily quota or QPS limit exceeded. */
        QUOTA,

        /** Connectivity problems and timeouts. */
        NETWORK,

        /** The response could not be understood. */
        PARSE,
        OTHER,
    }
}

/** Address -> coordinates, used when the user types a location instead of granting GPS. */
interface Geocoder {
    /** Returns the GCJ-02 location, or `null` when nothing matches. Throws [ShopSourceException] on failures. */
    @Throws(ShopSourceException::class)
    suspend fun geocode(address: String, city: String? = null): GeoPoint?
}
