package com.koubeiradar.data.baidu

import com.koubeiradar.core.model.GeoPoint
import com.koubeiradar.data.Geocoder
import com.koubeiradar.data.amap.asObject
import com.koubeiradar.data.amap.text
import com.koubeiradar.data.amap.toFiniteDoubleOrNull
import okhttp3.OkHttpClient

/** Baidu `/geocoding/v3/`: address -> coordinates, requested as GCJ-02 (`ret_coordtype=gcj02ll`, Baidu's default is BD-09). */
class BaiduGeocoder(
    apiKey: String,
    httpClient: OkHttpClient = OkHttpClient(),
    baseUrl: String = "https://api.map.baidu.com",
) : Geocoder {
    private val api = BaiduApi(apiKey, httpClient, baseUrl)

    override suspend fun geocode(address: String, city: String?): GeoPoint? {
        val query = address.trim()
        if (query.isEmpty()) return null
        val params = buildList {
            add("address" to query)
            city?.trim()?.takeIf { it.isNotEmpty() }?.let { add("city" to it) }
            add("ret_coordtype" to "gcj02ll")
        }
        val envelope = api.get("geocoding/v3/", params, tolerate = ::isNoResult)
        val location = envelope["result"].asObject()?.get("location").asObject() ?: return null
        val lat = location.text("lat")?.toFiniteDoubleOrNull() ?: return null
        val lng = location.text("lng")?.toFiniteDoubleOrNull() ?: return null
        if (lat !in -90.0..90.0 || lng !in -180.0..180.0) return null
        return GeoPoint(lat = lat, lng = lng)
    }

    /** Community-reported reply for an address Baidu cannot resolve: status 1 with "Internal Service Error:无相关结果". */
    private fun isNoResult(status: Int, message: String?): Boolean =
        status == STATUS_INTERNAL_ERROR && message?.contains(NO_RESULT_TEXT) == true

    private companion object {
        const val STATUS_INTERNAL_ERROR = 1
        const val NO_RESULT_TEXT = "无相关结果"
    }
}
