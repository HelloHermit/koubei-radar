package com.koubeiradar.app.platform

import com.koubeiradar.core.model.GeoPoint

/** One position fix as the operating system reports it: WGS-84, not yet usable with Amap data. */
data class RawFix(
    val lat: Double,
    val lng: Double,
    val accuracyMeters: Float?,
    val timeMillis: Long,
) {
    /** The single place where a fix leaves the platform layer; everything downstream works in GCJ-02. */
    fun toLocation(): LocationResult.Found = LocationResult.Found(
        point = Gcj02.fromWgs84(Wgs84Point(lat, lng)),
        accuracyMeters = accuracyMeters?.toInt(),
    )
}

sealed interface LocationResult {
    /** [point] is GCJ-02. */
    data class Found(val point: GeoPoint, val accuracyMeters: Int?) : LocationResult

    data object PermissionDenied : LocationResult

    /** No provider is enabled, or none produced a position in time. */
    data object Unavailable : LocationResult
}

interface LocationService {
    fun hasPermission(): Boolean

    /** One-shot position in GCJ-02. Never throws; every failure is a [LocationResult]. */
    suspend fun currentLocation(): LocationResult
}

/**
 * Newest fix that is still recent enough to be trusted when no fresh fix could be obtained.
 * Ties on time prefer the more accurate fix.
 */
fun List<RawFix>.newestWithin(nowMillis: Long, maxAgeMillis: Long): RawFix? =
    filter { nowMillis - it.timeMillis in 0..maxAgeMillis }
        .maxWithOrNull(compareBy<RawFix> { it.timeMillis }.thenByDescending { it.accuracyMeters ?: Float.MAX_VALUE })
