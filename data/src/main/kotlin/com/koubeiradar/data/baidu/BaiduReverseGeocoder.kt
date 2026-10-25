package com.koubeiradar.data.baidu

import com.koubeiradar.core.model.GeoPoint
import com.koubeiradar.data.ReverseGeocoder
import com.koubeiradar.data.amap.asObject
import com.koubeiradar.data.amap.formatCoordinate
import com.koubeiradar.data.amap.text
import com.koubeiradar.data.shortAddressLabel
import okhttp3.OkHttpClient

/**
 * Baidu `/reverse_geocoding/v3/`: GCJ-02 coordinates (`coordtype=gcj02ll`, the default is BD-09) -> a short label such as
 * "朝阳区望京街道 · 望京附近". POI data (`extensions_poi`) is off, so the hint is the first business district, else the street.
 */
class BaiduReverseGeocoder(
    apiKey: String,
    httpClient: OkHttpClient = OkHttpClient(),
    baseUrl: String = "https://api.map.baidu.com",
) : ReverseGeocoder {
    private val api = BaiduApi(apiKey, httpClient, baseUrl)

    override suspend fun reverseGeocode(point: GeoPoint): String? {
        val params = listOf(
            // Unlike Amap, Baidu wants latitude first.
            "location" to "${formatCoordinate(point.lat)},${formatCoordinate(point.lng)}",
            "coordtype" to "gcj02ll",
            "extensions_poi" to "0",
        )
        val result = api.get("reverse_geocoding/v3/", params, tolerate = ::isNoResult)["result"].asObject() ?: return null
        val component = result["addressComponent"].asObject()
        fun part(key: String) = component?.text(key)

        val area = listOfNotNull(part("district"), part("town")).joinToString("")
        val business = result.text("business")?.split(',', '，')?.map { it.trim() }?.firstOrNull { it.isNotEmpty() }
        val street = part("street")?.let { it + part("street_number").orEmpty() }
        return shortAddressLabel(area, business ?: street, result.text("formatted_address"), part("province"), part("city"))
    }

    /** The geocoding endpoint's community-reported "nothing found" reply; assumed (not verified) to be the same here. */
    private fun isNoResult(status: Int, message: String?): Boolean =
        status == STATUS_INTERNAL_ERROR && message?.contains(NO_RESULT_TEXT) == true

    private companion object {
        const val STATUS_INTERNAL_ERROR = 1
        const val NO_RESULT_TEXT = "无相关结果"
    }
}
