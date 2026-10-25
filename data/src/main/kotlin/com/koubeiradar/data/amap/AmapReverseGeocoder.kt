package com.koubeiradar.data.amap

import com.koubeiradar.core.model.GeoPoint
import com.koubeiradar.data.ReverseGeocoder
import com.koubeiradar.data.shortAddressLabel
import okhttp3.OkHttpClient

/**
 * Amap `/v3/geocode/regeo` (`extensions=base`): GCJ-02 coordinates -> a short label such as "朝阳区望京街道 · 望京SOHO附近".
 * Empty fields come back as `[]` instead of a string, hence the tolerant [asText] / [asObject] reads.
 */
class AmapReverseGeocoder(
    apiKey: String,
    httpClient: OkHttpClient = OkHttpClient(),
    baseUrl: String = "https://restapi.amap.com",
) : ReverseGeocoder {
    private val api = AmapApi(apiKey, httpClient, baseUrl)

    override suspend fun reverseGeocode(point: GeoPoint): String? {
        val params = listOf(
            "location" to "${formatCoordinate(point.lng)},${formatCoordinate(point.lat)}",
            "radius" to RADIUS_METERS,
            "extensions" to "base",
            "output" to "JSON",
        )
        val regeocode = api.get("v3/geocode/regeo", params)["regeocode"].asObject() ?: return null
        val component = regeocode["addressComponent"].asObject()
        fun part(key: String) = component?.text(key)
        fun nested(key: String, field: String) = component?.get(key).asObject()?.text(field)

        val area = listOfNotNull(part("district"), part("township")).joinToString("")
        val street = nested("streetNumber", "street")?.let { it + nested("streetNumber", "number").orEmpty() }
        val hint = nested("building", "name") ?: nested("neighborhood", "name") ?: street
        return shortAddressLabel(area, hint, regeocode.text("formatted_address"), part("province"), part("city"))
    }

    private companion object {
        const val RADIUS_METERS = "200"
    }
}
