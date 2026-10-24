package com.koubeiradar.app.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.koubeiradar.app.service.RankerProvider
import com.koubeiradar.app.settings.SettingsRepository
import com.koubeiradar.app.store.ShopStore
import com.koubeiradar.core.model.Criteria
import com.koubeiradar.core.model.ScoredShop
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

sealed interface DetailUiState {
    data object Loading : DetailUiState

    data class Found(val scored: ScoredShop) : DetailUiState

    /** The shop is not (or no longer) in the candidate pool, e.g. after the process was restarted. */
    data object NotFound : DetailUiState
}

/** Scores the shop [shopId] within the whole candidate pool (the prior and percentiles depend on the other shops). */
class DetailViewModel(
    private val shopId: String,
    settings: SettingsRepository,
    store: ShopStore,
    rankers: RankerProvider,
    computeDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {
    val uiState: StateFlow<DetailUiState> = combine(
        store.shops,
        settings.settings.map { it.preset }.distinctUntilChanged(),
    ) { shops, preset ->
        val everything = Criteria(limit = shops.size.coerceAtLeast(1))
        rankers.forPreset(preset).select(shops, everything)
            .firstOrNull { it.shop.id == shopId }
            ?.let<ScoredShop, DetailUiState> { DetailUiState.Found(it) }
            ?: DetailUiState.NotFound
    }.flowOn(computeDispatcher).stateIn(viewModelScope, SharingStarted.Eagerly, DetailUiState.Loading)
}
