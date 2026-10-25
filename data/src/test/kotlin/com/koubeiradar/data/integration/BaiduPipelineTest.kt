package com.koubeiradar.data.integration

import com.koubeiradar.core.model.Criteria
import com.koubeiradar.core.model.GeoPoint
import com.koubeiradar.core.model.ScoredShop
import com.koubeiradar.core.model.Verdict
import com.koubeiradar.core.selection.ShopSelector
import com.koubeiradar.data.MockServerTest
import com.koubeiradar.data.NearbyQuery
import com.koubeiradar.data.amap.AmapShopSource
import com.koubeiradar.data.amap.SAMPLE_RESPONSE
import com.koubeiradar.data.baidu.BaiduShopSource
import com.koubeiradar.data.baidu.baiduPageOf
import com.koubeiradar.data.baidu.baiduPoi
import com.koubeiradar.data.repo.ShopRepository
import com.koubeiradar.data.shanghaiTime
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Baidu (and Amap) mock replies -> source -> repository -> the REAL selector: only the source that reports a rating count
 * lets the scorer compute a confidence (docs/DESIGN.md §3.4).
 */
class BaiduPipelineTest : MockServerTest() {
    private val query = NearbyQuery(GeoPoint(39.9087, 116.3975), radiusMeters = 3000, limit = 50)
    private val baseUrl get() = server.url("/").toString()

    private fun List<ScoredShop>.byId(id: String): ScoredShop =
        assertNotNull(firstOrNull { it.shop.id == id }, "$id missing from ${map { it.shop.id }}")

    @Test
    fun `a baidu shop with a rating count gets a confidence score and a count-free one does not`() = runTest {
        server.enqueue(
            jsonResponse(
                baiduPageOf(
                    baiduPoi("BIG", detail = """"overall_rating":"4.7","comment_num":"3200","price":"45","shop_hours":"10:00-22:00""""),
                    baiduPoi("NOCOUNT", detail = """"overall_rating":"4.8","comment_num":"","price":"40""""),
                    baiduPoi("FEW", detail = """"overall_rating":"5.0","comment_num":"6","price":"40""""),
                ),
            ),
        )
        val repository = ShopRepository()
        repository.refreshNearby(BaiduShopSource("AK", baseUrl = baseUrl, clock = { shanghaiTime(12) }), query)

        val ranked = ShopSelector().select(repository.all(), Criteria(limit = 100))

        val big = ranked.byId("baidu:BIG")
        val confidence = assertNotNull(big.breakdown.confidenceScore, "3200 ratings must give a confidence")
        assertTrue(confidence > 90.0, confidence.toString())
        assertEquals(Verdict.RECOMMENDED, big.verdict)

        val noCount = ranked.byId("baidu:NOCOUNT")
        assertNull(noCount.breakdown.confidenceScore)
        assertTrue(noCount.warnings.any { "评价数" in it }, noCount.warnings.toString())

        val few = ranked.byId("baidu:FEW")
        assertNotNull(few.breakdown.confidenceScore)
        assertTrue(few.warnings.any { "评价" in it }, few.warnings.toString())
        assertTrue(ranked.indexOf(big) < ranked.indexOf(few), "6 perfect ratings must rank below 3200 good ones")
    }

    @Test
    fun `an amap shop has no rating count and so no confidence`() = runTest {
        server.enqueue(jsonResponse(SAMPLE_RESPONSE))
        val repository = ShopRepository()
        repository.refreshNearby(AmapShopSource("KEY", baseUrl = baseUrl, clock = { shanghaiTime(12) }), query)

        val ranked = ShopSelector().select(repository.all(), Criteria(limit = 100))

        val amap = ranked.byId("amap:B0FFH1")
        assertNull(amap.shop.ratingCount)
        assertNull(amap.breakdown.confidenceScore)
        assertTrue(amap.warnings.any { "评价数" in it }, amap.warnings.toString())
    }
}
