package com.koubeiradar.data.amap

import com.koubeiradar.core.model.GeoPoint
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Amap reports "no value" as `[]` about as often as `""` or a missing key, and sends numbers as strings (sometimes as
 * numbers). This returns the trimmed text of any primitive, the `;`-joined texts of an array, and `null` for everything empty.
 */
internal fun JsonElement?.asText(): String? = when (this) {
    null, is JsonNull -> null
    is JsonPrimitive -> content.trim().ifEmpty { null }
    is JsonArray -> mapNotNull { it.asText() }.joinToString(";").ifEmpty { null }
    is JsonObject -> null
}

internal fun JsonObject.text(key: String): String? = this[key].asText()

internal fun JsonElement?.asObject(): JsonObject? = this as? JsonObject

internal fun String.toFiniteDoubleOrNull(): Double? = toDoubleOrNull()?.takeIf { it.isFinite() }

/** Parses Amap's `"lng,lat"` pair into a [GeoPoint]; `null` unless both numbers are present and in range. */
internal fun parseLngLat(text: String?): GeoPoint? {
    val parts = text?.split(',') ?: return null
    if (parts.size != 2) return null
    val lng = parts[0].trim().toFiniteDoubleOrNull() ?: return null
    val lat = parts[1].trim().toFiniteDoubleOrNull() ?: return null
    if (lat !in -90.0..90.0 || lng !in -180.0..180.0) return null
    return GeoPoint(lat = lat, lng = lng)
}

/** At most six decimals (Amap rejects more), without trailing zeros and never in exponent notation. */
internal fun formatCoordinate(value: Double): String {
    val plain = BigDecimal.valueOf(value).setScale(6, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
    return if ('.' in plain) plain else "$plain.0"
}
