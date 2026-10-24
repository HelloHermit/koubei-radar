package com.koubeiradar.core.selection

import com.koubeiradar.core.model.Criteria
import com.koubeiradar.core.model.Shop
import com.koubeiradar.core.scoring.asAmount
import com.koubeiradar.core.scoring.asCount
import com.koubeiradar.core.scoring.asRating
import java.util.Locale

/**
 * The shop-level part of a [Criteria], prepared once per `select` call. The null-handling rules are the ones
 * documented on [Criteria]: only an unknown rating fails `minRating`, every other unknown passes. Values the scorer
 * treats as unknown (a rating of 7.0, a distance of -3) are unknown here too, so both agree on what a shop is.
 * A NaN limit is ignored.
 */
internal class ShopFilter(private val criteria: Criteria) {
    private val include: List<String> = normalized(criteria.includeKeywords)
    private val exclude: List<String> = normalized(criteria.excludeKeywords)

    fun accepts(shop: Shop): Boolean =
        meetsRating(shop) &&
            meetsLimits(shop) &&
            (criteria.platforms.isEmpty() || shop.platforms.isEmpty() || shop.platforms.any { it in criteria.platforms }) &&
            !(criteria.onlyOpen && shop.openNow == false) &&
            meetsKeywords(shop)

    private fun meetsRating(shop: Shop): Boolean {
        val minRating = criteria.minRating ?: return true
        if (minRating.isNaN()) return true
        val rating = shop.rating.asRating() ?: return false
        return rating >= minRating
    }

    private fun meetsLimits(shop: Shop): Boolean {
        val avgPrice = shop.avgPrice.asAmount()
        return atLeast(shop.ratingCount.asCount(), criteria.minRatingCount) &&
            atMost(shop.distanceMeters.asCount(), criteria.maxDistanceMeters) &&
            atLeast(avgPrice, criteria.minAvgPrice) &&
            atMost(avgPrice, criteria.maxAvgPrice) &&
            atMost(shop.deliveryMinutes.asCount(), criteria.maxDeliveryMinutes) &&
            atMost(shop.deliveryFee.asAmount(), criteria.maxDeliveryFee)
    }

    private fun meetsKeywords(shop: Shop): Boolean {
        if (include.isEmpty() && exclude.isEmpty()) return true
        val haystacks = (listOf(shop.name) + listOfNotNull(shop.category) + shop.tags).map { it.lowercase(Locale.ROOT) }
        val mentioned = { keyword: String -> haystacks.any { it.contains(keyword) } }
        return (include.isEmpty() || include.any(mentioned)) && exclude.none(mentioned)
    }

    private fun atLeast(value: Int?, limit: Int?): Boolean = value == null || limit == null || value >= limit

    private fun atMost(value: Int?, limit: Int?): Boolean = value == null || limit == null || value <= limit

    private fun atLeast(value: Double?, limit: Double?): Boolean =
        value == null || limit == null || limit.isNaN() || value >= limit

    private fun atMost(value: Double?, limit: Double?): Boolean =
        value == null || limit == null || limit.isNaN() || value <= limit

    private companion object {
        /** Trimmed and lower-cased; blank entries are dropped because a blank keyword would match every shop. */
        fun normalized(keywords: Set<String>): List<String> =
            keywords.map { it.trim().lowercase(Locale.ROOT) }.filter { it.isNotEmpty() }.distinct()
    }
}
