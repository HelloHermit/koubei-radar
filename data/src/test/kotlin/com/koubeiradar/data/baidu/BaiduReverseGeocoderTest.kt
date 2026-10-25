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

class BaiduReverseGeocoderTest : MockServerTest() {
    private val wangjing = GeoPoint(lat = 39.99, lng = 116.48)

    private fun geocoder(client: OkHttpClient = OkHttpClient()) =
        BaiduReverseGeocoder(apiKey = KEY, httpClient = client, baseUrl = server.url("/").toString())

    private fun regeo(
        formatted: String = "北京市朝阳区阜通东大街6号",
        business: String = "望京,来广营,花家地",
        province: String = "北京市",
        city: String = "北京市",
        district: String = "朝阳区",
        town: String = "望京街道",
        street: String = "阜通东大街",
        streetNumber: String = "6号",
    ) = """{"status":0,"result":{"location":{"lng":116.48,"lat":39.99},"formatted_address":"$formatted","business":"$business",
        "addressComponent":{"country":"中国","province":"$province","city":"$city","district":"$district","town":"$town","adcode":"110105",
        "street":"$street","street_number":"$streetNumber","direction":"附近","distance":"12"},"cityCode":131}}"""

    private suspend fun failureOf(response: MockResponse, client: OkHttpClient = OkHttpClient()): ShopSourceException {
        server.enqueue(response)
        return assertFailsWith { geocoder(client).reverseGeocode(wangjing) }
    }

    @Test
    fun `builds district and town plus the first business district`() = runTest {
        server.enqueue(jsonResponse(regeo()))

        assertEquals("朝阳区望京街道 · 望京附近", geocoder().reverseGeocode(wangjing))
    }

    @Test
    fun `asks for gcj02 input with latitude first and no poi data`() = runTest {
        server.enqueue(jsonResponse(regeo()))

        geocoder().reverseGeocode(GeoPoint(lat = 39.9909570, lng = 116.480000))

        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/reverse_geocoding/v3/", request.url.encodedPath)
        assertEquals(KEY, request.url.queryParameter("ak"))
        assertEquals("json", request.url.queryParameter("output"))
        assertEquals("39.990957,116.48", request.url.queryParameter("location"))
        assertEquals("gcj02ll", request.url.queryParameter("coordtype"))
        assertEquals("0", request.url.queryParameter("extensions_poi"))
    }

    @Test
    fun `the street stands in for missing business districts and empty fields are skipped`() = runTest {
        server.enqueue(jsonResponse(regeo(business = "")))
        server.enqueue(jsonResponse(regeo(business = "", town = "")))
        server.enqueue(jsonResponse(regeo(business = " , 来广营")))

        assertEquals("朝阳区望京街道 · 阜通东大街6号附近", geocoder().reverseGeocode(wangjing))
        assertEquals("朝阳区 · 阜通东大街6号附近", geocoder().reverseGeocode(wangjing))
        assertEquals("朝阳区望京街道 · 来广营附近", geocoder().reverseGeocode(wangjing))
    }

    @Test
    fun `the formatted address without province and city is the last resort, and is clamped`() = runTest {
        val bare = regeo(district = "", town = "", street = "", streetNumber = "", business = "")
        server.enqueue(jsonResponse(bare))
        server.enqueue(jsonResponse(regeo(formatted = "北京市北京市朝阳区" + "很长的地址".repeat(10), district = "", town = "", street = "", streetNumber = "", business = "")))

        assertEquals("朝阳区阜通东大街6号", geocoder().reverseGeocode(wangjing))
        val long = geocoder().reverseGeocode(wangjing)!!
        assertEquals(30, long.length)
        assertTrue(long.startsWith("朝阳区很长的地址") && long.endsWith("…"))
    }

    @Test
    fun `nothing found is null`() = runTest {
        server.enqueue(jsonResponse("""{"status":0}"""))
        server.enqueue(jsonResponse("""{"status":0,"result":[]}"""))
        server.enqueue(jsonResponse("""{"status":0,"result":{"formatted_address":"","business":"","addressComponent":{}}}"""))
        server.enqueue(jsonResponse("""{"status":1,"msg":"Internal Service Error:无相关结果"}"""))

        repeat(4) { assertNull(geocoder().reverseGeocode(wangjing)) }
    }

    @Test
    fun `maps statuses onto error kinds`() = runTest {
        assertEquals(Kind.AUTH, failureOf(jsonResponse(baiduError(5, "ak不存在或者非法"))).kind)
        assertEquals(Kind.AUTH, failureOf(jsonResponse(baiduError(101))).kind)
        assertEquals(Kind.AUTH, failureOf(jsonResponse(baiduError(210))).kind)
        assertEquals(Kind.QUOTA, failureOf(jsonResponse(baiduError(4))).kind)
        assertEquals(Kind.QUOTA, failureOf(jsonResponse(baiduError(302))).kind)
        assertEquals(Kind.OTHER, failureOf(jsonResponse(baiduError(2, "Parameter Invalid"))).kind)
        assertEquals(Kind.OTHER, failureOf(jsonResponse("""{"status":1,"msg":"Internal Service Error"}""")).kind)
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
