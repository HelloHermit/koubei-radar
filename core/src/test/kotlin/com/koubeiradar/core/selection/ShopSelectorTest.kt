package com.koubeiradar.core.selection

import com.koubeiradar.core.PINNED_PRIOR
import com.koubeiradar.core.backdrop
import com.koubeiradar.core.model.Criteria
import com.koubeiradar.core.model.Platform
import com.koubeiradar.core.model.Shop
import com.koubeiradar.core.model.Verdict
import com.koubeiradar.core.numbers
import com.koubeiradar.core.scoring.ReputationScorer
import com.koubeiradar.core.shop
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ShopSelectorTest {

    private val scorer = ReputationScorer(PINNED_PRIOR)
    private val selector = ShopSelector(scorer)

    private fun names(criteria: Criteria, vararg shops: Shop) =
        selector.select(shops.toList(), criteria).map { it.shop.name }

    @Test
    fun defaultCriteriaKeepsEveryShopUpToTheDefaultLimit() {
        val pool = backdrop() + shop("无评分", rating = null)
        assertEquals(pool.size, selector.select(pool).size)
        val many = List(30) { shop("店$it", rating = 4.0 + it * 0.02) }
        assertEquals(20, selector.select(many).size)
    }

    @Test
    fun emptyInputGivesEmptyOutput() {
        assertEquals(emptyList(), selector.select(emptyList()))
        assertEquals(emptyList(), scorer.score(emptyList()))
    }

    @Test
    fun minRatingExcludesShopsWithoutARatingAndIsInclusive() {
        val result = names(
            Criteria(minRating = 4.5),
            shop("刚好", rating = 4.5), shop("略低", rating = 4.4), shop("无评分", rating = null), shop("更高", rating = 4.9),
        )
        assertEquals(setOf("刚好", "更高"), result.toSet())
    }

    @Test
    fun everyOtherLimitLetsUnknownValuesPassAndIsInclusive() {
        val unknown = shop("全未知", ratingCount = null, distanceMeters = null, avgPrice = null, deliveryMinutes = null, deliveryFee = null)
        val boundary = shop("边界", ratingCount = 30, distanceMeters = 3000, avgPrice = 50.0, deliveryMinutes = 40, deliveryFee = 4.0)
        val over = shop("超出", ratingCount = 29, distanceMeters = 3001, avgPrice = 50.5, deliveryMinutes = 41, deliveryFee = 4.5)
        val strict = Criteria(
            minRatingCount = 30, maxDistanceMeters = 3000, minAvgPrice = 50.0, maxAvgPrice = 50.0,
            maxDeliveryMinutes = 40, maxDeliveryFee = 4.0,
        )
        assertEquals(setOf("全未知", "边界"), names(strict, unknown, boundary, over).toSet())
    }

    @Test
    fun eachLimitWorksOnItsOwn() {
        fun single(criteria: Criteria, ok: Shop, bad: Shop) {
            assertTrue(selector.matches(ok, criteria), "ok: $criteria")
            assertFalse(selector.matches(bad, criteria), "bad: $criteria")
        }
        single(Criteria(minRatingCount = 100), shop(ratingCount = 100), shop(ratingCount = 99))
        single(Criteria(maxDistanceMeters = 1000), shop(distanceMeters = 1000), shop(distanceMeters = 1001))
        single(Criteria(minAvgPrice = 20.0), shop(avgPrice = 20.0), shop(avgPrice = 19.9))
        single(Criteria(maxAvgPrice = 30.0), shop(avgPrice = 30.0), shop(avgPrice = 30.1))
        single(Criteria(maxDeliveryMinutes = 30), shop(deliveryMinutes = 30), shop(deliveryMinutes = 31))
        single(Criteria(maxDeliveryFee = 3.0), shop(deliveryFee = 3.0), shop(deliveryFee = 3.5))
    }

    @Test
    fun keywordsMatchNameCategoryAndTagsIgnoringCaseAndExcludeWins() {
        val byName = shop("老王川菜馆")
        val byCategory = shop("甲", category = "湘菜")
        val byTag = shop("乙", tags = listOf("Spicy", "招牌"))
        val none = shop("丙", category = "粥铺")
        val include = Criteria(includeKeywords = setOf("川菜", "湘菜", "spicy"))
        assertEquals(setOf("老王川菜馆", "甲", "乙"), names(include, byName, byCategory, byTag, none).toSet())

        val exclude = Criteria(includeKeywords = setOf("菜"), excludeKeywords = setOf("湘"))
        assertEquals(listOf("老王川菜馆"), names(exclude, byName, byCategory, byTag, none))
    }

    @Test
    fun blankKeywordsAreIgnoredInsteadOfMatchingEverything() {
        assertEquals(2, names(Criteria(includeKeywords = setOf("  ", "")), shop("A"), shop("B")).size)
        assertEquals(2, names(Criteria(excludeKeywords = setOf(" ")), shop("A"), shop("B")).size)
    }

    @Test
    fun platformAndOpenNowRulesTreatUnknownAsPassing() {
        val mt = shop("美团店", platforms = setOf(Platform.MEITUAN))
        val el = shop("饿店", platforms = setOf(Platform.ELEME))
        val both = shop("两者", platforms = setOf(Platform.MEITUAN, Platform.ELEME))
        val unknown = shop("未知平台")
        assertEquals(setOf("美团店", "两者", "未知平台"), names(Criteria(platforms = setOf(Platform.MEITUAN)), mt, el, both, unknown).toSet())

        val open = shop("营业", openNow = true)
        val closed = shop("休息", openNow = false)
        val dunno = shop("不详", openNow = null)
        assertEquals(setOf("营业", "不详"), names(Criteria(onlyOpen = true), open, closed, dunno).toSet())
        assertEquals(3, names(Criteria(onlyOpen = false), open, closed, dunno).size)
    }

    @Test
    fun minScoreIsAppliedAfterScoring() {
        val pool = backdrop() + shop("优", rating = 4.9, ratingCount = 3000, distanceMeters = 500, deliveryMinutes = 22)
        val all = selector.select(pool, Criteria(limit = 100))
        val cut = all[2].score
        val kept = selector.select(pool, Criteria(limit = 100, minScore = cut))
        assertTrue(kept.isNotEmpty() && kept.all { it.score >= cut })
        assertEquals(all.filter { it.score >= cut }.map { it.shop.id }, kept.map { it.shop.id })
        assertEquals(emptyList(), selector.select(pool, Criteria(minScore = 100.1)))
    }

    @Test
    fun resultsAreSortedByScoreThenRatingCountThenName() {
        // rating == the pinned prior (4.4) makes the adjusted rating independent of the count, and both counts
        // saturate the confidence score, so these three tie exactly and only the tie-breakers can order them
        val pool = backdrop() + listOf(
            shop("乙店", rating = 4.4, ratingCount = 200_000),
            shop("甲店", rating = 4.4, ratingCount = 200_000),
            shop("大样本", rating = 4.4, ratingCount = 300_000),
            shop("差", rating = 4.1, ratingCount = 5000),
        )
        val ranked = selector.select(pool, Criteria(limit = 100))
        val scores = ranked.map { it.score }
        assertEquals(scores.sortedDescending(), scores)
        val tied = ranked.filter { it.shop.name in setOf("乙店", "甲店", "大样本") }
        assertEquals(1, tied.map { it.score }.distinct().size, "fixture must produce an exact tie: $scores")
        // rating count desc first, then name ascending by code point (乙 < 甲)
        assertEquals(listOf("大样本", "乙店", "甲店"), tied.map { it.shop.name })
    }

    @Test
    fun shopsWithoutDataSortLastEvenWithDefaultCriteria() {
        val pool = listOf(shop("无评分", rating = null)) + backdrop()
        val ranked = selector.select(pool, Criteria(limit = 100))
        assertEquals(Verdict.INSUFFICIENT_DATA, ranked.last().verdict)
        assertEquals("无评分", ranked.last().shop.name)
        assertEquals(1, ranked.count { it.verdict == Verdict.INSUFFICIENT_DATA })
    }

    @Test
    fun limitTruncatesToTheBestAndNonPositiveLimitSelectsNothing() {
        val pool = backdrop()
        val best = selector.select(pool, Criteria(limit = 100)).take(3).map { it.shop.id }
        assertEquals(best, selector.select(pool, Criteria(limit = 3)).map { it.shop.id })
        assertEquals(emptyList(), selector.select(pool, Criteria(limit = 0)))
        assertEquals(emptyList(), selector.select(pool, Criteria(limit = -5)))
    }

    @Test
    fun scoringStatisticsComeFromTheWholePoolNotTheFilteredSubset() {
        val probe = shop("探针", rating = 4.8, ratingCount = 500, distanceMeters = 900)
        val pool = backdrop() + probe
        val inFullPool = scorer.score(pool).last().score
        val selected = selector.select(pool, Criteria(minRating = 4.7, limit = 100)).first { it.shop.name == "探针" }
        assertEquals(inFullPool, selected.score)
    }

    @Test
    fun scoreKeepsInputOrderAndSize() {
        val pool = listOf(shop("丙", rating = 3.9), shop("甲", rating = 4.9), shop("乙", rating = null), shop("丁", rating = 4.4))
        assertEquals(pool.map { it.id }, scorer.score(pool).map { it.shop.id })
    }

    @Test
    fun garbageInputNeverProducesNaNOrOutOfRangeNumbers() {
        val garbage = listOf(
            shop("评分七", rating = 7.0), shop("NaN", rating = Double.NaN), shop("负数", rating = -1.0, ratingCount = -5, monthlySales = -9),
            shop("零距离", distanceMeters = 0, deliveryMinutes = 0, deliveryFee = 0.0), shop("巨大", ratingCount = Int.MAX_VALUE, monthlySales = Int.MAX_VALUE),
            shop("负价", avgPrice = -3.0, distanceMeters = -100, deliveryMinutes = -1, deliveryFee = Double.POSITIVE_INFINITY),
            shop("", rating = 4.5),
        )
        val criteria = Criteria(maxDistanceMeters = 0, minScore = Double.NaN, minRating = Double.NaN, maxAvgPrice = Double.NaN)
        val result = selector.select(garbage + backdrop(), criteria.copy(limit = 100))
        assertTrue(result.isNotEmpty())
        result.forEach { scored ->
            scored.numbers().forEach { assertTrue(it.isFinite() && it in 0.0..100.0, "$it in ${scored.shop.name}") }
        }
    }

    @Test
    fun selectionIsDeterministic() {
        val pool = backdrop() + shop("乙", rating = 4.6) + shop("甲", rating = 4.6)
        val criteria = Criteria(limit = 100, minRating = 4.2)
        assertEquals(selector.select(pool, criteria), selector.select(pool.toList(), criteria))
    }
}
