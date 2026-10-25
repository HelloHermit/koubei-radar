package com.koubeiradar.data.amap

import com.koubeiradar.core.model.GeoPoint
import com.koubeiradar.data.MAX_ADDRESS_LABEL_LENGTH
import com.koubeiradar.data.MockServerTest
import com.koubeiradar.data.ShopSourceException
import com.koubeiradar.data.ShopSourceException.Kind
import com.koubeiradar.data.causeChain
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.SocketEffect
import okhttp3.OkHttpClient
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AmapReverseGeocoderTest : MockServerTest() {
    private val wangjing = GeoPoint(lat = 39.99, lng = 116.48)

    private fun geocoder(client: OkHttpClient = OkHttpClient()) =
        AmapReverseGeocoder(apiKey = KEY, httpClient = client, baseUrl = server.url("/").toString())

    private fun regeo(
        formatted: String = """"北京市朝阳区望京街道望京SOHO塔1"""",
        province: String = """"北京市"""",
        city: String = "[]",
        district: String = """"朝阳区"""",
        township: String = """"望京街道"""",
        neighborhood: String = """{"name":[],"type":[]}""",
        building: String = """{"name":"望京SOHO","type":"商务写字楼"}""",
        street: String = """"阜通东大街"""",
        number: String = """"6号"""",
    ) = """{"status":"1","info":"OK","infocode":"10000","regeocode":{"formatted_address":$formatted,"addressComponent":{
        "country":"中国","province":$province,"city":$city,"citycode":"010","district":$district,"adcode":"110105","township":$township,
        "towncode":"110105026000","neighborhood":$neighborhood,"building":$building,
        "streetNumber":{"street":$street,"number":$number,"location":"116.48,39.99","direction":"东","distance":"20"}}}}"""

    private suspend fun failureOf(response: MockResponse, client: OkHttpClient = OkHttpClient()): ShopSourceException {
        server.enqueue(response)
        return assertFailsWith { geocoder(client).reverseGeocode(wangjing) }
    }

    @Test
    fun `builds district and township plus the building`() = runTest {
        server.enqueue(jsonResponse(regeo()))

        assertEquals("朝阳区望京街道 · 望京SOHO附近", geocoder().reverseGeocode(wangjing))
    }

    @Test
    fun `sends the documented request with longitude first`() = runTest {
        server.enqueue(jsonResponse(regeo()))

        geocoder().reverseGeocode(GeoPoint(lat = 39.9909570, lng = 116.480000))

        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/v3/geocode/regeo", request.url.encodedPath)
        assertEquals(KEY, request.url.queryParameter("key"))
        assertEquals("116.48,39.990957", request.url.queryParameter("location"))
        assertEquals("base", request.url.queryParameter("extensions"))
        assertEquals("JSON", request.url.queryParameter("output"))
        assertEquals("200", request.url.queryParameter("radius"))
    }

    @Test
    fun `the neighborhood, then the street, stand in for a missing building`() = runTest {
        server.enqueue(jsonResponse(regeo(neighborhood = """{"name":"花家地小区","type":"住宅区"}""", building = """{"name":[],"type":[]}""")))
        server.enqueue(jsonResponse(regeo(building = """{"name":[],"type":[]}""")))
        server.enqueue(jsonResponse(regeo(building = "[]", neighborhood = "[]", number = "[]")))

        assertEquals("朝阳区望京街道 · 花家地小区附近", geocoder().reverseGeocode(wangjing))
        assertEquals("朝阳区望京街道 · 阜通东大街6号附近", geocoder().reverseGeocode(wangjing))
        assertEquals("朝阳区望京街道 · 阜通东大街附近", geocoder().reverseGeocode(wangjing))
    }

    @Test
    fun `empty arrays instead of strings are tolerated everywhere`() = runTest {
        server.enqueue(jsonResponse(regeo(township = "[]", building = "[]", neighborhood = "[]", street = "[]", number = "[]")))
        server.enqueue(jsonResponse(regeo(district = "[]", township = "[]", building = "[]", neighborhood = "[]", street = "[]", number = "[]")))
        server.enqueue(jsonResponse("""{"status":"1","info":"OK","infocode":"10000","regeocode":{"formatted_address":[],"addressComponent":[]}}"""))

        assertEquals("朝阳区", geocoder().reverseGeocode(wangjing))
        assertEquals("朝阳区望京街道望京SOHO塔1", geocoder().reverseGeocode(wangjing), "falls back to the address without the province")
        assertNull(geocoder().reverseGeocode(wangjing))
    }

    @Test
    fun `the formatted address loses its province and city prefix`() = runTest {
        val bare = regeo(
            formatted = """"广东省深圳市南山区粤海街道科技园南区"""", province = """"广东省"""", city = """"深圳市"""",
            district = "[]", township = "[]", building = "[]", neighborhood = "[]", street = "[]", number = "[]",
        )
        server.enqueue(jsonResponse(bare))

        assertEquals("南山区粤海街道科技园南区", geocoder().reverseGeocode(wangjing))
    }

    @Test
    fun `a long label is shortened to the limit`() = runTest {
        server.enqueue(jsonResponse(regeo(building = """{"name":"北京市朝阳区望京商务中心国际大厦A座与B座联合办公空间","type":"x"}""")))

        val label = geocoder().reverseGeocode(wangjing)!!

        assertEquals(MAX_ADDRESS_LABEL_LENGTH, label.length)
        assertTrue(label.endsWith("…"))
        assertTrue(label.startsWith("朝阳区望京街道 · 北京市朝阳区"))
    }

    @Test
    fun `nothing found is null`() = runTest {
        server.enqueue(jsonResponse("""{"status":"1","info":"OK","infocode":"10000","regeocode":{"formatted_address":[],"addressComponent":{}}}"""))
        server.enqueue(jsonResponse("""{"status":"1","info":"OK","infocode":"10000","regeocode":[]}"""))
        server.enqueue(jsonResponse("""{"status":"1","info":"OK","infocode":"10000"}"""))

        repeat(3) { assertNull(geocoder().reverseGeocode(wangjing)) }
    }

    @Test
    fun `maps infocodes onto error kinds`() = runTest {
        assertEquals(Kind.AUTH, failureOf(jsonResponse(errorBody("10001", "INVALID_USER_KEY"))).kind)
        assertEquals(Kind.AUTH, failureOf(jsonResponse(errorBody("10009"))).kind)
        assertEquals(Kind.QUOTA, failureOf(jsonResponse(errorBody("10003", "DAILY_QUERY_OVER_LIMIT"))).kind)
        assertEquals(Kind.QUOTA, failureOf(jsonResponse(errorBody("10021"))).kind)
        assertEquals(Kind.OTHER, failureOf(jsonResponse(errorBody("20000", "INVALID_PARAMS"))).kind)
    }

    @Test
    fun `http failures and dropped connections are network errors`() = runTest {
        assertEquals(Kind.NETWORK, failureOf(jsonResponse("oops", 500)).kind)

        val dropped = OkHttpClient.Builder().retryOnConnectionFailure(false).build()
        val error = failureOf(MockResponse.Builder().onResponseStart(SocketEffect.CloseSocket()).build(), dropped)
        assertEquals(Kind.NETWORK, error.kind)
        assertIs<IOException>(error.cause)
    }

    @Test
    fun `malformed responses are parse errors`() = runTest {
        for (body in listOf("not json", "", "[]", """{"foo":1}""")) {
            assertEquals(Kind.PARSE, failureOf(jsonResponse(body)).kind, "body '$body'")
        }
    }

    @Test
    fun `the key never appears in an exception or in the label`() = runTest {
        val failures = listOf(
            failureOf(jsonResponse(errorBody("10001", "key $KEY is invalid"))),
            failureOf(jsonResponse("oops", 500)),
            failureOf(jsonResponse("""{"echo":"$KEY","status":""")),
        )

        for (failure in failures) {
            for (e in failure.causeChain()) {
                assertFalse(KEY in e.message.orEmpty(), "key leaked into ${e.javaClass.simpleName}: ${e.message}")
            }
        }
        assertTrue("***" in failures[0].message.orEmpty())
    }

    private companion object {
        const val KEY = "SECRET-KEY-0123456789abcdef"
    }
}
