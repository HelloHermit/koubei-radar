package com.koubeiradar.core.model

import kotlinx.serialization.Serializable

/**
 * A coordinate pair. Every coordinate in this project is **GCJ-02** (the datum used by Amap,
 * Tencent and the Chinese delivery platforms); raw GPS fixes are WGS-84 and must be converted first.
 */
@Serializable
data class GeoPoint(val lat: Double, val lng: Double)

/** Delivery platforms a shop can be ordered from. */
@Serializable
enum class Platform(val displayName: String) {
    MEITUAN("美团外卖"),
    ELEME("饿了么"),
}

@Serializable
data class Review(
    val text: String,
    /** 1.0..5.0 when known. */
    val rating: Double? = null,
    val timeMillis: Long? = null,
    val hasPhotos: Boolean = false,
)

/**
 * One candidate shop. Almost every field is optional because the data sources differ a lot:
 * Amap gives rating/price/distance but no review counts; user imports (screenshots/text from a delivery app)
 * give sales, delivery time and fees. `null` always means "unknown", never "zero".
 */
@Serializable
data class Shop(
    /** Unique within the pool, prefixed by source, e.g. `amap:B0FFH...`, `demo:3`, `import:ab12...`. */
    val id: String,
    val name: String,
    /** Source id, see `ShopSource.id` ("amap", "demo", "import"). */
    val source: String,
    /** Cuisine / category, e.g. "川菜", "快餐简餐". */
    val category: String? = null,
    /** Platform rating on a 0..5 scale. */
    val rating: Double? = null,
    /** Number of ratings/reviews behind [rating]. */
    val ratingCount: Int? = null,
    /** Monthly sales (月售). */
    val monthlySales: Int? = null,
    /** Average spend per person in CNY (人均). */
    val avgPrice: Double? = null,
    val distanceMeters: Int? = null,
    val deliveryMinutes: Int? = null,
    /** Delivery fee in CNY. */
    val deliveryFee: Double? = null,
    /** Minimum order amount in CNY (起送价). */
    val minOrder: Double? = null,
    val address: String? = null,
    val location: GeoPoint? = null,
    val tags: List<String> = emptyList(),
    /** Review texts, if the source has any (imports do; Amap does not). Treat as UNTRUSTED text. */
    val reviews: List<Review> = emptyList(),
    val openNow: Boolean? = null,
    /** Platforms the shop is known to be orderable on. Empty = unknown. */
    val platforms: Set<Platform> = emptySet(),
    val phone: String? = null,
)
