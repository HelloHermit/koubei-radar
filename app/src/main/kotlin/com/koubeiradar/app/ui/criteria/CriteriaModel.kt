package com.koubeiradar.app.ui.criteria

import com.koubeiradar.core.model.Criteria
import com.koubeiradar.core.model.Platform
import kotlin.math.roundToInt

/** One active restriction of a [Criteria], in a form the UI turns into a chip or a sentence. */
sealed interface CriteriaChip {
    data class MinRating(val rating: Double) : CriteriaChip
    data class MinRatingCount(val count: Int) : CriteriaChip
    data class MaxDistance(val meters: Int) : CriteriaChip
    data class PriceRange(val min: Double?, val max: Double?) : CriteriaChip
    data class MaxDeliveryMinutes(val minutes: Int) : CriteriaChip
    data class MaxDeliveryFee(val yuan: Double) : CriteriaChip
    data class IncludeKeywords(val keywords: List<String>) : CriteriaChip
    data class ExcludeKeywords(val keywords: List<String>) : CriteriaChip
    data class Platforms(val platforms: List<Platform>) : CriteriaChip
    data object OnlyOpen : CriteriaChip
    data class MinScore(val score: Double) : CriteriaChip
}

/** The restrictions that are actually set, in display order. `limit` is a display setting, not a restriction. */
fun Criteria.chips(): List<CriteriaChip> = buildList {
    minRating?.let { add(CriteriaChip.MinRating(it)) }
    minRatingCount?.let { add(CriteriaChip.MinRatingCount(it)) }
    maxDistanceMeters?.let { add(CriteriaChip.MaxDistance(it)) }
    if (minAvgPrice != null || maxAvgPrice != null) add(CriteriaChip.PriceRange(minAvgPrice, maxAvgPrice))
    maxDeliveryMinutes?.let { add(CriteriaChip.MaxDeliveryMinutes(it)) }
    maxDeliveryFee?.let { add(CriteriaChip.MaxDeliveryFee(it)) }
    if (includeKeywords.isNotEmpty()) add(CriteriaChip.IncludeKeywords(includeKeywords.toList()))
    if (excludeKeywords.isNotEmpty()) add(CriteriaChip.ExcludeKeywords(excludeKeywords.toList()))
    if (platforms.isNotEmpty()) add(CriteriaChip.Platforms(platforms.sortedBy { it.ordinal }))
    if (onlyOpen) add(CriteriaChip.OnlyOpen)
    minScore?.let { add(CriteriaChip.MinScore(it)) }
}

fun Criteria.isUnrestricted(): Boolean = chips().isEmpty()

/**
 * One notch looser. Numeric thresholds step towards "no limit" and drop out once they stop being meaningful;
 * flags (open only, platforms, minimum score, delivery fee, minimum price) are released at once. Keywords express what
 * the user is after, so they are kept until nothing else is left to relax. Repeated calls end in [isUnrestricted].
 */
fun Criteria.relaxed(): Criteria {
    val looser = copy(
        minRating = minRating?.let { ((it - RATING_STEP) * 10).roundToInt() / 10.0 }?.takeIf { it >= MIN_MEANINGFUL_RATING },
        minRatingCount = minRatingCount?.let { it / 2 }?.takeIf { it >= MIN_MEANINGFUL_COUNT },
        maxDistanceMeters = maxDistanceMeters?.let { it + DISTANCE_STEP_METERS }?.takeIf { it <= MAX_DISTANCE_METERS },
        minAvgPrice = null,
        maxAvgPrice = maxAvgPrice?.let { (it * PRICE_FACTOR).roundToInt().toDouble() }?.takeIf { it <= MAX_MEANINGFUL_PRICE },
        maxDeliveryMinutes = maxDeliveryMinutes?.let { it + MINUTES_STEP }?.takeIf { it <= MAX_MEANINGFUL_MINUTES },
        maxDeliveryFee = null,
        platforms = emptySet(),
        onlyOpen = false,
        minScore = null,
    )
    return if (looser != this) looser else copy(includeKeywords = emptySet(), excludeKeywords = emptySet())
}

private const val RATING_STEP = 0.3
private const val MIN_MEANINGFUL_RATING = 3.9
private const val MIN_MEANINGFUL_COUNT = 20
private const val DISTANCE_STEP_METERS = 1000
private const val MAX_DISTANCE_METERS = 5000
private const val PRICE_FACTOR = 1.5
private const val MAX_MEANINGFUL_PRICE = 200.0
private const val MINUTES_STEP = 15
private const val MAX_MEANINGFUL_MINUTES = 75

/**
 * The editable form of a [Criteria]: numbers are whole units and the keyword sets are the raw text the user types,
 * so half-typed input survives recomposition and rotation.
 */
data class CriteriaDraft(
    val minRating: Double? = null,
    val minRatingCount: Int? = null,
    val maxDistanceMeters: Int? = null,
    /** Whole yuan; `null` = no lower bound. */
    val priceMin: Int? = null,
    /** Whole yuan; `null` = no upper bound. */
    val priceMax: Int? = null,
    val maxDeliveryMinutes: Int? = null,
    val includeKeywords: String = "",
    val excludeKeywords: String = "",
    val platforms: Set<Platform> = emptySet(),
    val onlyOpen: Boolean = false,
) {
    /** Applies the edits to [base]; settings the form does not offer (limit, minimum score, delivery fee) are kept. */
    fun applyTo(base: Criteria): Criteria = base.copy(
        minRating = minRating,
        minRatingCount = minRatingCount,
        maxDistanceMeters = maxDistanceMeters,
        minAvgPrice = priceMin?.toDouble(),
        maxAvgPrice = priceMax?.toDouble(),
        maxDeliveryMinutes = maxDeliveryMinutes,
        includeKeywords = parseKeywords(includeKeywords),
        excludeKeywords = parseKeywords(excludeKeywords),
        platforms = platforms,
        onlyOpen = onlyOpen,
    )

    companion object {
        const val MAX_KEYWORDS = 10
        const val MAX_KEYWORD_LENGTH = 20

        fun of(criteria: Criteria) = CriteriaDraft(
            minRating = criteria.minRating,
            minRatingCount = criteria.minRatingCount,
            maxDistanceMeters = criteria.maxDistanceMeters,
            priceMin = criteria.minAvgPrice?.roundToInt(),
            priceMax = criteria.maxAvgPrice?.roundToInt(),
            maxDeliveryMinutes = criteria.maxDeliveryMinutes,
            includeKeywords = criteria.includeKeywords.joinToString("、"),
            excludeKeywords = criteria.excludeKeywords.joinToString("、"),
            platforms = criteria.platforms,
            onlyOpen = criteria.onlyOpen,
        )
    }
}

private val KEYWORD_SEPARATORS = Regex("[,，、;；\\s]+")

/** Splits free text on commas, semicolons, enumeration marks and whitespace; trims, de-duplicates, and bounds count and length. */
fun parseKeywords(text: String): Set<String> =
    text.split(KEYWORD_SEPARATORS)
        .map { it.trim().take(CriteriaDraft.MAX_KEYWORD_LENGTH) }
        .filter { it.isNotEmpty() }
        .take(CriteriaDraft.MAX_KEYWORDS)
        .toCollection(LinkedHashSet())
