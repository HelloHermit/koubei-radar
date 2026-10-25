package com.koubeiradar.data.baidu

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

class BaiduGeocoderTest : MockServerTest() {
    private fun geocoder(client: OkHttpClient = OkHttpClient()) =
        BaiduGeocoder(apiKey = KEY, httpClient = client, baseUrl = server.url("/").toString())

    private fun found(location: String) =
        """{"status":0,"result":{"location":$location,"precise":1,"confidence":80,"comprehension":100,"level":"门址"}}"""

    private suspend fun failureOf(response: MockResponse, client: OkHttpClient = OkHttpClient()): ShopSourceException {
        server.enqueue(response)
        return assertFailsWith { geocoder(client).geocode("中关村大街1号") }
    }

    @Test
    fun `returns the location as latitude and longitude`() = runTest {
        server.enqueue(jsonResponse(found("""{"lng":116.310003,"lat":39.991957}""")))

        assertEquals(GeoPoint(lat = 39.991957, lng = 116.310003), geocoder().geocode("中关村大街1号", city = "北京"))
    }

    @Test
    fun `accepts coordinates sent as strings`() = runTest {
        server.enqueue(jsonResponse(found("""{"lng":"116.31","lat":"39.99"}""")))

        assertEquals(GeoPoint(lat = 39.99, lng = 116.31), geocoder().geocode("中关村"))
    }

    @Test
    fun `asks for gcj02 coordinates and sends the documented request`() = runTest {
        server.enqueue(jsonResponse(found("""{"lng":116.31,"lat":39.99}""")))

        geocoder().geocode("  中关村大街1号 ", city = "北京")

        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/geocoding/v3/", request.url.encodedPath)
        assertEquals(KEY, request.url.queryParameter("ak"))
        assertEquals("json", request.url.queryParameter("output"))
        assertEquals("中关村大街1号", request.url.queryParameter("address"))
        assertEquals("北京", request.url.queryParameter("city"))
        assertEquals("gcj02ll", request.url.queryParameter("ret_coordtype"))
    }

    @Test
    fun `omits the city when it is null or blank`() = runTest {
        repeat(3) { server.enqueue(jsonResponse(found("""{"lng":116.31,"lat":39.99}"""))) }

        geocoder().geocode("中关村大街1号")
        geocoder().geocode("中关村大街1号", city = null)
        geocoder().geocode("中关村大街1号", city = "  ")

        repeat(3) { assertNull(server.takeRequest().url.queryParameter("city")) }
    }

    @Test
    fun `no match is null`() = runTest {
        server.enqueue(jsonResponse("""{"status":1,"msg":"Internal Service Error:无相关结果"}"""))
        server.enqueue(jsonResponse("""{"status":0}"""))
        server.enqueue(jsonResponse("""{"status":0,"result":[]}"""))
        server.enqueue(jsonResponse("""{"status":0,"result":{"precise":0}}"""))

        repeat(4) { assertNull(geocoder().geocode("不存在的地方")) }
    }

    @Test
    fun `a status 1 without the no result text is a failure`() = runTest {
        assertEquals(Kind.OTHER, failureOf(jsonResponse("""{"status":1,"msg":"Internal Service Error"}""")).kind)
    }

    @Test
    fun `unusable locations are null`() = runTest {
        for (location in listOf("""{"lng":116.31}""", """{"lng":"abc","lat":"x"}""", """{"lng":116.31,"lat":95}""", """{"lng":200,"lat":39.9}""", """"39.9,116.3"""")) {
            server.enqueue(jsonResponse(found(location)))
            assertNull(geocoder().geocode("中关村"), location)
        }
    }

    @Test
    fun `a blank address is null without a request`() = runTest {
        assertNull(geocoder().geocode(""))
        assertNull(geocoder().geocode("   "))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `maps statuses onto error kinds`() = runTest {
        assertEquals(Kind.AUTH, failureOf(jsonResponse(baiduError(5, "ak不存在或者非法"))).kind)
        assertEquals(Kind.AUTH, failureOf(jsonResponse(baiduError(101))).kind)
        assertEquals(Kind.AUTH, failureOf(jsonResponse(baiduError(102))).kind)
        assertEquals(Kind.AUTH, failureOf(jsonResponse(baiduError(240))).kind)
        assertEquals(Kind.QUOTA, failureOf(jsonResponse(baiduError(4))).kind)
        assertEquals(Kind.QUOTA, failureOf(jsonResponse(baiduError(302))).kind)
        assertEquals(Kind.OTHER, failureOf(jsonResponse(baiduError(2, "Parameter Invalid"))).kind)
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
        for (body in listOf("not json", "", "[]", """{"foo":1}""", "<html>nope</html>")) {
            assertEquals(Kind.PARSE, failureOf(jsonResponse(body)).kind, "body '$body'")
        }
    }

    @Test
    fun `the key never appears in an exception`() = runTest {
        val failures = listOf(
            failureOf(jsonResponse(baiduError(5, "ak $KEY is invalid"))),
            failureOf(jsonResponse("oops", 500)),
            failureOf(jsonResponse("""{"echo":"$KEY","status":""")),
            failureOf(jsonResponse("<html>$KEY</html>")),
        )

        for (failure in failures) {
            for (e in failure.causeChain()) {
                assertFalse(KEY in e.message.orEmpty(), "key leaked into ${e.javaClass.simpleName}: ${e.message}")
            }
        }
        assertTrue("***" in failures[0].message.orEmpty())
    }

    private companion object {
        const val KEY = "SECRET-AK-0123456789abcdefghijklmn"
    }
}
