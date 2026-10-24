package com.koubeiradar.data.amap

import com.koubeiradar.core.model.GeoPoint
import com.koubeiradar.core.model.Shop
import com.koubeiradar.data.MockServerTest
import com.koubeiradar.data.NearbyQuery
import com.koubeiradar.data.ShopSourceException
import com.koubeiradar.data.ShopSourceException.Kind
import com.koubeiradar.data.causeChain
import com.koubeiradar.data.shanghaiTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import mockwebserver3.SocketEffect
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AmapShopSourceTest : MockServerTest() {
    private val center = GeoPoint(lat = 39.9087, lng = 116.3975)
    private val query = NearbyQuery(center, radiusMeters = 3000, limit = 50)

    private fun source(
        client: OkHttpClient = OkHttpClient(),
        baseUrl: String = server.url("/").toString(),
        clock: () -> Long = { shanghaiTime(12) },
    ) = AmapShopSource(apiKey = KEY, httpClient = client, baseUrl = baseUrl, clock = clock)

    /** Answers every request with the page the lambda builds for its `page_num`. */
    private fun servePages(page: (Int) -> String) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                jsonResponse(page(requireNotNull(request.url.queryParameter("page_num")).toInt()))
        }
    }

    private var reportedRequests = 0

    /** `page_num` of every request received since the last call. */
    private fun requestedPages(): List<String?> {
        val received = server.requestCount
        return List(received - reportedRequests) { server.takeRequest().url.queryParameter("page_num") }
            .also { reportedRequests = received }
    }

    private fun str(text: String) = "\"$text\""

    /** Serves a single POI carrying [business] and returns the shop it maps to. */
    private suspend fun shopWithBusiness(business: String, type: String = "餐饮服务;中餐厅;川菜馆"): Shop {
        server.enqueue(
            jsonResponse(
                """{"status":"1","pois":[{"id":"X","name":"测试店","type":"$type","location":"116.3,39.9","business":$business}]}""",
            ),
        )
        return source().nearby(query).single()
    }

    private suspend fun failureOf(response: MockResponse, client: OkHttpClient = OkHttpClient()): ShopSourceException {
        server.enqueue(response)
        return assertFailsWith { source(client = client).nearby(query) }
    }

    private fun assertKeyFree(error: Throwable) {
        for (e in error.causeChain()) {
            assertFalse(KEY in e.message.orEmpty(), "key leaked into ${e.javaClass.simpleName}: ${e.message}")
            assertFalse(KEY in e.toString())
        }
    }

    // ---- mapping ----

    @Test
    fun `maps every field of a fully populated poi`() = runTest {
        server.enqueue(jsonResponse(SAMPLE_RESPONSE))

        val shop = source().nearby(query).first()

        assertEquals(
            Shop(
                id = "amap:B0FFH1",
                name = "老王川菜馆(中关村店)",
                source = "amap",
                category = "川菜馆",
                rating = 4.6,
                ratingCount = null,
                monthlySales = null,
                avgPrice = 58.0,
                distanceMeters = 356,
                address = "中关村大街1号",
                location = GeoPoint(lat = 39.991957, lng = 116.310003),
                tags = listOf("水煮鱼", "回锅肉", "川菜馆"),
                reviews = emptyList(),
                openNow = true,
                platforms = emptySet(),
                phone = "010-12345678",
            ),
            shop,
        )
    }

    @Test
    fun `tolerates the empty array quirk`() = runTest {
        server.enqueue(jsonResponse(SAMPLE_RESPONSE))

        val shops = source().nearby(query)

        assertEquals(listOf("amap:B0FFH1", "amap:B0FFH2"), shops.map { it.id })
        assertEquals(
            Shop(
                id = "amap:B0FFH2",
                name = "某小吃",
                source = "amap",
                category = "快餐厅",
                distanceMeters = 412,
                location = GeoPoint(lat = 39.99, lng = 116.31),
                tags = listOf("快餐厅"),
            ),
            shops[1],
        )
    }

    @Test
    fun `accepts numbers where strings are expected and the other way round`() = runTest {
        server.enqueue(
            jsonResponse(
                """{"status":1,"infocode":10000,"count":1,"pois":[{"id":7,"name":"数字店","location":"116.3,39.9","distance":356,
                   "business":{"rating":4.5,"cost":58,"tel":13800000000}}]}""",
            ),
        )

        val shop = source().nearby(query).single()

        assertEquals("amap:7", shop.id)
        assertEquals(356, shop.distanceMeters)
        assertEquals(4.5, shop.rating)
        assertEquals(58.0, shop.avgPrice)
        assertEquals("13800000000", shop.phone)
    }

    @Test
    fun `leaves everything amap does not provide unknown`() = runTest {
        server.enqueue(jsonResponse(SAMPLE_RESPONSE))

        val shop = source().nearby(query).first()

        assertNull(shop.ratingCount)
        assertNull(shop.monthlySales)
        assertNull(shop.deliveryMinutes)
        assertNull(shop.deliveryFee)
        assertNull(shop.minOrder)
        assertTrue(shop.platforms.isEmpty())
        assertTrue(shop.reviews.isEmpty())
    }

    @Test
    fun `rating is only accepted within 0 exclusive to 5 inclusive`() = runTest {
        val cases = listOf(
            str("0") to null, str("-1") to null, str("5") to 5.0, str("5.0") to 5.0, str("5.1") to null,
            str("4.55") to 4.55, str("abc") to null, str("NaN") to null, str("") to null, "4.5" to 4.5, "[]" to null,
        )
        for ((raw, expected) in cases) {
            assertEquals(expected, shopWithBusiness("""{"rating":$raw}""").rating, "rating $raw")
        }
    }

    @Test
    fun `cost must be positive`() = runTest {
        val cases = listOf(str("58.00") to 58.0, str("12") to 12.0, str("0") to null, str("-3") to null, str("abc") to null, "[]" to null)
        for ((raw, expected) in cases) {
            assertEquals(expected, shopWithBusiness("""{"cost":$raw}""").avgPrice, "cost $raw")
        }
    }

    @Test
    fun `category is the last non empty type segment`() = runTest {
        assertEquals("川菜馆", shopWithBusiness("{}", type = "餐饮服务;中餐厅;川菜馆").category)
        assertEquals("中餐厅", shopWithBusiness("{}", type = "餐饮服务;中餐厅;").category)
        assertEquals("餐饮服务", shopWithBusiness("{}", type = "餐饮服务").category)
    }

    @Test
    fun `tags are split on all separators de-duplicated and capped at six with the category last`() = runTest {
        val shop = shopWithBusiness("""{"tag":"水煮鱼；回锅肉、水煮鱼,辣子鸡;麻婆豆腐，宫保鸡丁,鱼香肉丝"}""")

        assertEquals(listOf("水煮鱼", "回锅肉", "辣子鸡", "麻婆豆腐", "宫保鸡丁", "鱼香肉丝"), shop.tags)
        assertEquals(listOf("川菜馆", "水煮鱼"), shopWithBusiness("""{"tag":"川菜馆,水煮鱼"}""").tags)
        assertEquals(listOf("水煮鱼", "川菜馆"), shopWithBusiness("""{"tag":" 水煮鱼 ; ;"}""").tags)
    }

    @Test
    fun `phone is the first number`() = runTest {
        assertEquals("010-12345678", shopWithBusiness("""{"tel":"010-12345678;13800000000"}""").phone)
        assertEquals("13800000000", shopWithBusiness("""{"tel":";13800000000"}""").phone)
        assertNull(shopWithBusiness("""{"tel":""}""").phone)
        assertNull(shopWithBusiness("""{"tel":[]}""").phone)
    }

    @Test
    fun `distance accepts integers decimals and numbers and ignores junk`() = runTest {
        for ((raw, expected) in listOf(str("356") to 356, str("356.6") to 357, "356" to 356, str("abc") to null, str("-5") to null, str("") to null)) {
            server.enqueue(jsonResponse("""{"status":"1","pois":[{"id":"X","name":"店","location":"116.3,39.9","distance":$raw}]}"""))
            assertEquals(expected, source().nearby(query).single().distanceMeters, "distance $raw")
        }
    }

    @Test
    fun `open now follows the opening hours and the injected clock`() = runTest {
        server.enqueue(jsonResponse(SAMPLE_RESPONSE))
        server.enqueue(jsonResponse(SAMPLE_RESPONSE))

        val atNoon = source(clock = { shanghaiTime(12) }).nearby(query).first()
        val atNight = source(clock = { shanghaiTime(22, 30) }).nearby(query).first()

        assertEquals(true, atNoon.openNow)
        assertEquals(false, atNight.openNow)
    }

    @Test
    fun `open now is unknown without parseable hours`() = runTest {
        assertNull(shopWithBusiness("{}").openNow)
        assertNull(shopWithBusiness("""{"opentime_today":[]}""").openNow)
        assertNull(shopWithBusiness("""{"opentime_today":"周一至周日 10:00-22:00"}""").openNow)
    }

    @Test
    fun `open now handles a shop open past midnight`() = runTest {
        val page = pageOf(poi("night", extra = """"business":{"opentime_today":"18:00-02:00"}"""))
        repeat(3) { server.enqueue(jsonResponse(page)) }

        assertEquals(true, source(clock = { shanghaiTime(23, 30) }).nearby(query).single().openNow)
        assertEquals(true, source(clock = { shanghaiTime(1, 0) }).nearby(query).single().openNow)
        assertEquals(false, source(clock = { shanghaiTime(3, 0) }).nearby(query).single().openNow)
    }

    // ---- request ----

    @Test
    fun `sends the documented poi search request`() = runTest {
        server.enqueue(jsonResponse(pageOf()))

        source().nearby(NearbyQuery(center, radiusMeters = 2500, keyword = "川菜", limit = 10))

        val request = server.takeRequest()
        val url = request.url
        assertEquals("GET", request.method)
        assertEquals("/v5/place/around", url.encodedPath)
        assertEquals(KEY, url.queryParameter("key"))
        assertEquals("116.3975,39.9087", url.queryParameter("location"))
        assertEquals("2500", url.queryParameter("radius"))
        assertEquals("050000", url.queryParameter("types"))
        assertEquals("distance", url.queryParameter("sortrule"))
        assertEquals("25", url.queryParameter("page_size"))
        assertEquals("1", url.queryParameter("page_num"))
        assertEquals("business", url.queryParameter("show_fields"))
        assertEquals("川菜", url.queryParameter("keywords"))
        assertTrue("keywords=%E5%B7%9D%E8%8F%9C" in url.encodedQuery.orEmpty(), "keyword must be UTF-8 percent-encoded: ${url.encodedQuery}")
    }

    @Test
    fun `omits the keywords parameter for null and blank keywords`() = runTest {
        server.enqueue(jsonResponse(pageOf()))
        server.enqueue(jsonResponse(pageOf()))

        source().nearby(query.copy(keyword = null))
        source().nearby(query.copy(keyword = "  "))

        assertNull(server.takeRequest().url.queryParameter("keywords"))
        assertNull(server.takeRequest().url.queryParameter("keywords"))
    }

    @Test
    fun `works with a base url that has a path prefix`() = runTest {
        server.enqueue(jsonResponse(pageOf()))

        source(baseUrl = server.url("/gateway/amap").toString()).nearby(query)

        assertEquals("/gateway/amap/v5/place/around", server.takeRequest().url.encodedPath)
    }

    @Test
    fun `clamps the radius to 1 through 50000`() = runTest {
        val cases = listOf(0 to "1", -5 to "1", 1 to "1", 3000 to "3000", 50_000 to "50000", 80_000 to "50000")
        for ((requested, sent) in cases) {
            server.enqueue(jsonResponse(pageOf()))
            source().nearby(query.copy(radiusMeters = requested))
            assertEquals(sent, server.takeRequest().url.queryParameter("radius"), "radius $requested")
        }
    }

    @Test
    fun `sends longitude first with at most six decimals and no exponent`() = runTest {
        val cases = listOf(
            GeoPoint(lat = 39.9087654321, lng = 116.3974123456) to "116.397412,39.908765",
            GeoPoint(lat = 40.0, lng = 116.0) to "116.0,40.0",
            GeoPoint(lat = -33.8688, lng = 151.2093) to "151.2093,-33.8688",
            GeoPoint(lat = 0.0000004, lng = -0.0000001) to "0.0,0.0",
            GeoPoint(lat = 0.00001, lng = 0.5) to "0.5,0.00001",
        )
        for ((point, expected) in cases) {
            server.enqueue(jsonResponse(pageOf()))
            source().nearby(query.copy(center = point))
            assertEquals(expected, server.takeRequest().url.queryParameter("location"), "$point")
        }
    }

    @Test
    fun `makes no request for a non positive limit`() = runTest {
        assertEquals(emptyList(), source().nearby(query.copy(limit = 0)))
        assertEquals(emptyList(), source().nearby(query.copy(limit = -3)))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `rejects a non finite center without a request`() = runTest {
        val error = assertFailsWith<ShopSourceException> { source().nearby(query.copy(center = GeoPoint(Double.NaN, 116.0))) }

        assertEquals(Kind.OTHER, error.kind)
        assertEquals(0, server.requestCount)
    }

    // ---- paging, limit, ordering ----

    @Test
    fun `fetches pages until a short page`() = runTest {
        servePages { page ->
            when (page) {
                1 -> fullPage(1)
                2 -> fullPage(2, count = 3)
                else -> error("page $page must not be requested")
            }
        }

        val shops = source().nearby(query.copy(limit = 100))

        assertEquals(28, shops.size)
        assertEquals(listOf("1", "2"), requestedPages())
    }

    @Test
    fun `stops as soon as the limit is reached`() = runTest {
        servePages { page -> fullPage(page) }

        assertEquals(10, source().nearby(query.copy(limit = 10)).size)
        assertEquals(listOf("1"), requestedPages())

        assertEquals(25, source().nearby(query.copy(limit = 25)).size)
        assertEquals(listOf("1"), requestedPages())

        assertEquals(30, source().nearby(query.copy(limit = 30)).size)
        assertEquals(listOf("1", "2"), requestedPages())
    }

    @Test
    fun `never fetches more than eight pages`() = runTest {
        servePages { page -> fullPage(page) }

        val shops = source().nearby(query.copy(limit = 1000))

        assertEquals(200, shops.size)
        assertEquals((1..8).map { it.toString() }, requestedPages())
    }

    @Test
    fun `an empty first page ends the search`() = runTest {
        server.enqueue(jsonResponse(pageOf()))

        assertEquals(emptyList(), source().nearby(query))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `a missing or blank pois field means no results`() = runTest {
        server.enqueue(jsonResponse("""{"status":"1","info":"OK","infocode":"10000","count":"0"}"""))
        server.enqueue(jsonResponse("""{"status":"1","info":"OK","infocode":"10000","count":"0","pois":""}"""))
        server.enqueue(jsonResponse("""{"status":"1","info":"OK","infocode":"10000","count":"0","pois":null}"""))

        repeat(3) { assertEquals(emptyList(), source().nearby(query)) }
    }

    @Test
    fun `de-duplicates pois by id within and across pages`() = runTest {
        servePages { page ->
            when (page) {
                1 -> pageOf(*List(25) { i -> poi("D${i % 24}", distance = 100 + i) }.toTypedArray())
                2 -> pageOf(*List(25) { i -> poi("D${i + 20}", distance = 500 + i) }.toTypedArray())
                else -> pageOf()
            }
        }

        val shops = source().nearby(query.copy(limit = 100))

        assertEquals((0..44).map { "amap:D$it" }, shops.map { it.id })
    }

    @Test
    fun `sorts by distance nearest first and truncates to the limit`() = runTest {
        server.enqueue(
            jsonResponse(
                pageOf(
                    poi("far", distance = 900),
                    poi("none", distance = null),
                    poi("near", distance = 100),
                    poi("mid", distance = 400),
                ),
            ),
        )

        assertEquals(listOf("amap:near", "amap:mid"), source().nearby(query.copy(limit = 2)).map { it.id })

        server.enqueue(
            jsonResponse(pageOf(poi("far", distance = 900), poi("none", distance = null), poi("near", distance = 100), poi("mid", distance = 400))),
        )
        assertEquals(listOf("amap:near", "amap:mid", "amap:far", "amap:none"), source().nearby(query).map { it.id })
    }

    @Test
    fun `skips malformed pois instead of failing`() = runTest {
        server.enqueue(
            jsonResponse(
                """{"status":"1","info":"OK","infocode":"10000","count":"12","pois":[
                  ${poi("ok1", distance = 10)},
                  {"id":"no-location","name":"缺位置"},
                  {"id":"empty-array-location","name":"空位置","location":[]},
                  {"id":"junk-location","name":"乱位置","location":"abc"},
                  {"id":"one-number","name":"单数字","location":"116.3"},
                  {"id":"out-of-range","name":"越界","location":"200.0,100.0"},
                  {"id":"three-numbers","name":"三数字","location":"116.3,39.9,5"},
                  {"id":"no-name","location":"116.3,39.9"},
                  {"id":"empty-name","name":[],"location":"116.3,39.9"},
                  {"name":"没有id","location":"116.3,39.9"},
                  "oops", 7, null, [],
                  ${poi("ok2", distance = 20)}]}""",
            ),
        )

        val shops = source().nearby(query)

        assertEquals(listOf("amap:ok1", "amap:ok2"), shops.map { it.id })
    }

    // ---- errors ----

    @Test
    fun `maps authentication infocodes to AUTH`() = runTest {
        for (code in listOf("10001", "10005", "10006", "10007", "10008", "10009", "10012", "10013", "10026")) {
            assertEquals(Kind.AUTH, failureOf(jsonResponse(errorBody(code, "INVALID_USER_KEY"))).kind, "infocode $code")
        }
    }

    @Test
    fun `maps quota and rate limit infocodes to QUOTA`() = runTest {
        for (code in listOf("10003", "10004", "10010", "10014", "10019", "10020", "10021", "10022", "10044")) {
            assertEquals(Kind.QUOTA, failureOf(jsonResponse(errorBody(code, "DAILY_QUERY_OVER_LIMIT"))).kind, "infocode $code")
        }
    }

    @Test
    fun `maps every other failure status to OTHER`() = runTest {
        for (code in listOf("10002", "10011", "20000", "20003", "30000", "")) {
            assertEquals(Kind.OTHER, failureOf(jsonResponse(errorBody(code))).kind, "infocode '$code'")
        }
        assertEquals(Kind.OTHER, failureOf(jsonResponse("""{"status":"0","info":"UNKNOWN_ERROR"}""")).kind)
    }

    @Test
    fun `reads the infocode when it is a json number`() = runTest {
        assertEquals(Kind.AUTH, failureOf(jsonResponse("""{"status":0,"info":"INVALID_USER_KEY","infocode":10001}""")).kind)
    }

    @Test
    fun `the error message carries amap's reason and infocode`() = runTest {
        val error = failureOf(jsonResponse(errorBody("10001", "INVALID_USER_KEY")))

        assertTrue("INVALID_USER_KEY" in error.message.orEmpty())
        assertTrue("10001" in error.message.orEmpty())
    }

    @Test
    fun `non success http statuses are network errors`() = runTest {
        for (code in listOf(500, 502, 503, 404, 403, 429)) {
            val error = failureOf(jsonResponse("<html>oops</html>", code))
            assertEquals(Kind.NETWORK, error.kind, "http $code")
            assertTrue("$code" in error.message.orEmpty())
        }
    }

    @Test
    fun `a connection dropped mid request is a network error`() = runTest {
        val client = OkHttpClient.Builder().retryOnConnectionFailure(false).build()

        val error = failureOf(MockResponse.Builder().onResponseStart(SocketEffect.CloseSocket()).build(), client)

        assertEquals(Kind.NETWORK, error.kind)
        assertIs<IOException>(error.cause)
    }

    @Test
    fun `a read timeout is a network error`() = runTest {
        val client = OkHttpClient.Builder().readTimeout(200, TimeUnit.MILLISECONDS).build()

        val error = failureOf(MockResponse.Builder().onResponseStart(SocketEffect.Stall).build(), client)

        assertEquals(Kind.NETWORK, error.kind)
        assertIs<IOException>(error.cause)
    }

    @Test
    fun `an unreachable host is a network error`() = runTest {
        val deadUrl = MockWebServer().use { it.start(); it.url("/").toString() }

        val error = assertFailsWith<ShopSourceException> { source(baseUrl = deadUrl).nearby(query) }

        assertEquals(Kind.NETWORK, error.kind)
    }

    @Test
    fun `an invalid base url is reported as OTHER`() = runTest {
        val error = assertFailsWith<ShopSourceException> { source(baseUrl = "not a url").nearby(query) }

        assertEquals(Kind.OTHER, error.kind)
    }

    @Test
    fun `responses that are not understood are parse errors`() = runTest {
        val bodies = listOf(
            "this is not json", "", "[]", "\"text\"", """{"foo":1}""", """{"status":"1","pois":{"a":1}}""",
            """{"status":"1","pois":"nope"}""", """{"status":"1","pois":[{"id":""",
        )
        for (body in bodies) {
            assertEquals(Kind.PARSE, failureOf(jsonResponse(body)).kind, "body '$body'")
        }
    }

    @Test
    fun `a failure on a later page fails the whole search`() = runTest {
        servePages { page -> if (page == 1) fullPage(1) else errorBody("10021", "CUQPS_HAS_EXCEEDED_THE_LIMIT") }

        val error = assertFailsWith<ShopSourceException> { source().nearby(query.copy(limit = 100)) }

        assertEquals(Kind.QUOTA, error.kind)
    }

    // ---- secrecy ----

    @Test
    fun `the key never appears in an exception`() = runTest {
        val dropped = OkHttpClient.Builder().retryOnConnectionFailure(false).build()
        val failures = listOf(
            failureOf(jsonResponse(errorBody("10001", "INVALID_USER_KEY"))),
            failureOf(jsonResponse(errorBody("10003", "DAILY_QUERY_OVER_LIMIT"))),
            failureOf(jsonResponse(errorBody("20000"))),
            failureOf(jsonResponse("oops", 500)),
            failureOf(jsonResponse("not json")),
            failureOf(MockResponse.Builder().onResponseStart(SocketEffect.CloseSocket()).build(), dropped),
            failureOf(jsonResponse(errorBody("10001", "the key $KEY is not valid"))),
            failureOf(jsonResponse("""{"echo":"$KEY","status":""")),
        )

        failures.forEach(::assertKeyFree)
        assertFalse(KEY in failures[6].message.orEmpty(), "info text must be redacted")
    }

    @Test
    fun `a library exception that quotes the url is scrubbed of the key`() = runTest {
        val quoting = OkHttpClient.Builder()
            .addInterceptor { chain -> throw IOException("proxy rejected ${chain.request().url}") }
            .build()

        val error = assertFailsWith<ShopSourceException> { source(client = quoting).nearby(query) }

        assertEquals(Kind.NETWORK, error.kind)
        assertKeyFree(error)
        assertTrue("proxy rejected" in error.cause?.message.orEmpty(), "the cause keeps its diagnostic text")
    }

    // ---- coroutines ----

    @Test
    fun `cancelling the caller cancels the http call`() = runTest {
        val callCancelled = CountDownLatch(1)
        val client = OkHttpClient.Builder()
            .eventListener(object : EventListener() {
                override fun canceled(call: Call) = callCancelled.countDown()
            })
            .build()
        server.enqueue(MockResponse.Builder().onResponseStart(SocketEffect.Stall).build())

        val search = launch(Dispatchers.Default) { source(client = client).nearby(query) }
        assertNotNull(server.takeRequest(10, TimeUnit.SECONDS), "the request never reached the server")
        search.cancelAndJoin()

        assertTrue(callCancelled.await(10, TimeUnit.SECONDS), "the OkHttp call was not cancelled")
    }

    private companion object {
        const val KEY = "SECRET-KEY-0123456789abcdef"
    }
}
