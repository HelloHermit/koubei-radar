package com.koubeiradar.core.scoring

/** Everything the explanation texts are derived from. [adjustedRating] is the stored, two-decimal value. */
internal class ExplanationInput(
    val facts: ShopFacts,
    val rating: Double,
    val adjustedRating: Double,
    val convenienceScore: Double?,
    val analysis: ReviewAnalysis,
    val penalties: Penalties,
    /** Median price of the candidate set, `null` when too few shops have a price. */
    val medianPrice: Double?,
)

/**
 * Chinese reasons and warnings (DESIGN.md §4.11). Every sentence carries the concrete numbers it is based on, and
 * nothing is stated that the data does not show: an estimated rating count is called an estimate, an unknown one
 * is called unknown.
 */
internal object Explanations {
    const val NO_RATING = "暂无评分，无法判断口碑"
    const val UNKNOWN_RATING_COUNT = "未提供评价数，可信度无法判断"
    const val CLOSED = "当前未营业"

    private const val MAX_ITEMS = 5
    private const val SOLID_ADJUSTED_RATING = 4.3
    private const val STABLE_MONTHLY_SALES = 500
    private const val NOT_NEGATIVE_SENTIMENT = 50.0
    private const val CHEAP_SHARE_OF_MEDIAN = 0.9
    private const val GOOD_CONVENIENCE = 65.0
    private const val MIN_REVIEWS_FOR_CLEAN_RECORD = 5
    private const val MIN_NEGATIVE_REVIEWS = 2
    private const val MIN_NEGATIVE_SHARE = 0.25
    private const val FAR_METERS = 3000
    private const val SLOW_MINUTES = 50

    fun reasons(input: ExplanationInput): List<String> = buildList {
        val facts = input.facts
        val analysis = input.analysis
        val solidRating = input.adjustedRating >= SOLID_ADJUSTED_RATING
        val sentiment = analysis.sentimentScore

        if (solidRating) add(ratingLine(input))

        val sales = facts.monthlySales
        if (solidRating && sales != null && sales >= STABLE_MONTHLY_SALES && !analysis.hygieneFlag &&
            (sentiment == null || sentiment >= NOT_NEGATIVE_SENTIMENT)
        ) {
            add("月售 $sales，口碑稳定")
        }

        if (!analysis.hygieneFlag && analysis.topPositiveTerms.isNotEmpty()) {
            add("评价常提到" + analysis.topPositiveTerms.joinToString("") { "「$it」" })
        }

        val price = facts.avgPrice
        val median = input.medianPrice
        if (price != null && median != null && price <= median * CHEAP_SHARE_OF_MEDIAN) {
            add("人均 ${Fmt.yuan(price)}，低于同类中位数 ${Fmt.yuan(median)}")
        }

        val convenience = input.convenienceScore
        if (convenience != null && convenience >= GOOD_CONVENIENCE) {
            add(deliveryFacts(facts).joinToString("，"))
        }

        if (analysis.reviewCount >= MIN_REVIEWS_FOR_CLEAN_RECORD && analysis.redReviewCount == 0) {
            add("${analysis.reviewCount} 条评价文本中未发现卫生/食安投诉")
        }
    }.take(MAX_ITEMS)

    fun warnings(input: ExplanationInput): List<String> = buildList {
        val facts = input.facts
        val analysis = input.analysis
        val penalties = input.penalties

        if (penalties.hygiene) add(hygieneWarning(analysis))
        if (penalties.perfectButFewRatings) add(perfectButFewWarning(input))
        if (penalties.tooFewRatings) add(tooFewWarning(input))
        if (penalties.closed) add(CLOSED)
        if (facts.count.basis == CountBasis.ASSUMED) add(UNKNOWN_RATING_COUNT)
        if (input.rating < Penalties.LOW_RATING) add("评分仅 ${Fmt.rating(input.rating)}，低于 ${Fmt.rating(Penalties.LOW_RATING)}")

        if (analysis.negativeReviewCount >= MIN_NEGATIVE_REVIEWS &&
            analysis.negativeReviewCount.toDouble() / analysis.reviewCount >= MIN_NEGATIVE_SHARE
        ) {
            add("${analysis.negativeReviewCount}/${analysis.reviewCount} 条评价有负面反馈（${analysis.negativeTerms.joinToString("、")}）")
        }

        val far = facts.distanceMeters?.let { it >= FAR_METERS } == true
        val slow = facts.deliveryMinutes?.let { it >= SLOW_MINUTES } == true
        if (far || slow) {
            val summary = if (far && slow) "又远又慢" else if (far) "距离较远" else "送达较慢"
            add((distanceAndTime(facts) + summary).joinToString("，"))
        }
    }.take(MAX_ITEMS)

    private fun ratingLine(input: ExplanationInput): String {
        val rating = Fmt.rating(input.rating)
        val adjusted = Fmt.twoDecimals(input.adjustedRating)
        val count = input.facts.count
        return when (count.basis) {
            CountBasis.REPORTED -> "评分 $rating（共 ${count.value} 条评价，修正后 $adjusted）"
            CountBasis.ESTIMATED_FROM_SALES ->
                "评分 $rating（月售 ${input.facts.monthlySales ?: 0}，约 ${count.value} 条评价，修正后 $adjusted）"
            CountBasis.ASSUMED -> "评分 $rating（评价数未知，按保守估计修正后 $adjusted）"
        }
    }

    private fun hygieneWarning(analysis: ReviewAnalysis): String {
        val lead = if (analysis.redReviewCount >= 2) "多条评价提到卫生/食安问题" else "有评价提到卫生/食安问题"
        return "$lead（${analysis.redReviewCount}/${analysis.reviewCount} 条：${analysis.redTerms.joinToString("、")}）"
    }

    private fun perfectButFewWarning(input: ExplanationInput): String {
        val rating = Fmt.rating(input.rating)
        val description = if (input.rating >= 5.0) "满分" else "近乎满分"
        val count = input.facts.count
        return when (count.basis) {
            CountBasis.REPORTED -> "评分 $rating（$description）但评价很少（仅 ${count.value} 条），疑似新店/刷评，建议观望"
            CountBasis.ESTIMATED_FROM_SALES ->
                "评分 $rating（$description），月售仅 ${input.facts.monthlySales ?: 0}（约 ${count.value} 条评价），疑似新店/刷评，建议观望"
            CountBasis.ASSUMED -> "评分 $rating（$description）且评价数未知，无法排除新店/刷评，建议观望"
        }
    }

    private fun tooFewWarning(input: ExplanationInput): String {
        val count = input.facts.count
        return when (count.basis) {
            CountBasis.REPORTED -> "评价数过少（仅 ${count.value} 条），口碑可信度低"
            CountBasis.ESTIMATED_FROM_SALES ->
                "评价数过少（月售仅 ${input.facts.monthlySales ?: 0}，约 ${count.value} 条评价），口碑可信度低"
            CountBasis.ASSUMED -> "评价数未知，按 ${count.value} 条估算，口碑可信度低"
        }
    }

    private fun distanceAndTime(facts: ShopFacts): List<String> = buildList {
        facts.distanceMeters?.let { add("距离 ${Fmt.distance(it)}") }
        facts.deliveryMinutes?.let { add("约 $it 分钟送达") }
    }

    private fun deliveryFacts(facts: ShopFacts): List<String> =
        distanceAndTime(facts) + listOfNotNull(facts.deliveryFee?.let { if (it == 0.0) "免配送费" else "配送费 ${Fmt.yuan(it)}" })
}
