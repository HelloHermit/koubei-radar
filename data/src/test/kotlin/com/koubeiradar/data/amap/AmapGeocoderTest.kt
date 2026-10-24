package com.koubeiradar.data.amap

import com.koubeiradar.core.model.GeoPoint
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

class AmapGeocoderTest : MockServerTest() {
    private fun geocoder(client: OkHttpClient = OkHttpClient()) =
        AmapGeocoder(apiKey = KEY, httpClient = client, baseUrl = server.url("/").toString())

    private fun geocodes(vararg entries: String) =
        """{"status":"1","info":"OK","infocode":"10000","count":"${entries.size}","geocodes":[${entries.joinToString(",")}]}"""

    private fun geocode(location: String) = """{"formatted_address":"北京市海淀区中关村大街1号","country":"中国","province":"北京市",
        "citycode":"010","city":"北京市","district":"海淀区","township":[],"neighborhood":{"name":[],"type":[]},"building":{"name":[],"type":[]},
        "adcode":"110108","street":[],"number":[],"location":$location,"level":"门牌号"}"""

    private suspend fun failureOf(response: MockResponse, client: OkHttpClient = OkHttpClient()): ShopSourceException {
        server.enqueue(response)
        return assertFailsWith { geocoder(client).geocode("中关村大街1号") }
    }

    @Test
    fun `returns the location of the first match as latitude and longitude`() = runTest {
        server.enqueue(jsonResponse(geocodes(geocode(""""116.310003,39.991957""""), geocode(""""121.47,31.23""""))))

        val point = geocoder().geocode("中关村大街1号", city = "北京")

        assertEquals(GeoPoint(lat = 39.991957, lng = 116.310003), point)
    }

    @Test
    fun `sends the documented geocoding request`() = runTest {
        server.enqueue(jsonResponse(geocodes(geocode(""""116.31,39.99""""))))

        geocoder().geocode("  中关村大街1号 ", city = "北京")

        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/v3/geocode/geo", request.url.encodedPath)
        assertEquals(KEY, request.url.queryParameter("key"))
        assertEquals("中关村大街1号", request.url.queryParameter("address"))
        assertEquals("北京", request.url.queryParameter("city"))
    }

    @Test
    fun `omits the city when it is null or blank`() = runTest {
        repeat(3) { server.enqueue(jsonResponse(geocodes(geocode(""""116.31,39.99"""")))) }

        geocoder().geocode("中关村大街1号")
        geocoder().geocode("中关村大街1号", city = null)
        geocoder().geocode("中关村大街1号", city = "  ")

        repeat(3) { assertNull(server.takeRequest().url.queryParameter("city")) }
    }

    @Test
    fun `no match is null`() = runTest {
        server.enqueue(jsonResponse("""{"status":"1","info":"OK","infocode":"10000","count":"0","geocodes":[]}"""))
        server.enqueue(jsonResponse("""{"status":"1","info":"OK","infocode":"10000","count":"0"}"""))

        assertNull(geocoder().geocode("不存在的地方"))
        assertNull(geocoder().geocode("不存在的地方"))
    }

    @Test
    fun `skips matches without a usable location`() = runTest {
        server.enqueue(jsonResponse(geocodes(geocode("[]"), geocode(""""abc""""), geocode(""""116.31,39.99""""))))
        server.enqueue(jsonResponse(geocodes(geocode("[]"))))

        assertEquals(GeoPoint(lat = 39.99, lng = 116.31), geocoder().geocode("中关村"))
        assertNull(geocoder().geocode("中关村"))
    }

    @Test
    fun `a blank address is null without a request`() = runTest {
        assertNull(geocoder().geocode(""))
        assertNull(geocoder().geocode("   "))
        assertEquals(0, server.requestCount)
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
        assertEquals(Kind.NETWORK, failureOf(jsonResponse("oops", 404)).kind)

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
    fun `the key never appears in an exception`() = runTest {
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
