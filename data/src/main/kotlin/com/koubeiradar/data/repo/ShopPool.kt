package com.koubeiradar.data.repo

import com.koubeiradar.core.model.Shop
import com.koubeiradar.data.NearbyQuery
import com.koubeiradar.data.ShopSource

/**
 * The candidate pool as seen by the agent: read access plus "fetch more around the user".
 * [ShopRepository] is the production implementation; the agent depends on this interface so it can be tested with fakes.
 */
interface ShopPool {
    /** Current merged, de-duplicated shops (nearby + imported). */
    fun all(): List<Shop>

    fun find(id: String): Shop?

    /** Loads shops around [query].center via [source] into the pool and returns the nearby shops just loaded. */
    suspend fun refreshNearby(source: ShopSource, query: NearbyQuery, force: Boolean = false): List<Shop>
}
