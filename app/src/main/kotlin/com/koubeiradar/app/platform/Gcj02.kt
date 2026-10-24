package com.koubeiradar.app.platform

import com.koubeiradar.core.model.GeoPoint
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** A raw GPS coordinate in the global WGS-84 datum. A distinct type, so a converted point cannot be converted again by mistake. */
data class Wgs84Point(val lat: Double, val lng: Double)

/**
 * WGS-84 -> GCJ-02 ("Mars coordinates") conversion, the datum used by Amap and the delivery platforms.
 *
 * This is the widely used reverse-engineered algorithm. Mainland China is approximated by the usual bounding box
 * (so border regions of neighbouring countries and Hong Kong/Macau/Taiwan are shifted too, by a few hundred metres at most);
 * everywhere else the point is returned unchanged.
 */
object Gcj02 {
    private const val SEMI_MAJOR_AXIS = 6378245.0
    private const val ECCENTRICITY_SQUARED = 0.00669342162296594323

    private const val MIN_LNG = 72.004
    private const val MAX_LNG = 137.8347
    private const val MIN_LAT = 0.8293
    private const val MAX_LAT = 55.8271

    fun isOutsideChina(lat: Double, lng: Double): Boolean =
        lng < MIN_LNG || lng > MAX_LNG || lat < MIN_LAT || lat > MAX_LAT

    fun fromWgs84(point: Wgs84Point): GeoPoint {
        val (lat, lng) = point
        if (isOutsideChina(lat, lng)) return GeoPoint(lat, lng)

        var dLat = transformLat(lng - 105.0, lat - 35.0)
        var dLng = transformLng(lng - 105.0, lat - 35.0)
        val radLat = Math.toRadians(lat)
        val magic = 1 - ECCENTRICITY_SQUARED * sin(radLat) * sin(radLat)
        val sqrtMagic = sqrt(magic)
        dLat = dLat * 180.0 / (SEMI_MAJOR_AXIS * (1 - ECCENTRICITY_SQUARED) / (magic * sqrtMagic) * Math.PI)
        dLng = dLng * 180.0 / (SEMI_MAJOR_AXIS / sqrtMagic * cos(radLat) * Math.PI)
        return GeoPoint(lat + dLat, lng + dLng)
    }

    private fun transformLat(x: Double, y: Double): Double {
        var ret = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * sqrt(abs(x))
        ret += (20.0 * sin(6.0 * x * Math.PI) + 20.0 * sin(2.0 * x * Math.PI)) * 2.0 / 3.0
        ret += (20.0 * sin(y * Math.PI) + 40.0 * sin(y / 3.0 * Math.PI)) * 2.0 / 3.0
        ret += (160.0 * sin(y / 12.0 * Math.PI) + 320 * sin(y * Math.PI / 30.0)) * 2.0 / 3.0
        return ret
    }

    private fun transformLng(x: Double, y: Double): Double {
        var ret = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * sqrt(abs(x))
        ret += (20.0 * sin(6.0 * x * Math.PI) + 20.0 * sin(2.0 * x * Math.PI)) * 2.0 / 3.0
        ret += (20.0 * sin(x * Math.PI) + 40.0 * sin(x / 3.0 * Math.PI)) * 2.0 / 3.0
        ret += (150.0 * sin(x / 12.0 * Math.PI) + 300.0 * sin(x / 30.0 * Math.PI)) * 2.0 / 3.0
        return ret
    }
}
