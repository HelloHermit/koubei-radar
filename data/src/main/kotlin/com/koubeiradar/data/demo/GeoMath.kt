package com.koubeiradar.data.demo

import com.koubeiradar.core.model.GeoPoint
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Spherical-earth geometry, accurate to well under a metre per kilometre - plenty for sample data. */
internal object GeoMath {
    private const val EARTH_RADIUS_METERS = 6_371_000.0

    /** Great-circle (haversine) distance. */
    fun distanceMeters(from: GeoPoint, to: GeoPoint): Double {
        val fromLat = Math.toRadians(from.lat)
        val toLat = Math.toRadians(to.lat)
        val halfDLat = (toLat - fromLat) / 2
        val halfDLng = Math.toRadians(to.lng - from.lng) / 2
        val a = sin(halfDLat).pow(2) + cos(fromLat) * cos(toLat) * sin(halfDLng).pow(2)
        return 2 * EARTH_RADIUS_METERS * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }

    /** The point [distanceMeters] away from [from], travelling along [bearingDegrees] (0 = north, 90 = east). */
    fun destination(from: GeoPoint, bearingDegrees: Double, distanceMeters: Double): GeoPoint {
        val angularDistance = distanceMeters / EARTH_RADIUS_METERS
        val bearing = Math.toRadians(bearingDegrees)
        val fromLat = Math.toRadians(from.lat)
        val lat = asin(sin(fromLat) * cos(angularDistance) + cos(fromLat) * sin(angularDistance) * cos(bearing))
        val lng = Math.toRadians(from.lng) +
            atan2(sin(bearing) * sin(angularDistance) * cos(fromLat), cos(angularDistance) - sin(fromLat) * sin(lat))
        return GeoPoint(lat = Math.toDegrees(lat), lng = (Math.toDegrees(lng) + 540.0) % 360.0 - 180.0)
    }
}
