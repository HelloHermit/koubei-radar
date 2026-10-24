package com.koubeiradar.core.selection

import com.koubeiradar.core.model.Criteria
import com.koubeiradar.core.model.ScoredShop
import com.koubeiradar.core.model.Shop

/**
 * Ranks shops for a [Criteria]. [ShopSelector] is the production implementation; the agent and the
 * ViewModels depend on this interface so they can be tested with trivial fakes.
 */
interface ShopRanker {
    /** filter -> score -> rank. At most `criteria.limit` results, best first. */
    fun select(shops: List<Shop>, criteria: Criteria = Criteria()): List<ScoredShop>
}
