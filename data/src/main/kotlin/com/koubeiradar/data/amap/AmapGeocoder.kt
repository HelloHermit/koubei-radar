package com.koubeiradar.data.amap

import com.koubeiradar.core.model.GeoPoint
import com.koubeiradar.data.Geocoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient

/** Amap `/v3/geocode/geo`: address -> GCJ-02 coordinates. */
class AmapGeocoder(
    apiKey: String,
    httpClient: OkHttpClient = OkHttpClient(),
    baseUrl: String = "https://restapi.amap.com",
) : Geocoder {
    private val api = AmapApi(apiKey, httpClient, baseUrl)

    override suspend fun geocode(address: String, city: String?): GeoPoint? {
        val query = address.trim()
        if (query.isEmpty()) return null
        val params = buildList {
            add("address" to query)
            city?.trim()?.takeIf { it.isNotEmpty() }?.let { add("city" to it) }
        }
        val geocodes = api.get("v3/geocode/geo", params)["geocodes"] as? JsonArray ?: return null
        return geocodes.firstNotNullOfOrNull { (it as? JsonObject)?.let { geocode -> parseLngLat(geocode.text("location")) } }
    }
}
