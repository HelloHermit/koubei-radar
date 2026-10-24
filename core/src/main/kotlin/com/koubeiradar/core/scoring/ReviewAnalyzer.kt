package com.koubeiradar.core.scoring

import com.koubeiradar.core.model.Review
import java.util.Locale

/** What the review texts of one shop say. Component scores are at full precision. */
internal class ReviewAnalysis(
    /** Number of reviews with a non-blank text; blank ones are ignored entirely. */
    val reviewCount: Int,
    /** `null` below [ReviewAnalyzer.MIN_REVIEWS_FOR_SENTIMENT] reviews. */
    val sentimentScore: Double?,
    val hygieneFlag: Boolean,
    /** Reviews containing at least one red-flag term. */
    val redReviewCount: Int,
    /** Reviews containing at least one ordinary negative term. */
    val negativeReviewCount: Int,
    /** Positive terms mentioned by at least two reviews, most frequent first, at most three. */
    val topPositiveTerms: List<String>,
    /** Negative terms by frequency, at most three. */
    val negativeTerms: List<String>,
    /** Red-flag terms by frequency, at most three. */
    val redTerms: List<String>,
) {
    companion object {
        val NONE = ReviewAnalysis(0, null, false, 0, 0, emptyList(), emptyList(), emptyList())
    }
}

internal class TermHits(val positive: Set<String>, val negative: Set<String>, val red: Set<String>)

/** Offline lexicon-based review analysis, DESIGN.md §4.5. */
internal object ReviewAnalyzer {
    const val MIN_REVIEWS_FOR_SENTIMENT = 3

    private const val MAX_HITS_PER_REVIEW = 3
    private const val MAX_REVIEW_CHARS = 2_000
    private const val MIN_REVIEWS_FOR_TOP_TERM = 2
    private const val MAX_REPORTED_TERMS = 3
    private const val HYGIENE_MIN_REVIEWS = 2
    private const val HYGIENE_MIN_SHARE = 0.10
    private const val NEGATIVE_STAR_LIMIT = 2.0
    private const val POSITIVE_STAR_LIMIT = 4.5

    private val trie: TermTrie = TermTrie(
        ReviewLexicon.positive.map { it to TermKind.POSITIVE } +
            ReviewLexicon.negative.map { it to TermKind.NEGATIVE } +
            ReviewLexicon.redFlags.map { it to TermKind.RED_FLAG } +
            ReviewLexicon.neutral.map { it to TermKind.NEUTRAL },
    )

    fun analyze(reviews: List<Review>): ReviewAnalysis {
        var reviewCount = 0
        var positive = 0
        var negative = 0
        var red = 0
        var redReviews = 0
        var negativeReviews = 0
        val positiveFrequency = HashMap<String, Int>()
        val negativeFrequency = HashMap<String, Int>()
        val redFrequency = HashMap<String, Int>()

        for (review in reviews) {
            val text = normalize(review.text)
            if (text.isEmpty()) continue
            reviewCount++

            val hits = scan(text)
            hits.positive.forEach { positiveFrequency.merge(it, 1, Int::plus) }
            hits.negative.forEach { negativeFrequency.merge(it, 1, Int::plus) }
            hits.red.forEach { redFrequency.merge(it, 1, Int::plus) }
            if (hits.negative.isNotEmpty()) negativeReviews++
            if (hits.red.isNotEmpty()) redReviews++

            var reviewPositive = minOf(hits.positive.size, MAX_HITS_PER_REVIEW)
            var reviewNegative = minOf(hits.negative.size, MAX_HITS_PER_REVIEW)
            val stars = review.rating?.takeIf { it.isFinite() && it >= 1.0 && it <= 5.0 }
            if (stars != null) {
                if (stars <= NEGATIVE_STAR_LIMIT) reviewNegative++ else if (stars >= POSITIVE_STAR_LIMIT) reviewPositive++
            }
            positive += reviewPositive
            negative += reviewNegative
            red += minOf(hits.red.size, MAX_HITS_PER_REVIEW)
        }
        if (reviewCount == 0) return ReviewAnalysis.NONE

        val sentiment = if (reviewCount >= MIN_REVIEWS_FOR_SENTIMENT) {
            val net = (positive - 1.5 * negative - 4.0 * red) / (reviewCount + 5.0)
            clamp(60.0 + 40.0 * net, 0.0, 100.0)
        } else {
            null
        }
        val hygieneFlag = redReviews >= HYGIENE_MIN_REVIEWS || redReviews.toDouble() / reviewCount >= HYGIENE_MIN_SHARE

        return ReviewAnalysis(
            reviewCount = reviewCount,
            sentimentScore = sentiment,
            hygieneFlag = hygieneFlag,
            redReviewCount = redReviews,
            negativeReviewCount = negativeReviews,
            topPositiveTerms = mostFrequent(positiveFrequency, MIN_REVIEWS_FOR_TOP_TERM),
            negativeTerms = mostFrequent(negativeFrequency, 1),
            redTerms = mostFrequent(redFrequency, 1),
        )
    }

    /** The distinct, non-negated lexicon terms found in one review text. */
    fun hitsIn(text: String): TermHits = scan(normalize(text))

    private fun normalize(text: String): String = text.trim().take(MAX_REVIEW_CHARS).lowercase(Locale.ROOT)

    private fun scan(normalizedText: String): TermHits {
        val positive = LinkedHashSet<String>()
        val negative = LinkedHashSet<String>()
        val red = LinkedHashSet<String>()
        var previousEnd = 0
        for (match in trie.scan(normalizedText)) {
            if (match.kind == TermKind.NEUTRAL) continue
            val negated = Negation.isNegated(normalizedText, match.start, previousEnd)
            previousEnd = match.end
            if (negated) continue
            when (match.kind) {
                TermKind.POSITIVE -> positive += match.term
                TermKind.NEGATIVE -> negative += match.term
                TermKind.RED_FLAG -> red += match.term
                TermKind.NEUTRAL -> Unit
            }
        }
        return TermHits(positive, negative, red)
    }

    private fun mostFrequent(frequency: Map<String, Int>, minReviews: Int): List<String> =
        frequency.entries
            .filter { it.value >= minReviews }
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { ReviewLexicon.indexOf(it.key) })
            .take(MAX_REPORTED_TERMS)
            .map { it.key }
}
