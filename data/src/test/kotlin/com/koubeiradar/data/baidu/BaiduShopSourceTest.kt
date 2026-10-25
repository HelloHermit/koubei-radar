package com.koubeiradar.data.baidu

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

class BaiduShopSourceTest : MockServerTest() {
    private val center = GeoPoint(lat = 39.9087, lng = 116.3975)
    private val query = NearbyQuery(center, radiusMeters = 3000, limit = 50)

    private fun source(
        client: OkHttpClient = OkHttpClient(),
        baseUrl: String = server.url("/").toString(),
        clock: () -> Long = { shanghaiTime(12) },
    ) = BaiduShopSource(apiKey = KEY, httpClient = client, baseUrl = baseUrl, clock = clock)

    /** Answers every request with the page the lambda builds for its (0-based) `page_num`. */
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

    /** Serves a single POI whose `detail_info` has [detail] as its body and returns the shop it maps to. */
    private suspend fun shopWithDetail(detail: String, distance: Int? = 100): Shop {
        server.enqueue(jsonResponse(baiduPageOf(baiduPoi("X", distance = distance, detail = detail))))
        return source().nearby(query).single()
    }

    private suspend fun failureOf(response: MockResponse, client: OkHttpClient = OkHttpClient()): ShopSourceException {
        server.enqueue(response)
        return assertFailsWith { source(client = client).nearby(query) }
    }

    private suspend fun kindOf(status: Any): Kind = failureOf(jsonResponse(baiduError(status))).kind

    private fun assertKeyFree(error: Throwable) {
        for (e in error.causeChain()) {
            assertFalse(KEY in e.message.orEmpty(), "key leaked into ${e.javaClass.simpleName}: ${e.message}")
            assertFalse(KEY in e.toString())
        }
    }

    // ---- mapping ----

    @Test
    fun `maps every field of a fully populated poi including the rating count`() = runTest {
        server.enqueue(jsonResponse(BAIDU_SAMPLE))

        val shop = source().nearby(query).first()

        assertEquals(
            Shop(
                id = "baidu:U1",
                name = "老王川菜馆(中关村店)",
                source = "baidu",
                category = "中餐厅",
                rating = 4.6,
                ratingCount = 1234,
                monthlySales = null,
                avgPrice = 58.0,
                distanceMeters = 356,
                address = "中关村大街1号",
                location = GeoPoint(lat = 39.991957, lng = 116.310003),
                tags = listOf("美食", "中餐厅"),
                reviews = emptyList(),
                openNow = true,
                platforms = emptySet(),
                phone = "(010)12345678",
            ),
            shop,
        )
    }

    @Test
    fun `tolerates empty strings for every optional field`() = runTest {
        server.enqueue(jsonResponse(BAIDU_SAMPLE))

        val shops = source().nearby(query)

        assertEquals(listOf("baidu:U1", "baidu:U2"), shops.map { it.id })
        assertEquals(
            Shop(
                id = "baidu:U2",
                name = "某小吃",
                source = "baidu",
                category = "小吃快餐",
                distanceMeters = 412,
                location = GeoPoint(lat = 39.99, lng = 116.31),
                tags = listOf("美食", "小吃快餐"),
            ),
            shops[1],
        )
    }

    @Test
    fun `a poi without detail_info keeps its basics and has no rating`() = runTest {
        server.enqueue(jsonResponse(baiduPageOf(baiduPoi("N", detail = null))))

        val shop = source().nearby(query).single()

        assertEquals("baidu:N", shop.id)
        assertEquals(100, shop.distanceMeters)
        assertEquals("某路", shop.address)
        assertNull(shop.rating)
        assertNull(shop.ratingCount)
        assertNull(shop.avgPrice)
        assertNull(shop.openNow)
        assertTrue(shop.tags.isEmpty())
        assertNull(shop.category)
    }

    @Test
    fun `accepts numbers where strings are expected and fields next to detail_info`() = runTest {
        val shop = shopWithDetail(""""overall_rating":4.5,"comment_num":321,"price":42,"classified_poi_tag":"美食;火锅","distance":250""", distance = null)

        assertEquals(4.5, shop.rating)
        assertEquals(321, shop.ratingCount)
        assertEquals(42.0, shop.avgPrice)
        assertEquals(250, shop.distanceMeters)

        server.enqueue(
            jsonResponse(
                """{"status":0,"results":[{"name":"平铺","uid":"F","location":{"lat":"39.9","lng":"116.3"},
                    "overall_rating":"4.2","comment_num":"77","price":"30","classified_poi_tag":"美食;面馆","distance":"90"}]}""",
            ),
        )
        val flat = source().nearby(query).single()
        assertEquals(GeoPoint(39.9, 116.3), flat.location)
        assertEquals(4.2, flat.rating)
        assertEquals(77, flat.ratingCount)
        assertEquals(30.0, flat.avgPrice)
        assertEquals("面馆", flat.category)
        assertEquals(90, flat.distanceMeters)
    }

