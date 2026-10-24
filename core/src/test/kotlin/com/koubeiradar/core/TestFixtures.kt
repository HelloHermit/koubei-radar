package com.koubeiradar.core

import com.koubeiradar.core.model.Criteria
import com.koubeiradar.core.model.Platform
import com.koubeiradar.core.model.Review
import com.koubeiradar.core.model.ScoredShop
import com.koubeiradar.core.model.ScoringConfig
import com.koubeiradar.core.model.Shop
import com.koubeiradar.core.scoring.ReputationScorer

/** Prior pinned to 4.4 so expected values can be computed by hand. */
internal val PINNED_PRIOR = ScoringConfig(priorMeanRating = 4.4)

internal fun shop(
    name: String = "测试店",
    rating: Double? = 4.5,
    ratingCount: Int? = 500,
    monthlySales: Int? = null,
    avgPrice: Double? = null,
    distanceMeters: Int? = null,
    deliveryMinutes: Int? = null,
    deliveryFee: Double? = null,
    category: String? = null,
    tags: List<String> = emptyList(),
    reviews: List<Review> = emptyList(),
    openNow: Boolean? = null,
    platforms: Set<Platform> = emptySet(),
    address: String? = null,
    id: String = "test:$name",
): Shop = Shop(
    id = id,
    name = name,
    source = "test",
    category = category,
    rating = rating,
    ratingCount = ratingCount,
    monthlySales = monthlySales,
    avgPrice = avgPrice,
    distanceMeters = distanceMeters,
    deliveryMinutes = deliveryMinutes,
    deliveryFee = deliveryFee,
    address = address,
    tags = tags,
    reviews = reviews,
    openNow = openNow,
    platforms = platforms,
)

internal fun reviews(vararg texts: String): List<Review> = texts.map { Review(it) }

/** [text] repeated [times] times, as separate reviews. */
internal fun repeated(text: String, times: Int): List<Review> = List(times) { Review(text) }

/**
 * Eight unremarkable, fully specified shops (ratings 4.0..4.8, prices 20..55) that give the population statistics
 * something to work with. Their median rating is 4.4.
 */
internal fun backdrop(): List<Shop> = listOf(4.0, 4.2, 4.3, 4.4, 4.5, 4.6, 4.7, 4.8).mapIndexed { index, rating ->
    shop(
        name = "背景$index",
        rating = rating,
        ratingCount = 400,
        avgPrice = 20.0 + 5.0 * index,
        distanceMeters = 800 + 300 * index,
        deliveryMinutes = 25 + 3 * index,
    )
}

/** Scores [probe] inside [background] and returns the probe's result. */
internal fun ReputationScorer.scoreProbe(
    probe: Shop,
    background: List<Shop> = backdrop(),
    criteria: Criteria = Criteria(),
): ScoredShop = score(background + probe, criteria).last()

internal fun ScoredShop.component(name: String): Double? = when (name) {
    "rating" -> breakdown.ratingScore
    "confidence" -> breakdown.confidenceScore
    "sentiment" -> breakdown.sentimentScore
    "value" -> breakdown.valueScore
    "convenience" -> breakdown.convenienceScore
    else -> error("unknown component $name")
}

/** All numbers a [ScoredShop] carries, for finiteness and range checks. */
internal fun ScoredShop.numbers(): List<Double> = listOfNotNull(
    score,
    breakdown.adjustedRating,
    breakdown.ratingScore,
    breakdown.confidenceScore,
    breakdown.sentimentScore,
    breakdown.valueScore,
    breakdown.convenienceScore,
    breakdown.penalty,
)
