package com.koubeiradar.core.reviewer

import com.koubeiradar.core.PINNED_PRIOR
import com.koubeiradar.core.backdrop
import com.koubeiradar.core.model.Criteria
import com.koubeiradar.core.model.Platform
import com.koubeiradar.core.model.Review
import com.koubeiradar.core.model.ScoredShop
import com.koubeiradar.core.model.ScoringConfig
import com.koubeiradar.core.model.Shop
import com.koubeiradar.core.model.Verdict
import com.koubeiradar.core.numbers
import com.koubeiradar.core.scoring.ReputationScorer
import com.koubeiradar.core.scoring.ReviewAnalyzer
import com.koubeiradar.core.selection.ShopSelector
import com.koubeiradar.core.shop
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReviewerCoreTest {

    private val texts = listOf(
        "好吃，分量足，下次回购", "难吃，太咸了", "吃完拉肚子", "吃到头发了", "没拉肚子，不错", "不难吃", "   ", "", "一般般",
        "不新鲜，有异物", "送太慢，凉了", "外卖很快，包装好", "yyds", "不是不新鲜", "没有异物", "x".repeat(5000),
    )

    private fun randomShop(r: Random, i: Int): Shop {
        fun <T> maybe(p: Double, v: () -> T): T? = if (r.nextDouble() < p) v() else null
        val ratings = listOf(0.0, -1.0, 7.0, Double.NaN, Double.POSITIVE_INFINITY, 5.0, 4.9, 4.0, 3.9, 1.0, 4.5)
        return Shop(
            id = "f:${r.nextInt(0, 60)}", // duplicate ids on purpose
            name = listOf("", "A", "店", "店店", "b$i", "老王").random(r),
            source = "fuzz",
            rating = maybe(0.85) { if (r.nextBoolean()) ratings.random(r) else 3.0 + r.nextDouble() * 2 },
            ratingCount = maybe(0.6) { listOf(-5, 0, 1, 9, 10, 29, 30, 500, Int.MAX_VALUE, r.nextInt(0, 4000)).random(r) },
            monthlySales = maybe(0.5) { listOf(-1, 0, 100, Int.MAX_VALUE, r.nextInt(0, 9000)).random(r) },
            avgPrice = maybe(0.6) { listOf(-3.0, 0.0, Double.NaN, Double.POSITIVE_INFINITY, 18.0, r.nextDouble() * 200).random(r) },
            distanceMeters = maybe(0.7) { listOf(-1, 0, Int.MAX_VALUE, r.nextInt(0, 9000)).random(r) },
            deliveryMinutes = maybe(0.5) { listOf(-1, 0, 20, 400, Int.MAX_VALUE).random(r) },
            deliveryFee = maybe(0.5) { listOf(-1.0, 0.0, Double.NaN, 3.5, 1e9).random(r) },
            openNow = maybe(0.3) { r.nextBoolean() },
            platforms = if (r.nextBoolean()) emptySet() else setOf(Platform.entries.random(r)),
            reviews = if (r.nextBoolean()) emptyList() else List(r.nextInt(0, 14)) {
                Review(texts.random(r), rating = maybe(0.5) { listOf(0.0, 1.0, 2.0, 4.5, 5.0, 9.0, Double.NaN).random(r) })
            },
        )
    }

    @Test
    fun fuzzInvariants() {
        val seedRandom = Random(20260101)
        repeat(300) { round ->
            val r = Random(seedRandom.nextLong())
            val n = listOf(0, 1, 2, 5, 40, 200).random(r)
            val shops = List(n) { randomShop(r, it) }
            val config = listOf(ScoringConfig(), ScoringConfig.VALUE_FIRST, ScoringConfig.FAST_FIRST,
                ScoringConfig(weightRating = Double.NaN, priorStrength = -1.0, volumeHalfLifeCount = 0.0, assumedRatingCountWhenUnknown = -3),
                ScoringConfig(priorMeanRating = 9.0, salesToRatingCountRatio = 1e9)).random(r)
            val criteria = Criteria(
                maxDistanceMeters = listOf(null, 0, -5, 1, 3000, Int.MAX_VALUE).random(r),
                minScore = listOf(null, 0.0, 50.0, Double.NaN, 200.0).random(r),
                limit = listOf(-1, 0, 1, 7, 20, Int.MAX_VALUE).random(r),
                minRating = listOf(null, 4.5, Double.NaN).random(r),
            )
            val scorer = ReputationScorer(config)
            val a = scorer.score(shops, criteria)
            val b = scorer.score(shops, criteria)
            assertEquals(a, b, "determinism round $round")
            assertEquals(shops.size, a.size)
            a.forEachIndexed { i, s ->
                assertTrue(s.shop === shops[i], "order")
                s.numbers().forEach { v -> assertTrue(v.isFinite(), "finite $v in $s") }
                assertTrue(s.score in 0.0..100.0)
                listOfNotNull(s.breakdown.ratingScore, s.breakdown.confidenceScore, s.breakdown.sentimentScore,
                    s.breakdown.valueScore, s.breakdown.convenienceScore).forEach { assertTrue(it in 0.0..100.0, "$it") }
                if (s.verdict == Verdict.INSUFFICIENT_DATA) assertEquals(0.0, s.score)
            }
            val sel = ShopSelector(scorer)
            val out = sel.select(shops, criteria)
            assertEquals(out, sel.select(shops, criteria))
            assertTrue(out.size <= maxOf(criteria.limit, 0))
            val ms = criteria.minScore?.takeUnless { it.isNaN() }
            out.forEach { if (ms != null) assertTrue(it.score >= ms) }
            val firstInsufficient = out.indexOfFirst { it.verdict == Verdict.INSUFFICIENT_DATA }
            if (firstInsufficient >= 0) assertTrue(out.drop(firstInsufficient).all { it.verdict == Verdict.INSUFFICIENT_DATA })
            out.zipWithNext().filter { (x, y) -> x.verdict != Verdict.INSUFFICIENT_DATA && y.verdict != Verdict.INSUFFICIENT_DATA }
                .forEach { (x, y) ->
                    assertTrue(x.score >= y.score)
                    if (x.score == y.score) {
                        val cx = x.breakdown.effectiveRatingCount ?: -1; val cy = y.breakdown.effectiveRatingCount ?: -1
                        assertTrue(cx >= cy); if (cx == cy) assertTrue(x.shop.name <= y.shop.name)
                    }
                }
        }
    }

    @Test
    fun monotoneInCountAndDistanceAndRating() {
        val scorer = ReputationScorer(PINNED_PRIOR)
        for (rating in listOf(4.5, 4.6, 4.95, 5.0)) {
            var prev = -1.0
            for (count in listOf(0, 1, 5, 9, 10, 20, 29, 30, 60, 150, 400, 1000, 5000, 100000)) {
                val s = scorer.score(backdrop() + shop(name = "p", rating = rating, ratingCount = count, distanceMeters = 1000, deliveryMinutes = 30)).last().score
                assertTrue(s >= prev - 1e-9, "rating=$rating count=$count score=$s prev=$prev")
                prev = s
            }
        }
        var prev = 101.0
        for (d in listOf(0, 100, 500, 1000, 2500, 4999, 5000, 9000)) {
            val s = scorer.score(backdrop() + shop(name = "p", distanceMeters = d)).last().score
            assertTrue(s <= prev + 1e-9, "distance $d"); prev = s
        }
        prev = -1.0
        for (rating in listOf(3.0, 3.5, 3.9, 4.0, 4.3, 4.6, 4.8, 4.85)) {
            val s = scorer.score(backdrop() + shop(name = "p", rating = rating, ratingCount = 500)).last().score
            assertTrue(s >= prev - 1e-9, "rating $rating"); prev = s
        }
    }

    private data class NegCase(val text: String, val red: Boolean? = null, val neg: Boolean? = null, val pos: Boolean? = null)

    @Test
    fun negationBattery() {
        val cases = listOf(
            NegCase("不难吃", neg = false, pos = false),
            NegCase("没有异物", red = false), NegCase("没拉肚子", red = false), NegCase("不是不新鲜", red = false),
            NegCase("吃到头发", red = true), NegCase("吃到了头发", red = true), NegCase("没吃到头发", red = false),
            NegCase("不太新鲜", red = true), NegCase("不太干净", red = true), NegCase("不是很新鲜", red = true),
            NegCase("不是特别好吃", neg = true), NegCase("不算好吃", neg = true), NegCase("没那么好吃", neg = true),
            NegCase("不好吃", neg = true), NegCase("味道一般，不推荐", neg = true, pos = false),
            NegCase("没有不错", pos = false), NegCase("一点都不新鲜", red = true),
            NegCase("有苍蝇", red = true), NegCase("没有苍蝇", red = false), NegCase("别点，拉肚子", red = true),
            NegCase("没怎么好吃", neg = true), NegCase("不咸，刚好", neg = false),
        )
        val bad = cases.filter { c ->
            val h = ReviewAnalyzer.hitsIn(c.text)
            (c.red != null && c.red != h.red.isNotEmpty()) || (c.neg != null && c.neg != h.negative.isNotEmpty()) ||
                (c.pos != null && c.pos != h.positive.isNotEmpty())
        }.map { c -> val h = ReviewAnalyzer.hitsIn(c.text); "${c.text} -> pos=${h.positive} neg=${h.negative} red=${h.red}" }
        println("NEGATION MISMATCHES:\n" + bad.joinToString("\n"))
        assertTrue(bad.isEmpty(), bad.joinToString("; "))
    }

    @Test
    fun hygieneThresholds() {
        fun flag(red: Int, total: Int) = ReviewAnalyzer.analyze(
            List(red) { Review("吃到头发") } + List(total - red) { Review("好吃") },
        ).hygieneFlag
        assertTrue(flag(1, 9)); assertTrue(flag(1, 10)); assertFalse(flag(1, 11)); assertTrue(flag(2, 100)); assertFalse(flag(0, 5))
    }

    @Test
    fun spamAndPerformance() {
        val spam = List(2000) { Review("好吃，推荐，回购，惊喜") }
        val s = ReputationScorer().score(backdrop() + shop(name = "spam", reviews = spam)).last()
        println("spam sentiment=${s.breakdown.sentimentScore} score=${s.score}")
        val big = List(2000) { i -> shop(name = "s$i", reviews = List(30) { Review(texts.random(Random(i * 31 + it))) }) }
        val t0 = System.nanoTime()
        val out = ShopSelector().select(big, Criteria(limit = 5000))
        println("5000-ish shops took ${(System.nanoTime() - t0) / 1_000_000} ms, n=${out.size}")
        val t1 = System.nanoTime()
        ReputationScorer().score(listOf(shop(reviews = List(20000) { Review("好吃不难吃没有异物".repeat(200)) })))
        println("20000 long reviews took ${(System.nanoTime() - t1) / 1_000_000} ms")
    }

    @Test
    fun selectionEdges() {
        val shops = listOf(shop(name = "a", rating = 4.3), shop(name = "b", rating = 4.3), shop(name = "c", rating = 4.299999))
        val sel = ShopSelector()
        assertEquals(setOf("a", "b"), sel.select(shops, Criteria(minRating = 4.3)).map { it.shop.name }.toSet())
        assertEquals(listOf("a", "b", "c").size, sel.select(shops, Criteria(limit = Int.MAX_VALUE)).size)
        assertTrue(sel.select(shops, Criteria(limit = -1)).isEmpty())
        // ties: same score, same count -> name asc
        assertEquals(listOf("a", "b"), sel.select(shops.take(2).reversed(), Criteria()).map { it.shop.name })
    }

    @Test
    fun explanationsMatchNumbers() {
        val s = ReputationScorer(PINNED_PRIOR).score(backdrop() + shop(name = "p", rating = 4.8, ratingCount = 3200, monthlySales = 4200,
            distanceMeters = 600, deliveryMinutes = 28)).last()
        println(s.reasons); println(s.warnings); println(s.breakdown)
        val bay = (3200 * 4.8 + 30 * 4.4) / 3230
        assertTrue(s.reasons.any { "修正后 ${"%.2f".format(bay)}" in it }, s.reasons.toString())
    }

    @Test
    fun couponExpiryIsNotAFoodSafetyIssue() {
        val reviews = reviews("红包过期了没用上，菜还行", "优惠券过期了，不过很好吃", "好吃", "推荐")
        val a = ReviewAnalyzer.analyze(reviews)
        println("coupon: red=${a.redReviewCount} hygieneFlag=${a.hygieneFlag}")
        assertFalse(a.hygieneFlag, "coupon/red-packet expiry must not flag hygiene")
    }

    private fun reviews(vararg t: String) = t.map { Review(it) }

    @Test
    fun couponExpiryShopVerdict() {
        val shopWithCoupons = shop(name = "p", rating = 4.7, ratingCount = 800, distanceMeters = 800, deliveryMinutes = 28,
            reviews = reviews("红包过期了没用上，菜还行", "优惠券过期了，不过很好吃", "好吃", "推荐"))
        val r = ReputationScorer(PINNED_PRIOR).score(backdrop() + shopWithCoupons).last()
        println("coupon shop: ${r.verdict} ${r.score} warnings=${r.warnings}")
        assertTrue(r.verdict != Verdict.AVOID, "verdict ${r.verdict} warnings ${r.warnings}")
        val real = ReviewAnalyzer.analyze(reviews("吃到过期的面包", "面包过期了", "好吃"))
        assertTrue(real.hygieneFlag)
    }
}
