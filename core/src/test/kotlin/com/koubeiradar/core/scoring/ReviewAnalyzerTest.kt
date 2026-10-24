package com.koubeiradar.core.scoring

import com.koubeiradar.core.model.Review
import com.koubeiradar.core.repeated
import com.koubeiradar.core.reviews
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReviewAnalyzerTest {

    private fun analyze(vararg texts: String) = ReviewAnalyzer.analyze(reviews(*texts))

    private fun sentimentOf(vararg texts: String): Double =
        assertNotNull(analyze(*texts).sentimentScore, "sentiment should be computed for ${texts.size} reviews")

    private fun positives(text: String) = ReviewAnalyzer.hitsIn(text).positive
    private fun negatives(text: String) = ReviewAnalyzer.hitsIn(text).negative
    private fun reds(text: String) = ReviewAnalyzer.hitsIn(text).red

    private fun assertNoHits(text: String) {
        val hits = ReviewAnalyzer.hitsIn(text)
        assertTrue(
            hits.positive.isEmpty() && hits.negative.isEmpty() && hits.red.isEmpty(),
            "expected no hits in 「$text」 but found ${hits.positive} / ${hits.negative} / ${hits.red}",
        )
    }

    // ---- lexicon ----

    @Test
    fun lexiconHasTheRequiredSizes() {
        assertTrue(ReviewLexicon.positive.size >= 40, "positive: ${ReviewLexicon.positive.size}")
        assertTrue(ReviewLexicon.negative.size >= 40, "negative: ${ReviewLexicon.negative.size}")
        assertTrue(ReviewLexicon.redFlags.size >= 25, "red flags: ${ReviewLexicon.redFlags.size}")
    }

    @Test
    fun lexiconTermsAreCleanUniqueAndDisjoint() {
        val lists = mapOf(
            "positive" to ReviewLexicon.positive,
            "negative" to ReviewLexicon.negative,
            "redFlags" to ReviewLexicon.redFlags,
            "neutral" to ReviewLexicon.neutral,
        )
        for ((name, terms) in lists) {
            assertEquals(terms.size, terms.toSet().size, "$name contains duplicates")
            for (term in terms) {
                assertTrue(term.isNotEmpty(), "$name contains an empty term")
                assertEquals(term.trim(), term, "$name term 「$term」 has surrounding whitespace")
                assertEquals(term.lowercase(), term, "$name term 「$term」 must be lower case")
            }
        }
        val all = lists.values.flatten()
        assertEquals(all.size, all.toSet().size, "a term appears in more than one list")
    }

    @Test
    fun everyTermIsFoundWhenItStandsAlone() {
        ReviewLexicon.positive.forEach { assertTrue(it in positives(it), "positive 「$it」") }
        ReviewLexicon.negative.forEach { assertTrue(it in negatives(it), "negative 「$it」") }
        ReviewLexicon.redFlags.forEach { assertTrue(it in reds(it), "red flag 「$it」") }
        ReviewLexicon.neutral.forEach { assertNoHits(it) }
    }

    @Test
    fun theTermsNamedInTheDesignAreCovered() {
        val positive = listOf("好吃", "推荐", "回购", "分量足", "新鲜", "干净", "准时", "包装好", "实惠", "惊喜")
        val negative = listOf("难吃", "太咸", "凉了", "送太慢", "少送", "漏送", "洒了", "态度差", "缩水", "踩雷", "避雷", "后悔")
        val red = listOf("拉肚子", "腹泻", "食物中毒", "异物", "头发", "苍蝇", "蟑螂", "变质", "发霉", "过期", "不新鲜", "不干净")
        positive.forEach { assertTrue(it in ReviewLexicon.positive, "missing positive 「$it」") }
        negative.forEach { assertTrue(it in ReviewLexicon.negative, "missing negative 「$it」") }
        red.forEach { assertTrue(it in ReviewLexicon.redFlags, "missing red flag 「$it」") }
        assertTrue(reds("饭菜有点馊味").isNotEmpty())
        assertTrue(reds("菜里有虫子").isNotEmpty())
    }

    @Test
    fun harmlessPhrasesThatContainRiskyCharactersAreNotHits() {
        listOf(
            "咸蛋黄肉粽", "红烧内脏", "脏脏包很好看", "汤里有虫草花", "天冷了想喝热汤", "天气凉了，点个火锅",
            "无味精添加", "没味精", "分量不太大", "销售火爆",
        ).forEach { text ->
            val hits = ReviewAnalyzer.hitsIn(text)
            assertTrue(hits.red.isEmpty() && hits.negative.isEmpty(), "unexpected hits in 「$text」: ${hits.negative} ${hits.red}")
        }
    }

    // ---- matching ----

    @Test
    fun theTrieFindsTheLongestNonOverlappingMatches() {
        val trie = TermTrie(
            listOf(
                "好吃" to TermKind.POSITIVE,
                "不好吃" to TermKind.NEGATIVE,
                "发馊" to TermKind.RED_FLAG,
                "馊了" to TermKind.RED_FLAG,
            ),
        )
        val matches = trie.scan("不好吃，很好吃，发馊了")
        assertEquals(listOf("不好吃", "好吃", "发馊"), matches.map { it.term })
        assertEquals(listOf(TermKind.NEGATIVE, TermKind.POSITIVE, TermKind.RED_FLAG), matches.map { it.kind })
        assertEquals(listOf(0, 5, 8), matches.map { it.start })
        assertEquals(listOf(3, 7, 10), matches.map { it.end })
    }

    @Test
    fun theTrieHandlesEmptyTextAndNoMatches() {
        val trie = TermTrie(listOf("好吃" to TermKind.POSITIVE))
        assertTrue(trie.scan("").isEmpty())
        assertTrue(trie.scan("今天天气不错").isEmpty())
        assertEquals(listOf("好吃"), trie.scan("好吃").map { it.term })
        assertEquals(listOf("好吃", "好吃"), trie.scan("好吃好吃").map { it.term })
    }

    @Test
    fun aTrieRefusesAnEmptyTerm() {
        kotlin.test.assertFailsWith<IllegalArgumentException> { TermTrie(listOf("" to TermKind.POSITIVE)) }
    }

    @Test
    fun matchingIsCaseInsensitiveForLatinTerms() {
        assertEquals(setOf("yyds"), positives("这家店YYDS"))
    }

    @Test
    fun overlappingTermsOfOnePhraseCountOnce() {
        assertEquals(setOf("发馊"), reds("米饭发馊了"))
        assertEquals(setOf("食物中毒"), reds("疑似食物中毒"))
        assertEquals(setOf("不新鲜"), reds("虾不新鲜"))
    }

    // ---- negation ----

    @Test
    fun aNegatedRedFlagIsNotAHit() {
        listOf("不拉肚子", "没拉肚子", "没有异物", "无异味", "未发现头发", "没有发现任何异物", "没吃到头发", "吃完也没有拉肚子")
            .forEach { assertNoHits(it) }
    }

    @Test
    fun aNegatedNegativeTermIsNotAHit() {
        listOf("不难吃", "不太咸", "一点都不太咸", "没凉透", "没有什么难吃", "不觉得难吃", "并不难吃").forEach {
            assertTrue(negatives(it).isEmpty(), "「$it」 should not be negative but was ${negatives(it)}")
        }
    }

    @Test
    fun aNegatedPositiveTermIsIgnoredNotCountedAsNegative() {
        assertNoHits("不准时")
        assertNoHits("没有分量足")
        assertNoHits("并不及时")
        assertNoHits("未必好吃")
    }

    @Test
    fun explicitNegativePhrasesStillCount() {
        assertEquals(setOf("不好吃"), negatives("不好吃"))
        assertEquals(setOf("不太好吃"), negatives("味道不太好吃"))
        assertEquals(setOf("不推荐"), negatives("不推荐"))
        assertEquals(setOf("不新鲜"), reds("不新鲜"))
        assertEquals(setOf("不干净"), reds("盒子不干净"))
        assertTrue(positives("不新鲜").isEmpty())
        assertTrue(positives("不好吃").isEmpty())
    }

    @Test
    fun negationReachesAtMostTwoCharactersBack() {
        assertTrue(negatives("没有一点难吃的地方").isEmpty(), "两个字的间隔仍属于否定范围")
        assertEquals(setOf("难吃"), negatives("没有人觉得难吃"), "三个字之外不再是否定")
    }

    @Test
    fun negationStopsAtPunctuation() {
        assertEquals(setOf("好吃"), positives("没，好吃"))
        assertEquals(setOf("异物"), reds("没有。异物"))
        assertEquals(setOf("难吃"), negatives("不难吃？难吃"))
    }

    @Test
    fun wordsThatMerelyContainANegatorDoNotNegate() {
        assertEquals(setOf("好吃"), positives("特别好吃"))
        assertEquals(setOf("新鲜"), positives("特别新鲜"))
        assertEquals(setOf("太咸"), negatives("味道不错，不过太咸了"))
        assertEquals(setOf("太咸"), negatives("不过太咸了"))
        assertEquals(setOf("好吃"), positives("不仅好吃还便宜"))
        assertEquals(setOf("好吃"), positives("不得不说好吃"))
        assertEquals(setOf("美味"), positives("无比美味"))
        assertEquals(setOf("回购"), positives("未来回购"))
        assertEquals(setOf("好吃"), positives("没想到好吃"))
        assertEquals(setOf("好吃"), positives("比别家好吃"))
    }

    @Test
    fun aNegatorOnlyReachesTheFirstTermAfterIt() {
        assertEquals(setOf("不错", "分量足"), positives("不错分量足"))
        assertEquals(setOf("太咸"), negatives("没有异物太咸"))
        assertTrue(reds("没有异物太咸").isEmpty())
        assertEquals(setOf("不好吃", "太咸"), negatives("不好吃太咸"))
    }

    @Test
    fun textIsMatchedAfterTrimmingAndLimitedToTheFirstTwoThousandCharacters() {
        assertEquals(setOf("难吃"), negatives("  难吃  "))
        assertNoHits("啊".repeat(2_500) + "拉肚子")
        assertEquals(setOf("拉肚子"), reds("啊".repeat(1_900) + "拉肚子"))
    }

    @Test
    fun hugeTextDoesNotFail() {
        val text = "好吃".repeat(500_000)
        assertEquals(setOf("好吃"), positives(text))
    }

    // ---- sentiment score ----

    @Test
    fun fewerThanThreeReviewTextsGiveNoSentimentScore() {
        assertNull(analyze().sentimentScore)
        assertNull(analyze("好吃").sentimentScore)
        assertNull(analyze("好吃", "好吃").sentimentScore)
        assertNotNull(analyze("好吃", "好吃", "好吃").sentimentScore)
    }

    @Test
    fun blankReviewTextsAreIgnoredEntirely() {
        val analysis = ReviewAnalyzer.analyze(
            listOf(Review(""), Review("   "), Review("\n\t", rating = 1.0), Review("好吃"), Review("好吃")),
        )
        assertEquals(2, analysis.reviewCount)
        assertNull(analysis.sentimentScore)
        assertEquals(3, ReviewAnalyzer.analyze(listOf(Review(" "), Review("好吃"), Review("好吃"), Review("好吃"))).reviewCount)
    }

    @Test
    fun neutralReviewsScoreExactlySixty() {
        assertEquals(60.0, sentimentOf("送到了", "还行", "一般"), 1e-9)
    }

    @Test
    fun positiveHeavyBeatsNeutralBeatsNegativeHeavy() {
        val positive = sentimentOf("好吃，分量足", "新鲜，推荐", "准时送达，包装好")
        val neutral = sentimentOf("送到了", "还行", "一般")
        val negative = sentimentOf("难吃，太咸", "送太慢，凉了", "态度差，缩水")
        assertTrue(positive > neutral, "$positive > $neutral")
        assertTrue(neutral > negative, "$neutral > $negative")
        assertTrue(positive > 60.0 && negative < 60.0)
    }

    @Test
    fun everyAdditionalGoodReviewRaisesTheScore() {
        var previous = sentimentOf("送到了", "还行", "一般", "还可以", "没感觉")
        for (good in 1..5) {
            val texts = List(5 - good) { "还行" } + List(good) { "好吃" }
            val score = sentimentOf(*texts.toTypedArray())
            assertTrue(score > previous, "with $good good reviews: $score > $previous")
            previous = score
        }
    }

    @Test
    fun everyAdditionalBadReviewLowersTheScore() {
        var previous = sentimentOf("送到了", "还行", "一般", "还可以", "没感觉")
        for (bad in 1..5) {
            val texts = List(5 - bad) { "还行" } + List(bad) { "难吃" }
            val score = sentimentOf(*texts.toTypedArray())
            assertTrue(score < previous, "with $bad bad reviews: $score < $previous")
            previous = score
        }
    }

    @Test
    fun theScoreFollowsTheDesignFormula() {
        // pos = 2, neg = 1, N = 4: net = (2 - 1.5) / (4 + 5)
        val score = sentimentOf("好吃", "新鲜", "难吃", "送到了")
        assertEquals(60.0 + 40.0 * (2.0 - 1.5) / 9.0, score, 1e-9)
        // red = 1, pos = 2, N = 3: net = (2 - 4) / 8
        assertEquals(50.0, sentimentOf("好吃", "新鲜", "拉肚子"), 1e-9)
        // neg = 1, pos = 2, N = 3: net = (2 - 1.5) / 8
        assertEquals(62.5, sentimentOf("好吃", "新鲜", "难吃"), 1e-9)
    }

    @Test
    fun redFlagsWeighFarMoreThanOrdinaryComplaints() {
        val withRedFlag = sentimentOf("好吃", "新鲜", "吃完拉肚子了")
        val withComplaint = sentimentOf("好吃", "新鲜", "有点难吃")
        val allGood = sentimentOf("好吃", "新鲜", "推荐")
        assertTrue(withRedFlag < 60.0, "one red flag outweighs two compliments: $withRedFlag")
        assertTrue(withComplaint > 60.0, "one complaint does not: $withComplaint")
        assertTrue(allGood > withComplaint && withComplaint > withRedFlag)

        val baseline = sentimentOf("好吃", "新鲜", "送到了")
        assertTrue(baseline - withRedFlag > 2 * (baseline - withComplaint), "a red flag costs more than twice a complaint")
    }

    @Test
    fun theScoreIsClampedToZeroAndOneHundred() {
        assertEquals(0.0, sentimentOf("拉肚子", "头发", "苍蝇", "异物"), 1e-9)
        val gushing = List(50) { "好吃，分量足，新鲜，推荐" }
        assertEquals(100.0, sentimentOf(*gushing.toTypedArray()), 1e-9)
    }

    @Test
    fun aTermCountsOnceNoMatterHowOftenItIsRepeatedInAReview() {
        val repeated = sentimentOf("难吃难吃难吃难吃难吃", "好吃", "好吃")
        val single = sentimentOf("难吃", "好吃", "好吃")
        assertEquals(single, repeated, 1e-9)
    }

    @Test
    fun aKeywordStuffedReviewCannotDominate() {
        val stuffed = "好吃 美味 新鲜 干净 推荐 回购 实惠 惊喜 准时 包装好"
        val capped = sentimentOf(stuffed, "送到了", "还行")
        val threeTerms = sentimentOf("好吃 美味 新鲜", "送到了", "还行")
        assertEquals(threeTerms, capped, 1e-9)
    }

    @Test
    fun reviewStarsCountAsDescribedInTheDesign() {
        fun withStars(stars: Double?) = ReviewAnalyzer.analyze(
            listOf(Review("送到了", rating = stars), Review("还行"), Review("一般")),
        ).sentimentScore

        assertEquals(60.0 - 40.0 * 1.5 / 8.0, assertNotNull(withStars(1.0)), 1e-9)
        assertEquals(60.0 - 40.0 * 1.5 / 8.0, assertNotNull(withStars(2.0)), 1e-9)
        assertEquals(60.0, assertNotNull(withStars(2.5)), 1e-9)
        assertEquals(60.0, assertNotNull(withStars(4.4)), 1e-9)
        assertEquals(60.0 + 40.0 / 8.0, assertNotNull(withStars(4.5)), 1e-9)
        assertEquals(60.0 + 40.0 / 8.0, assertNotNull(withStars(5.0)), 1e-9)
        assertEquals(60.0, assertNotNull(withStars(null)), 1e-9)
    }

    @Test
    fun starsOutsideOneToFiveAreIgnored() {
        for (stars in listOf(0.0, 0.5, 5.5, 9.0, -3.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            val score = ReviewAnalyzer.analyze(
                listOf(Review("送到了", rating = stars), Review("还行"), Review("一般")),
            ).sentimentScore
            assertEquals(60.0, assertNotNull(score), 1e-9, "stars=$stars")
        }
    }

    // ---- hygiene flag ----

    private fun hygieneFlag(red: Int, total: Int, redText: String = "吃完拉肚子了"): Boolean =
        ReviewAnalyzer.analyze(repeated(redText, red) + repeated("还行", total - red)).hygieneFlag

    @Test
    fun hygieneFlagNeedsTwoReviewsOrTenPercent() {
        assertFalse(hygieneFlag(red = 0, total = 20))
        assertFalse(hygieneFlag(red = 1, total = 20), "5%")
        assertFalse(hygieneFlag(red = 1, total = 11), "9.1%")
        assertTrue(hygieneFlag(red = 1, total = 10), "exactly 10%")
        assertTrue(hygieneFlag(red = 1, total = 5))
        assertTrue(hygieneFlag(red = 2, total = 50), "two reviews")
        assertTrue(hygieneFlag(red = 2, total = 200), "two reviews")
        assertTrue(hygieneFlag(red = 5, total = 100), "5%, but five reviews")
    }

    @Test
    fun hygieneFlagIsEvaluatedEvenBelowTheSentimentMinimum() {
        val one = ReviewAnalyzer.analyze(reviews("吃出一根头发"))
        assertTrue(one.hygieneFlag)
        assertNull(one.sentimentScore)
        assertFalse(ReviewAnalyzer.analyze(reviews("好吃")).hygieneFlag)
        assertFalse(ReviewAnalyzer.analyze(emptyList()).hygieneFlag)
    }

    @Test
    fun hygieneFlagCountsReviewsNotTerms() {
        val crowded = ReviewAnalyzer.analyze(listOf(Review("拉肚子，头发，苍蝇，蟑螂")) + repeated("还行", 29))
        assertEquals(1, crowded.redReviewCount)
        assertFalse(crowded.hygieneFlag, "one review out of 30")
    }

    @Test
    fun negatedRedFlagsNeverRaiseTheFlag() {
        val clean = ReviewAnalyzer.analyze(repeated("没有异物，也没拉肚子", 10))
        assertFalse(clean.hygieneFlag)
        assertEquals(0, clean.redReviewCount)
    }

    @Test
    fun everyRedFlagTermRaisesTheFlagWhenTwoReviewsMentionIt() {
        for (term in ReviewLexicon.redFlags) {
            assertTrue(ReviewAnalyzer.analyze(repeated("这次点的菜$term", 2)).hygieneFlag, "「$term」")
        }
    }

    @Test
    fun theMostFrequentRedTermsAreReported() {
        val analysis = ReviewAnalyzer.analyze(
            reviews("拉肚子", "拉肚子，头发", "有头发", "拉肚子", "有苍蝇", "没事", "没事"),
        )
        assertEquals(listOf("拉肚子", "头发", "苍蝇"), analysis.redTerms)
        assertEquals(5, analysis.redReviewCount)
        assertEquals(7, analysis.reviewCount)
    }

    // ---- top positive terms and negative terms ----

    @Test
    fun topPositiveTermsNeedAtLeastTwoMentions() {
        assertTrue(analyze("好吃", "新鲜", "实惠").topPositiveTerms.isEmpty())
        assertEquals(listOf("好吃"), analyze("好吃", "好吃", "新鲜").topPositiveTerms)
    }

    @Test
    fun topPositiveTermsAreOrderedByFrequencyThenByLexiconOrderAndCappedAtThree() {
        val analysis = analyze(
            "新鲜，分量足", "新鲜，分量足", "新鲜，分量足", "分量足",
            "好吃", "好吃", "实惠", "实惠", "准时", "准时",
        )
        assertEquals(listOf("分量足", "新鲜", "好吃"), analysis.topPositiveTerms)
    }

    @Test
    fun aTermMentionedTwiceInOneReviewCountsAsOneMention() {
        assertTrue(analyze("好吃好吃好吃", "送到了", "还行").topPositiveTerms.isEmpty())
    }

    @Test
    fun negativeTermsAndReviewsAreReported() {
        val analysis = analyze("太咸", "太咸，凉了", "凉了", "好吃")
        assertEquals(listOf("太咸", "凉了"), analysis.negativeTerms)
        assertEquals(3, analysis.negativeReviewCount)
    }

    @Test
    fun noReviewsGiveAnEmptyAnalysis() {
        val analysis = ReviewAnalyzer.analyze(emptyList())
        assertEquals(0, analysis.reviewCount)
        assertNull(analysis.sentimentScore)
        assertFalse(analysis.hygieneFlag)
        assertTrue(analysis.topPositiveTerms.isEmpty() && analysis.negativeTerms.isEmpty() && analysis.redTerms.isEmpty())
    }
}
