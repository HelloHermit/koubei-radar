package com.koubeiradar.app.store

import com.koubeiradar.core.model.Shop
import com.koubeiradar.data.NearbyQuery
import com.koubeiradar.data.ShopSource
import com.koubeiradar.data.repo.ShopRepository
import kotlinx.coroutines.flow.StateFlow

/**
 * The candidate pool as the screens use it. A seam in front of the concrete `ShopRepository`, so view models can be
 * tested with fakes and persistence can be layered on ([PersistingShopStore]).
 */
interface ShopStore {
    /** Nearby shops merged with imported ones, de-duplicated; mirrors `ShopRepository.all()`. */
    val shops: StateFlow<List<Shop>>

    suspend fun refreshNearby(source: ShopSource, query: NearbyQuery, force: Boolean = false): List<Shop>

    fun addImported(shops: List<Shop>)

    fun clearImported()

    fun clearAll()
}

class RepositoryShopStore(private val repository: ShopRepository) : ShopStore {
    override val shops: StateFlow<List<Shop>> get() = repository.shops

    override suspend fun refreshNearby(source: ShopSource, query: NearbyQuery, force: Boolean): List<Shop> =
        repository.refreshNearby(source, query, force)

    override fun addImported(shops: List<Shop>) = repository.addImported(shops)

    override fun clearImported() = repository.clearImported()

    override fun clearAll() = repository.clearAll()
}
