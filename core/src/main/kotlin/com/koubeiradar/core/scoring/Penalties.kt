package com.koubeiradar.core.scoring

/** The point deductions of DESIGN.md §4.9; they stack. */
internal class Penalties(
    val hygiene: Boolean,
    val perfectButFewRatings: Boolean,
    val tooFewRatings: Boolean,
    val closed: Boolean,
) {
    val total: Double =
        (if (hygiene) HYGIENE else 0.0) +
            (if (perfectButFewRatings) PERFECT_BUT_FEW_RATINGS else 0.0) +
            (if (tooFewRatings) TOO_FEW_RATINGS else 0.0) +
            (if (closed) CLOSED else 0.0)

    /** A shop whose rating cannot be trusted yet never becomes RECOMMENDED. */
    val blocksRecommendation: Boolean get() = perfectButFewRatings || tooFewRatings

    companion object {
        const val HYGIENE = 15.0
        const val PERFECT_BUT_FEW_RATINGS = 8.0
        const val TOO_FEW_RATINGS = 5.0
        const val CLOSED = 10.0

        /** Ratings from here on count as "perfect" for the suspicious-rating check. */
        const val PERFECT_RATING = 4.9
        const val PERFECT_BUT_FEW_BELOW_COUNT = 30
        const val TOO_FEW_BELOW_COUNT = 10

        /** Below this displayed rating a shop is AVOID regardless of its score. */
        const val LOW_RATING = 4.0

        fun of(rating: Double, ratingCount: Int, openNow: Boolean?, hygieneFlag: Boolean) = Penalties(
            hygiene = hygieneFlag,
            perfectButFewRatings = rating >= PERFECT_RATING && ratingCount < PERFECT_BUT_FEW_BELOW_COUNT,
            tooFewRatings = ratingCount < TOO_FEW_BELOW_COUNT,
            closed = openNow == false,
        )
    }
}
