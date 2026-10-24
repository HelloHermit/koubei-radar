package com.koubeiradar.core.model

import kotlinx.serialization.Serializable

/**
 * User-facing filter. Every field is optional; the default instance filters nothing and returns the top 20.
 *
 * Handling of unknown (`null`) shop fields, relied upon by the UI and the agent:
 *  - [minRating]: a shop with `rating == null` is EXCLUDED (no rating = no reputation).
 *  - every other numeric limit: a shop with the corresponding field `null` PASSES (benefit of the doubt);
 *    the scorer lowers confidence / adds a warning instead.
 *  - [platforms]: shops with an empty `platforms` set (unknown) PASS.
 *  - [onlyOpen]: `openNow == null` PASSES, only `openNow == false` is excluded.
 */
@Serializable
data class Criteria(
    /** Minimum displayed platform rating (0..5), e.g. 4.3. */
    val minRating: Double? = null,
    val minRatingCount: Int? = null,
    val maxDistanceMeters: Int? = null,
    val minAvgPrice: Double? = null,
    val maxAvgPrice: Double? = null,
    val maxDeliveryMinutes: Int? = null,
    val maxDeliveryFee: Double? = null,
    /** Shop matches if ANY keyword is contained (case-insensitive) in its name, category or tags. */
    val includeKeywords: Set<String> = emptySet(),
    /** Shop is dropped if ANY keyword is contained in its name, category or tags. */
    val excludeKeywords: Set<String> = emptySet(),
    /** If non-empty, the shop must be orderable on at least one of these (unknown passes). */
    val platforms: Set<Platform> = emptySet(),
    val onlyOpen: Boolean = false,
    /** Minimum composite score 0..100, applied after scoring. */
    val minScore: Double? = null,
    /** Maximum number of results returned by the selector. */
    val limit: Int = 20,
)
