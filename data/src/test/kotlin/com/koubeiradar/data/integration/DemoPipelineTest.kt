package com.koubeiradar.data.integration

import com.koubeiradar.core.model.Criteria
import com.koubeiradar.core.model.GeoPoint
import com.koubeiradar.core.model.ScoredShop
import com.koubeiradar.core.model.ScoringConfig
import com.koubeiradar.core.model.Verdict
import com.koubeiradar.core.scoring.ReputationScorer
import com.koubeiradar.core.selection.ShopSelector
import com.koubeiradar.data.NearbyQuery
import com.koubeiradar.data.demo.DemoShopIds
import com.koubeiradar.data.demo.DemoShopSource
import com.koubeiradar.data.repo.ShopRepository
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Demo source -> repository -> the REAL selector: the showcase shops must behave the way the demo data promises
 * (docs/DESIGN.md §3.2). Module-level tests use fakes; this one checks that the real parts fit together.
 */
class DemoPipelineTest {

    private val center = GeoPoint(39.9087, 116.3975)
    private val query = NearbyQuery(center, radiusMeters = 5000, limit = 100)

    private suspend fun load(): ShopRepository =
        ShopRepository().also { it.refreshNearby(DemoShopSource(), query) }

    private fun List<ScoredShop>.byId(id: String): ScoredShop =
        assertNotNull(firstOrNull { it.shop.id == id }, "$id missing from ${map { it.shop.id }}")

    @Test
    fun showcaseShopsBehaveAsPromised() = runTest {
        val ranked = ShopSelector().select(load().all(), Criteria(limit = 100))

        assertEquals(Verdict.RECOMMENDED, ranked.byId(DemoShopIds.LAOZIHAO).verdict)

        val newShop = ranked.byId(DemoShopIds.NEW_SHOP_PERFECT)
        assertNotEquals(Verdict.RECOMMENDED, newShop.verdict)
        assertTrue(newShop.warnings.isNotEmpty(), "perfect rating from 6 ratings must warn")

        val hygiene = ranked.byId(DemoShopIds.HYGIENE_COMPLAINTS)
        assertEquals(Verdict.AVOID, hygiene.verdict)
        assertTrue(hygiene.warnings.any { "卫生" in it || "食安" in it }, hygiene.warnings.toString())

        assertTrue(ranked.byId(DemoShopIds.RATING_ONLY).warnings.any { "评价数" in it })
        assertTrue(ranked.byId(DemoShopIds.RESTING).warnings.any { "未营业" in it })
        assertNotEquals(Verdict.RECOMMENDED, ranked.byId(DemoShopIds.FEW_HIGH).verdict)

        val farSlow = ranked.byId(DemoShopIds.FAR_AND_SLOW)
        val bargain = ranked.byId(DemoShopIds.BARGAIN)
        assertTrue(assertNotNull(farSlow.breakdown.convenienceScore) < assertNotNull(bargain.breakdown.convenienceScore))
        assertTrue(assertNotNull(bargain.breakdown.valueScore) >= 70.0, "bargain value ${bargain.breakdown.valueScore}")
        assertTrue(ranked.indexOf(bargain) < ranked.indexOf(farSlow))

        val unrated = ranked.byId(DemoShopIds.UNRATED)
        assertEquals(Verdict.INSUFFICIENT_DATA, unrated.verdict)
        assertEquals(unrated.shop.id, ranked.last().shop.id)
    }

    @Test
    fun suspiciousShopsRankBelowTheTrustworthyOne() = runTest {
        val ranked = ShopSelector().select(load().all(), Criteria(limit = 100))
        val trusted = ranked.indexOf(ranked.byId(DemoShopIds.LAOZIHAO))
        listOf(DemoShopIds.NEW_SHOP_PERFECT, DemoShopIds.HYGIENE_COMPLAINTS, DemoShopIds.FEW_HIGH).forEach {
            assertTrue(trusted < ranked.indexOf(ranked.byId(it)), "$it should rank below the 3200-rating shop")
        }
    }

    @Test
    fun typicalCriteriaFilterOutThinEvidence() = runTest {
        val criteria = Criteria(minRating = 4.5, minRatingCount = 100, limit = 100)
        val ids = ShopSelector().select(load().all(), criteria).map { it.shop.id }
        assertTrue(DemoShopIds.LAOZIHAO in ids)
        listOf(DemoShopIds.NEW_SHOP_PERFECT, DemoShopIds.FEW_HIGH, DemoShopIds.UNRATED).forEach {
            assertTrue(it !in ids, "$it must be filtered out")
        }
    }

    @Test
    fun speedPresetPushesTheFarAndSlowShopDown() = runTest {
        val shops = load().all()
        fun position(config: ScoringConfig): Int {
            val ranked = ShopSelector(ReputationScorer(config)).select(shops, Criteria(limit = 100))
            return ranked.indexOfFirst { it.shop.id == DemoShopIds.FAR_AND_SLOW }
        }
        assertTrue(position(ScoringConfig.FAST_FIRST) > position(ScoringConfig.REPUTATION_FIRST))
    }

    @Test
    fun rankingIsStableAcrossRepositoriesAndRuns() = runTest {
        val first = ShopSelector().select(load().all(), Criteria(limit = 100)).map { it.shop.id to it.score }
        val second = ShopSelector().select(load().all(), Criteria(limit = 100)).map { it.shop.id to it.score }
        assertEquals(first, second)
    }
}
