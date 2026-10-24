package com.koubeiradar.data.reviewer

import com.koubeiradar.core.model.Criteria
import com.koubeiradar.core.model.GeoPoint
import com.koubeiradar.core.model.Shop
import com.koubeiradar.core.model.Verdict
import com.koubeiradar.core.selection.ShopSelector
import com.koubeiradar.data.FakeShopSource
import com.koubeiradar.data.MockServerTest
import com.koubeiradar.data.NearbyQuery
import com.koubeiradar.data.ShopSourceException
import com.koubeiradar.data.amap.AmapGeocoder
import com.koubeiradar.data.amap.AmapShopSource
import com.koubeiradar.data.amap.OpeningHours
import com.koubeiradar.data.amap.SAMPLE_RESPONSE
import com.koubeiradar.data.amap.errorBody
import com.koubeiradar.data.amap.fullPage
import com.koubeiradar.data.amap.pageOf
import com.koubeiradar.data.amap.poi
import com.koubeiradar.data.causeChain
import com.koubeiradar.data.demo.DemoShopSource
import com.koubeiradar.data.repo.ShopMerger
import com.koubeiradar.data.repo.ShopRepository
import com.koubeiradar.data.shanghaiTime
import com.koubeiradar.data.testShop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReviewerDataTest : MockServerTest() {
    private val KEY = "SECRETKEY0123456789"
    private val query = NearbyQuery(GeoPoint(39.9, 116.4), radiusMeters = 3000, limit = 50)
    private fun source(client: OkHttpClient = OkHttpClient(), key: String = KEY) =
        AmapShopSource(key, client, server.url("/").toString(), clock = { shanghaiTime(12) })

    // ---------- fuzz the Amap JSON ----------
    private fun junk(r: Random): JsonElement = when (r.nextInt(12)) {
        0 -> JsonNull; 1 -> JsonArray(emptyList()); 2 -> JsonObject(emptyMap()); 3 -> JsonPrimitive(r.nextInt(-5, 99999))
        4 -> JsonPrimitive(""); 5 -> JsonPrimitive("  "); 6 -> JsonPrimitive("NaN"); 7 -> JsonPrimitive(true)
        8 -> JsonPrimitive("1e999"); 9 -> JsonArray(listOf(JsonPrimitive("a"), JsonPrimitive("b"))); 10 -> JsonPrimitive(-1.5)
        else -> JsonPrimitive("116.3,39.9,5")
    }

    @Test
    fun fuzzPois() = runBlocking {
        val base = SAMPLE_RESPONSE
        val r = Random(7)
        val keys = listOf("id", "name", "type", "address", "location", "distance", "business")
        val bkeys = listOf("rating", "cost", "tag", "tel", "opentime_today", "business_area")
        var ok = 0; var failed = 0
        repeat(400) {
            val pois = JsonArray(List(r.nextInt(0, 6)) {
                val o = mutableMapOf<String, JsonElement>(
                    "id" to JsonPrimitive("P${r.nextInt(5)}"), "name" to JsonPrimitive("店${r.nextInt(5)}"),
                    "type" to JsonPrimitive("餐饮服务;中餐厅;;"), "location" to JsonPrimitive("116.3,39.9"), "distance" to JsonPrimitive("${r.nextInt(3000)}"),
                    "business" to buildJsonObject { put("rating", "4.5"); put("cost", "30"); put("tag", "a;b,c"); put("tel", "1;2"); put("opentime_today", "09:00-21:00") },
                )
                repeat(r.nextInt(0, 4)) { o[keys.random(r)] = junk(r) }
                if (r.nextInt(3) == 0) o["business"] = buildJsonObject { repeat(3) { put(bkeys.random(r), junk(r)) } }
                JsonObject(o)
            } + if (r.nextInt(5) == 0) listOf(junk(r)) else emptyList())
            val env = buildJsonObject {
                put("status", if (r.nextInt(10) == 0) junk(r) else JsonPrimitive("1")); put("info", "OK"); put("infocode", "10000")
                put("pois", if (r.nextInt(8) == 0) junk(r) else pois)
            }
            server.enqueue(jsonResponse(env.toString()))
            try {
                val out = source().nearby(query.copy(limit = r.nextInt(0, 60)))
                ok++
                assertEquals(out.map { it.id }.distinct().size, out.size)
                out.forEach { s ->
                    assertTrue(s.id.startsWith("amap:") && s.name.isNotBlank() && s.name == s.name.trim())
                    s.rating?.let { assertTrue(it > 0 && it <= 5 && it.isFinite()) }
                    s.avgPrice?.let { assertTrue(it > 0 && it.isFinite()) }
                    s.distanceMeters?.let { assertTrue(it >= 0) }
                    assertTrue(s.tags.size <= 6 && s.tags.distinct() == s.tags && s.tags.all { it.isNotBlank() })
                    assertTrue(s.location != null && s.location!!.lat in -90.0..90.0)
                }
                assertEquals(out.sortedBy { it.distanceMeters ?: Int.MAX_VALUE }.map { it.id }, out.map { it.id })
            } catch (e: ShopSourceException) { failed++ }
        }
        println("fuzzPois ok=$ok failedCleanly=$failed")
    }

    // ---------- pagination ----------
    private fun recordPages(limit: Int, pageCount: Int = 20, dup: Boolean = false): List<Int> {
        val pages = mutableListOf<Int>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val p = request.url.queryParameter("page_num")!!.toInt()
                pages += p
                if (request.url.queryParameter("page_size") != "25") error("page_size")
                return if (p > pageCount) jsonResponse(pageOf()) else jsonResponse(if (dup) fullPage(1) else fullPage(p))
            }
        }
        runBlocking { source().nearby(query.copy(limit = limit)) }
        return pages
    }

    @Test
    fun pagination() {
        assertEquals(listOf(1), recordPages(25))
        assertEquals(listOf(1, 2), recordPages(26))
        assertEquals(listOf(1, 2), recordPages(30))
        assertEquals((1..8).toList(), recordPages(1000))
        assertEquals((1..8).toList(), recordPages(200))
        val dupPages = recordPages(60, dup = true) // every page identical -> only 25 unique ever
        println("dup pages requested: $dupPages")
        assertTrue(dupPages.size <= 8)
    }

    @Test
    fun urlBuilding() = runBlocking {
        val seen = mutableListOf<okhttp3.HttpUrl>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse { seen += request.url; return jsonResponse(pageOf()) }
        }
        val kw = "a&b#c d+e%20川菜=x"
        source().nearby(NearbyQuery(GeoPoint(-0.00000001, 116.1234567891), radiusMeters = 99999999, keyword = "  $kw  ", limit = 5))
        val u = seen.single()
        assertEquals(kw, u.queryParameter("keywords"))
        assertEquals("50000", u.queryParameter("radius"))
        println("location=" + u.queryParameter("location"))
        assertEquals(KEY, u.queryParameter("key"))
        AmapGeocoder(KEY, OkHttpClient(), server.url("/").toString()).geocode("北京市 朝阳区&x#", city = " 北京 ")
        assertEquals("北京市 朝阳区&x#", seen.last().queryParameter("address"))
        assertEquals("北京", seen.last().queryParameter("city"))
    }

    // ---------- key redaction / error mapping ----------
    private fun everything(e: Throwable): String =
        (e.causeChain().flatMap { listOf(it.message.orEmpty(), it.toString(), it.stackTraceToString()) }).joinToString("\n")

    @Test
    fun keyRedaction() = runBlocking {
        val bodies = listOf(
            errorBody("10001", "INVALID_USER_KEY key=$KEY"),
            errorBody("10003", "DAILY_QUERY_OVER_LIMIT $KEY"),
            errorBody("99999", "UNKNOWN $KEY"),
            "garbage $KEY not json",
            """{"status":"$KEY"}""",
            """{"status":"1","pois":"$KEY"}""",
            """{"status":"1","pois":{"k":"$KEY"}}""",
            """[$KEY]""",
        )
        for (b in bodies) {
            server.enqueue(jsonResponse(b))
            val e = runCatching { source().nearby(query) }.exceptionOrNull()
            println("body=${b.take(40)} -> ${(e as? ShopSourceException)?.kind} ${e?.message}")
            if (e != null) assertTrue(KEY !in everything(e), "LEAK for body $b: ${everything(e).take(400)}")
        }
        for (code in listOf(401, 403, 429, 500, 503)) {
            server.enqueue(jsonResponse("body $KEY", code))
            val e = assertFailsWith<ShopSourceException> { source().nearby(query) }
            assertEquals(ShopSourceException.Kind.NETWORK, e.kind); assertTrue(KEY !in everything(e))
        }
        // server hangs up / sends a redirect that carries the key
        server.enqueue(MockResponse.Builder().code(302).addHeader("Location", "http://127.0.0.1:1/x?key=$KEY").build())
        val e2 = runCatching { source().nearby(query) }.exceptionOrNull()
        println("redirect -> ${(e2 as? ShopSourceException)?.kind} ${e2?.message}")
        if (e2 != null) assertTrue(KEY !in everything(e2), everything(e2).take(500))
        // dead port
        val dead = server.url("/").toString().also { server.close() }
        val e3 = assertFailsWith<ShopSourceException> { AmapShopSource(KEY, OkHttpClient(), dead).nearby(query) }
        assertEquals(ShopSourceException.Kind.NETWORK, e3.kind); assertTrue(KEY !in everything(e3))
        val e4 = assertFailsWith<ShopSourceException> { AmapGeocoder(KEY, OkHttpClient(), dead).geocode("x") }
        assertTrue(KEY !in everything(e4))
    }

    @Test
    fun timeoutAndCancellation() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) =
                MockResponse.Builder().body(pageOf()).headersDelay(5, TimeUnit.SECONDS).build()
        }
        val fast = OkHttpClient.Builder().readTimeout(300, TimeUnit.MILLISECONDS).build()
        val t0 = System.nanoTime()
        val e = assertFailsWith<ShopSourceException> { source(fast).nearby(query) }
        assertEquals(ShopSourceException.Kind.NETWORK, e.kind)
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 3000)
        val job = launch(Dispatchers.Default) { source().nearby(query) }
        delay(300)
        val t1 = System.nanoTime()
        job.cancelAndJoin()
        assertTrue((System.nanoTime() - t1) / 1_000_000 < 1500)
    }

    @Test
    fun geocoderOdd() = runBlocking {
        val g = AmapGeocoder(KEY, OkHttpClient(), server.url("/").toString())
        val cases = listOf(
            """{"status":"1","count":"0","geocodes":[]}""" to null,
            """{"status":"1","geocodes":""}""" to null,
            """{"status":"1","geocodes":{"location":"1,2"}}""" to null,
            """{"status":"1","geocodes":[{"location":[]},{"location":"116.4,39.9"}]}""" to GeoPoint(39.9, 116.4),
            """{"status":"1","geocodes":[{"location":"116.4,39.9"}]}""" to GeoPoint(39.9, 116.4),
            """{"status":"1","geocodes":[{"location":"200,95"}]}""" to null,
            """{"status":"1","geocodes":[{"location":" 116.4 , 39.9 "}]}""" to GeoPoint(39.9, 116.4),
        )
        for ((body, exp) in cases) { server.enqueue(jsonResponse(body)); assertEquals(exp, g.geocode("x"), body) }
    }

    // ---------- opening hours ----------
    @Test
    fun openingHours() {
        fun open(t: String, h: Int, m: Int = 0) = OpeningHours.isOpenAt(t, shanghaiTime(h, m))
        assertEquals(true, open("00:00-24:00", 23, 59)); assertEquals(true, open("00:00-24:00", 0, 0))
        assertEquals(true, open("22:00-02:00", 1, 59)); assertEquals(false, open("22:00-02:00", 2, 0)); assertEquals(true, open("22:00-02:00", 23))
        assertEquals(false, open("09:00-14:00 17:00-21:00", 15)); assertEquals(true, open("09:00-14:00 17:00-21:00", 17))
        assertEquals(true, open("09:00-14:00,17:00-21:00", 20)); assertEquals(true, open("9:00~21:00", 9))
        assertNull(open("24:00-02:00", 1)); assertNull(open("全天营业", 1)); assertNull(open("", 1)); assertNull(open("09:00-25:00", 1))
        assertNull(open("09:00-21:00 周一休息", 10)); assertNull(open("09:61-21:00", 10)); assertNull(open("00:00-00:00", 10))
        // UTC midnight == 08:00 Shanghai
        assertEquals(true, OpeningHours.isOpenAt("08:00-09:00", 0L))
        println("00:00-24:00 at 24:00? ${open("08:00-24:00", 23, 59)}")
    }

    // ---------- demo data through the REAL pipeline ----------
    private fun hav(a: GeoPoint, b: GeoPoint): Double {
        val r = 6371000.0
        val p1 = Math.toRadians(a.lat); val p2 = Math.toRadians(b.lat)
        val dp = p2 - p1; val dl = Math.toRadians(b.lng - a.lng)
        val x = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return 2 * r * asin(sqrt(x))
    }

    @Test
    fun demoPipelineTable() = runBlocking {
        for (center in listOf(GeoPoint(39.9087, 116.3975), GeoPoint(31.2304, 121.4737), GeoPoint(22.5431, 114.0579), GeoPoint(-33.9, 151.2))) {
            val q = NearbyQuery(center, 5000, limit = 200)
            val shops = DemoShopSource().nearby(q)
            assertEquals(shops, DemoShopSource().nearby(q))
            assertTrue(shops.size >= 30, "size ${shops.size}")
            shops.forEach {
                val d = hav(center, it.location!!)
                assertTrue(Math.abs(d - it.distanceMeters!!) <= 2.0, "${it.id} d=$d vs ${it.distanceMeters}")
                assertTrue(it.distanceMeters!! <= 5000)
            }
            assertEquals(shops.sortedBy { it.distanceMeters }.map { it.id }, shops.map { it.id })
            assertEquals(shops.size, shops.map { it.id }.distinct().size)
            assertEquals(shops.map { it.name }.size, shops.map { it.name }.distinct().size, "duplicate demo names")
        }
        val shops = DemoShopSource().nearby(NearbyQuery(GeoPoint(39.9087, 116.3975), 5000, limit = 200))
        val ranked = ShopSelector().select(shops, Criteria(limit = 200))
        println("rank id score verdict rating n sales dist/min | warnings")
        ranked.forEachIndexed { i, s ->
            if (i < 14 || s.shop.id.count { it == ':' } == 1 && s.shop.id.removePrefix("demo:").all { it.isLetter() })
                println("%2d %-18s %5.1f %-17s r=%s n=%s ms=%s d=%s t=%s | %s".format(i, s.shop.id, s.score, s.verdict, s.shop.rating,
                    s.shop.ratingCount, s.shop.monthlySales, s.shop.distanceMeters, s.shop.deliveryMinutes, s.warnings.joinToString(" / ").take(70)))
        }
        val firstNonRecommended = ranked.indexOfFirst { it.verdict != Verdict.RECOMMENDED }
        val lastRecommended = ranked.indexOfLast { it.verdict == Verdict.RECOMMENDED }
        val avoidAboveRec = ranked.withIndex().filter { it.value.verdict == Verdict.AVOID && it.index < lastRecommended }
        println("AVOID shops ranked above some RECOMMENDED shop: ${avoidAboveRec.map { it.value.shop.id + "@" + it.index + "/" + it.value.score }}")
        println("verdict histogram: " + ranked.groupingBy { it.verdict }.eachCount())
        val gen = shops.filter { it.id.removePrefix("demo:").any { c -> c.isDigit() } }
        println("generated n=${gen.size} rating range ${gen.mapNotNull { it.rating }.minOrNull()}..${gen.mapNotNull { it.rating }.maxOrNull()} " +
            "count range ${gen.mapNotNull { it.ratingCount }.minOrNull()}..${gen.mapNotNull { it.ratingCount }.maxOrNull()} " +
            "price ${gen.mapNotNull { it.avgPrice }.minOrNull()}..${gen.mapNotNull { it.avgPrice }.maxOrNull()} " +
            "deliv ${gen.mapNotNull { it.deliveryMinutes }.minOrNull()}..${gen.mapNotNull { it.deliveryMinutes }.maxOrNull()} " +
            "withReviews=${gen.count { it.reviews.size in 6..15 }}/${gen.size} anyReviews=${gen.count { it.reviews.isNotEmpty() }} platforms=${gen.groupingBy { it.platforms }.eachCount()}")
        assertTrue(firstNonRecommended >= 0)
    }

    // ---------- repository ----------
    @Test
    fun repositoryStress() = runBlocking(Dispatchers.Default) {
        val repo = ShopRepository(cacheTtlMillis = 3)
        val sources = (0 until 3).map { s ->
            FakeShopSource("s$s") { q ->
                delay(Random.nextLong(0, 4))
                if (Random.nextInt(8) == 0) throw ShopSourceException("boom", ShopSourceException.Kind.NETWORK)
                List(Random.nextInt(0, 25)) { testShop("n$s:${it}", name = "店${it % 9}(分店${q.limit % 3})") }
            }
        }
        val errors = java.util.concurrent.ConcurrentLinkedQueue<Throwable>()
        (0 until 48).map { w ->
            async(Dispatchers.Default) {
                val r = Random(w)
                repeat(250) { i ->
                    try {
                        when (r.nextInt(10)) {
                            0, 1, 2 -> try { repo.refreshNearby(sources.random(r), query.copy(limit = r.nextInt(1, 6)), force = r.nextBoolean()) } catch (_: ShopSourceException) {}
                            3, 4 -> repo.addImported(List(r.nextInt(0, 4)) { testShop("imp${r.nextInt(30)}", name = "店${r.nextInt(9)}") })
                            5 -> repo.clearImported()
                            6 -> if (r.nextInt(5) == 0) repo.clearAll()
                            else -> {
                                val a = repo.all()
                                if (a.map { it.id }.distinct().size != a.size) error("dup ids in all()")
                                a.firstOrNull()?.let { repo.find(it.id) }
                                val v = repo.shops.value
                                if (v.map { it.id }.distinct().size != v.size) error("dup ids in shops")
                            }
                        }
                    } catch (t: Throwable) { errors += t }
                }
            }
        }.awaitAll()
        assertTrue(errors.isEmpty(), errors.firstOrNull()?.stackTraceToString().orEmpty())
        assertEquals(repo.all(), repo.shops.value)
    }

    @Test
    fun mergerCases() {
        fun merged(nearbyNames: List<String>, importedNames: List<String>): List<String> {
            val n = nearbyNames.mapIndexed { i, nm -> testShop("n$i", nm, "amap").copy(address = "addr$i") }
            val m = importedNames.mapIndexed { i, nm -> testShop("i$i", nm, "import").copy(ratingCount = 100 + i) }
            return ShopMerger.merge(n, m).map { it.id + (it.address?.let { a -> "+$a" } ?: "") }
        }
        println("fullwidth: " + merged(listOf("老王川菜馆(中关村店)"), listOf("老王川菜馆（中关村店）")))
        println("ambiguous: " + merged(listOf("老王川菜馆(中关村店)", "老王川菜馆(五道口店)"), listOf("老王川菜馆")))
        println("unique: " + merged(listOf("老王川菜馆(中关村店)"), listOf("老王川菜馆")))
        println("two imports same name: " + merged(listOf("老王川菜馆"), listOf("老王川菜馆", "老王 川菜馆")))
        println("two imports same name no nearby: " + merged(emptyList(), listOf("老王川菜馆", "老王 川菜馆!")))
        println("diff branch: " + merged(listOf("老王川菜馆(中关村店)"), listOf("老王川菜馆(五道口店)")))
        println("bracket types: " + merged(listOf("老王川菜馆【中关村店】"), listOf("老王川菜馆[中关村店]")))
        println("case/latin: " + merged(listOf("KFC 肯德基(望京店)"), listOf("kfc肯德基（望京店）")))
        println("suffix text in middle: " + merged(listOf("(老)王川菜馆"), listOf("王川菜馆")))
    }
}
