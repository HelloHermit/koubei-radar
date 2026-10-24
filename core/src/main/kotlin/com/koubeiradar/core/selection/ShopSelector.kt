package com.koubeiradar.core.selection

import com.koubeiradar.core.model.Criteria
import com.koubeiradar.core.model.ScoredShop
import com.koubeiradar.core.model.Shop
import com.koubeiradar.core.model.Verdict
import com.koubeiradar.core.scoring.ReputationScorer

/**
 * filter -> score -> rank.
 *
 * CONTRACT (frozen):
 *  - scoring statistics are computed over ALL given shops (before filtering) so a strict filter does not skew the prior;
 *  - [select] returns at most `criteria.limit` shops, sorted by score descending
 *    (ties: ratingCount desc, then name asc); shops with `Verdict.INSUFFICIENT_DATA` sort last;
 *  - `criteria.minScore` is applied after scoring;
 *  - [matches] implements the null-handling rules documented on [Criteria].
 *
 * The tie-breaker is the effective rating count (reported, or estimated from sales, or the scorer's assumption), and
 * the score compared is the stored one-decimal score. A `limit` of zero or less selects nothing.
 */
class ShopSelector(val scorer: ReputationScorer = ReputationScorer()) : ShopRanker {

    override fun select(shops: List<Shop>, criteria: Criteria): List<ScoredShop> {
        if (criteria.limit <= 0 || shops.isEmpty()) return emptyList()
        val filter = ShopFilter(criteria)
        val minScore = criteria.minScore?.takeUnless { it.isNaN() }
        return scorer.score(shops, criteria)
            .filter { filter.accepts(it.shop) && (minScore == null || it.score >= minScore) }
            .sortedWith(RANKING)
            .take(criteria.limit)
    }

    /** Whether [shop] passes the shop-level fields of [criteria]; `minScore` and `limit` are applied by [select]. */
    fun matches(shop: Shop, criteria: Criteria): Boolean = ShopFilter(criteria).accepts(shop)

    private companion object {
        val RANKING: Comparator<ScoredShop> = compareBy<ScoredShop> { it.verdict == Verdict.INSUFFICIENT_DATA }
            .thenByDescending { it.score }
            .thenByDescending { it.breakdown.effectiveRatingCount ?: -1 }
            .thenBy { it.shop.name }
    }
}
