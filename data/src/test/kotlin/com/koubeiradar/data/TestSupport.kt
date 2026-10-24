package com.koubeiradar.data

import com.koubeiradar.core.model.GeoPoint
import com.koubeiradar.core.model.Shop
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Before
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.CopyOnWriteArrayList

/** A hand-cranked clock to inject wherever the code under test takes `() -> Long`. */
class MutableClock(var nowMillis: Long = 0L) : () -> Long {
    override fun invoke(): Long = nowMillis
}

/** Epoch millis of 2026-03-04 at [hour]:[minute] Shanghai time. */
fun shanghaiTime(hour: Int, minute: Int = 0): Long =
    ZonedDateTime.of(2026, 3, 4, hour, minute, 0, 0, ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli()

/** A [ShopSource] whose answer is a lambda, recording every query it receives. */
class FakeShopSource(
    override val id: String = "fake",
    private val answer: suspend (NearbyQuery) -> List<Shop>,
) : ShopSource {
    override val displayName: String = "Fake"
    val queries = CopyOnWriteArrayList<NearbyQuery>()
    val calls: Int get() = queries.size

    constructor(id: String = "fake", shops: List<Shop>) : this(id, { shops })

    override suspend fun nearby(query: NearbyQuery): List<Shop> {
        queries += query
        return answer(query)
    }
}

fun testShop(
    id: String,
    name: String = id,
    source: String = "fake",
    rating: Double? = 4.5,
    location: GeoPoint? = GeoPoint(39.9, 116.4),
) = Shop(id = id, name = name, source = source, rating = rating, location = location)

fun Throwable.causeChain(): List<Throwable> = generateSequence(this) { it.cause }.toList()

/** Starts a loopback [MockWebServer] around every test. */
abstract class MockServerTest {
    protected lateinit var server: MockWebServer

    @Before
    fun startServer() {
        server = MockWebServer().apply { start() }
    }

    @After
    fun stopServer() {
        server.close()
    }

    protected fun jsonResponse(body: String, code: Int = 200): MockResponse =
        MockResponse.Builder()
            .code(code)
            .addHeader("Content-Type", "application/json;charset=UTF-8")
            .body(body)
            .build()
}