    @Test
    fun `rating is only accepted within 0 exclusive to 5 inclusive`() = runTest {
        val ratings = listOf("0", "0.0", "-1", "5.1", "x", "NaN", "").map { shopWithDetail(""""overall_rating":"$it"""").rating }
        assertEquals(List(7) { null }, ratings)
        assertEquals(5.0, shopWithDetail(""""overall_rating":"5.0"""").rating)
        assertEquals(0.1, shopWithDetail(""""overall_rating":"0.1"""").rating)
    }

    @Test
    fun `rating count accepts zero and counts but not negatives or junk`() = runTest {
        assertEquals(0, shopWithDetail(""""comment_num":"0"""").ratingCount)
        assertEquals(12000, shopWithDetail(""""comment_num":"12000"""").ratingCount)
        assertEquals(15, shopWithDetail(""""comment_num":"15.0"""").ratingCount)
        for (junk in listOf("-3", "many", "", "1e99")) {
            assertNull(shopWithDetail(""""comment_num":"$junk"""").ratingCount, junk)
        }
    }

    @Test
    fun `price must be positive`() = runTest {
        assertEquals(63.0, shopWithDetail(""""price":"63"""").avgPrice)
        for (junk in listOf("0", "-5", "", "free")) assertNull(shopWithDetail(""""price":"$junk"""").avgPrice, junk)
    }

    @Test
    fun `category is the last segment of the poi classification and tags are de-duplicated and capped at six`() = runTest {
        val shop = shopWithDetail(""""classified_poi_tag":"美食;中餐厅;川菜,川菜;;火锅、小吃;烧烤;夜宵;烤鱼"""")

        assertEquals("烤鱼", shop.category)
        assertEquals(listOf("美食", "中餐厅", "川菜", "火锅", "小吃", "烧烤"), shop.tags)
    }

    @Test
    fun `the detail style tag is used when there is no classification`() = runTest {
        assertEquals("面馆", shopWithDetail(""""tag":"美食;面馆"""").category)
        assertEquals("火锅", shopWithDetail(""""classified_poi_tag":"美食;火锅","tag":"其他"""").category)
    }

    @Test
    fun `an english industry type is not a category but a chinese one is`() = runTest {
        assertNull(shopWithDetail(""""type":"cater"""").category)
        assertEquals("餐饮", shopWithDetail(""""type":"餐饮"""").category)
    }

    @Test
    fun `phone is the first number`() = runTest {
        server.enqueue(jsonResponse(baiduPageOf(baiduPoi("T", extra = """"telephone":" ,(010)111, 138""""))))
        assertEquals("(010)111", source().nearby(query).single().phone)
    }

    @Test
    fun `distance accepts integers decimals and strings and ignores junk`() = runTest {
        assertEquals(356, shopWithDetail(""""distance":356""", distance = null).distanceMeters)
        assertEquals(356, shopWithDetail(""""distance":"355.6"""", distance = null).distanceMeters)
        assertNull(shopWithDetail(""""distance":"far"""", distance = null).distanceMeters)
        assertNull(shopWithDetail(""""distance":-4""", distance = null).distanceMeters)
    }

    @Test
    fun `open now follows the shop hours and the injected clock`() = runTest {
        for ((hours, expected) in listOf(
            "10:30-14:00,16:30-22:00" to true,
            "00:00-24:00" to true,
            "13:00-16:00" to false,
            "10:30-11:30,13:00-14:00" to false,
        )) {
            assertEquals(expected, shopWithDetail(""""shop_hours":"$hours"""").openNow, hours)
        }
    }

    @Test
    fun `unparseable shop hours leave open now unknown instead of failing`() = runTest {
        for (hours in listOf("", "周一至周日 10:00-22:00", "全天营业", "10:00", "25:00-26:00", "[]")) {
            assertNull(shopWithDetail(""""shop_hours":"$hours"""").openNow, hours)
        }
    }

    // ---- request ----

    @Test
    fun `sends the documented circle search with gcj02 coordinates`() = runTest {
        server.enqueue(jsonResponse(baiduPageOf()))

        source().nearby(query)

        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/place/v3/around", request.url.encodedPath)
        assertEquals(KEY, request.url.queryParameter("ak"))
        assertEquals("json", request.url.queryParameter("output"))
        assertEquals("美食", request.url.queryParameter("query"))
        assertNull(request.url.queryParameter("type"), "no keyword, no second filter")
        assertNull(request.url.queryParameter("tag"), "v3 has no tag parameter")
        assertEquals("true", request.url.queryParameter("radius_limit"))
        assertEquals("39.9087,116.3975", request.url.queryParameter("location"), "latitude first")
        assertEquals("3000", request.url.queryParameter("radius"))
        assertEquals("2", request.url.queryParameter("coord_type"), "input is GCJ-02")
        assertEquals("gcj02ll", request.url.queryParameter("ret_coordtype"), "output must be GCJ-02 too")
        assertEquals("2", request.url.queryParameter("scope"))
        assertEquals("industry_type:cater|sort_name:distance|sort_rule:1", request.url.queryParameter("filter"))
        assertEquals("10", request.url.queryParameter("page_size"))
        assertEquals("0", request.url.queryParameter("page_num"))
    }

    @Test
    fun `a keyword becomes the type filter and the query stays restaurants`() = runTest {
        repeat(3) { server.enqueue(jsonResponse(baiduPageOf())) }

        source().nearby(query.copy(keyword = " 火锅 "))
        source().nearby(query.copy(keyword = null))
        source().nearby(query.copy(keyword = "  "))

        val queries = List(3) { server.takeRequest().url }
        assertEquals(List(3) { "美食" }, queries.map { it.queryParameter("query") })
        assertEquals(listOf("火锅", null, null), queries.map { it.queryParameter("type") })
    }

    @Test
    fun `works with a base url that has a path prefix`() = runTest {
        server.enqueue(jsonResponse(baiduPageOf(baiduPoi("A"))))

        assertEquals(1, source(baseUrl = server.url("/proxy/baidu").toString()).nearby(query).size)

        assertEquals("/proxy/baidu/place/v3/around", server.takeRequest().url.encodedPath)
    }

    @Test
    fun `clamps the radius to 1 through 50000`() = runTest {
        repeat(3) { server.enqueue(jsonResponse(baiduPageOf())) }

        source().nearby(query.copy(radiusMeters = 0))
        source().nearby(query.copy(radiusMeters = 99_999))
        source().nearby(query.copy(radiusMeters = 500))

        assertEquals(listOf("1", "50000", "500"), List(3) { server.takeRequest().url.queryParameter("radius") })
    }

    @Test
    fun `sends at most six decimals without an exponent`() = runTest {
        server.enqueue(jsonResponse(baiduPageOf()))
        server.enqueue(jsonResponse(baiduPageOf()))

        source().nearby(query.copy(center = GeoPoint(lat = 39.123456789, lng = 116.00000001)))
        source().nearby(query.copy(center = GeoPoint(lat = 0.00000001, lng = -0.0000004)))

        assertEquals("39.123457,116.0", server.takeRequest().url.queryParameter("location"))
        assertEquals("0.0,0.0", server.takeRequest().url.queryParameter("location"))
    }

    @Test
    fun `makes no request for a non positive limit`() = runTest {
        assertEquals(emptyList(), source().nearby(query.copy(limit = 0)))
        assertEquals(emptyList(), source().nearby(query.copy(limit = -1)))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `rejects a non finite center without a request`() = runTest {
        for (bad in listOf(GeoPoint(Double.NaN, 116.0), GeoPoint(39.0, Double.POSITIVE_INFINITY))) {
            assertEquals(Kind.OTHER, assertFailsWith<ShopSourceException> { source().nearby(query.copy(center = bad)) }.kind)
        }
        assertEquals(0, server.requestCount)
    }

    // ---- paging, de-duplication, ordering ----

    @Test
    fun `fetches pages from zero until the reported total is reached`() = runTest {
        servePages { page -> if (page == 0) baiduFullPage(0, total = 15) else baiduFullPage(1, count = 5, total = 15) }

        val shops = source().nearby(query.copy(limit = 100, radiusMeters = 10_000))

        assertEquals(15, shops.size)
        assertEquals(listOf("0", "1"), requestedPages())
    }

    @Test
    fun `without a total a short page ends the search`() = runTest {
        servePages { page -> if (page == 0) baiduFullPage(0, total = null) else baiduFullPage(1, count = 3, total = null) }

        val shops = source().nearby(query.copy(limit = 100, radiusMeters = 10_000))

        assertEquals(13, shops.size)
        assertEquals(listOf("0", "1"), requestedPages())
    }

    @Test
    fun `a short page does not end the search while the total says there is more`() = runTest {
        servePages { page -> if (page == 0) baiduFullPage(0, count = 7, total = 150) else baiduPageOf() }

        val shops = source().nearby(query.copy(limit = 100, radiusMeters = 10_000))

        assertEquals(7, shops.size)
        assertEquals(listOf("0", "1"), requestedPages(), "the empty second page is what ends it")
    }

    @Test
    fun `stops as soon as the limit is reached`() = runTest {
        servePages { page -> baiduFullPage(page) }

        val shops = source().nearby(query.copy(limit = 10, radiusMeters = 10_000))

        assertEquals(10, shops.size)
        assertEquals(listOf("0"), requestedPages())
    }

    @Test
    fun `never fetches more than fifteen pages`() = runTest {
        servePages { page -> baiduFullPage(page, total = 10_000) }

        val shops = source().nearby(query.copy(limit = 1000, radiusMeters = 10_000))

        assertEquals((0..14).map { it.toString() }, requestedPages())
        assertEquals(150, shops.size)
    }

    @Test
    fun `an empty first page or a missing or blank results field means no results`() = runTest {
        for (body in listOf(
            baiduPageOf(), """{"status":0,"message":"ok","total":0}""", """{"status":0,"results":null}""", """{"status":0,"results":""}""",
        )) {
            server.enqueue(jsonResponse(body))
            assertEquals(emptyList(), source().nearby(query), body)
        }
        assertEquals(4, server.requestCount)
    }

    @Test
    fun `de-duplicates pois by uid within and across pages`() = runTest {
        servePages { page ->
            when (page) {
                0 -> baiduPageOf(*Array(8) { baiduPoi("D$it", distance = 10 + it) }, baiduPoi("D0", distance = 10), baiduPoi("D1", distance = 11), total = 150)
                1 -> baiduPageOf(baiduPoi("D1", distance = 11), baiduPoi("E", distance = 500), total = 150)
                else -> baiduPageOf()
            }
        }

        val ids = source().nearby(query).map { it.id }

        assertEquals(9, ids.size)
        assertEquals(ids.distinct(), ids)
        assertTrue("baidu:E" in ids)
    }

    @Test
    fun `sorts by distance nearest first with unknown distances last and truncates to the limit`() = runTest {
        server.enqueue(
            jsonResponse(
                baiduPageOf(
                    baiduPoi("C", distance = 900), baiduPoi("U", distance = null), baiduPoi("A", distance = 100), baiduPoi("B", distance = 400),
                ),
            ),
        )

        assertEquals(listOf("baidu:A", "baidu:B", "baidu:C"), source().nearby(query.copy(limit = 3)).map { it.id })
    }

    @Test
    fun `drops results beyond the requested radius`() = runTest {
        server.enqueue(jsonResponse(baiduPageOf(baiduPoi("IN", distance = 900), baiduPoi("OUT", distance = 1001), baiduPoi("UNKNOWN", distance = null))))

        val ids = source().nearby(query.copy(radiusMeters = 1000)).map { it.id }

        assertEquals(listOf("baidu:IN", "baidu:UNKNOWN"), ids)
    }

    @Test
    fun `skips malformed pois instead of failing`() = runTest {
        server.enqueue(
            jsonResponse(
                """{"status":0,"total":1,"results":[
                    "text", 7, null, [],
                    {"uid":"NOLOC","name":"x"},
                    {"uid":"BADLOC","name":"x","location":{"lat":"abc","lng":"116.3"}},
                    {"uid":"RANGE","name":"x","location":{"lat":95,"lng":116.3}},
                    {"uid":"LOCSTR","name":"x","location":"39.9,116.3"},
                    {"name":"no uid","location":{"lat":39.9,"lng":116.3}},
                    {"uid":"NONAME","location":{"lat":39.9,"lng":116.3}},
                    {"uid":"OK","name":"好店","location":{"lat":39.9,"lng":116.3},"detail_info":[]}
                ]}""",
            ),
        )

        assertEquals(listOf("baidu:OK"), source().nearby(query).map { it.id })
    }

    // ---- failures ----

    @Test
    fun `maps authentication statuses to AUTH`() = runTest {
        for (status in listOf(3, 5, 9, 101, 102, 200, 201, 202, 203, 210, 211, 240, 250, 251, 252, 260, 261, 299)) {
            assertEquals(Kind.AUTH, kindOf(status), "status $status")
        }
    }

    @Test
    fun `maps quota and concurrency statuses to QUOTA`() = runTest {
        for (status in listOf(4, 302, 401, 300, 399)) {
            assertEquals(Kind.QUOTA, kindOf(status), "status $status")
        }
    }

    @Test
    fun `maps every other failure status to OTHER`() = runTest {
        for (status in listOf(1, 2, 8, 400, 402, 1002, 9999, -1)) {
            assertEquals(Kind.OTHER, kindOf(status), "status $status")
        }
    }

    @Test
    fun `reads the status when it is a json string`() = runTest {
        assertEquals(Kind.AUTH, kindOf("240"))
        assertEquals(Kind.QUOTA, kindOf("302"))
    }

    @Test
    fun `the error message carries baidu's reason and status`() = runTest {
        val auth = failureOf(jsonResponse(baiduError(240, "APP 服务被禁用")))
        val quota = failureOf(jsonResponse(baiduError(302, "天配额超限，限制访问")))
        val ip = failureOf(jsonResponse(baiduError(210, "APP IP校验失败")))
        val sn = failureOf(jsonResponse(baiduError(211, "APP SN校验失败")))

        assertTrue("APP 服务被禁用" in auth.message.orEmpty() && "240" in auth.message.orEmpty(), auth.message)
        assertTrue("天配额超限" in quota.message.orEmpty() && "302" in quota.message.orEmpty(), quota.message)
        assertTrue("IP 白名单" in ip.message.orEmpty(), ip.message)
        assertTrue("SN" in sn.message.orEmpty(), sn.message)
    }

    @Test
    fun `non success http statuses are network errors`() = runTest {
        assertEquals(Kind.NETWORK, failureOf(jsonResponse("oops", 500)).kind)
        assertEquals(Kind.NETWORK, failureOf(jsonResponse(baiduError(5), 403)).kind)
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
            "this is not json", "", "[]", "\"text\"", """{"foo":1}""", """{"status":"ok"}""", """{"status":null}""",
            """{"status":0,"results":{"a":1}}""", """{"status":0,"results":"nope"}""", """{"status":0,"results":[{"uid":""",
            "<html><body>502 Bad Gateway</body></html>",
        )
        for (body in bodies) {
            assertEquals(Kind.PARSE, failureOf(jsonResponse(body)).kind, "body '$body'")
        }
    }

    @Test
    fun `a failure on a later page fails the whole search`() = runTest {
        servePages { page -> if (page == 0) baiduFullPage(0) else baiduError(401, "CONCURRENCY") }

        val error = assertFailsWith<ShopSourceException> { source().nearby(query.copy(limit = 100, radiusMeters = 10_000)) }

        assertEquals(Kind.QUOTA, error.kind)
    }

    // ---- secrecy ----

    @Test
    fun `the key never appears in an exception`() = runTest {
        val dropped = OkHttpClient.Builder().retryOnConnectionFailure(false).build()
        val failures = listOf(
            failureOf(jsonResponse(baiduError(240))),
            failureOf(jsonResponse(baiduError(302))),
            failureOf(jsonResponse(baiduError(2))),
            failureOf(jsonResponse("oops", 500)),
            failureOf(jsonResponse("not json")),
            failureOf(jsonResponse("<html>$KEY</html>")),
            failureOf(MockResponse.Builder().onResponseStart(SocketEffect.CloseSocket()).build(), dropped),
            failureOf(jsonResponse(baiduError(5, "the ak $KEY is not valid"))),
            failureOf(jsonResponse("""{"echo":"$KEY","status":""")),
            failureOf(jsonResponse("""{"echo":"$KEY"}""")),
        )

        failures.forEach(::assertKeyFree)
        assertTrue("***" in failures[7].message.orEmpty(), "message text must be redacted: ${failures[7].message}")
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
        const val KEY = "SECRET-AK-0123456789abcdefghijklmn"
    }
}
