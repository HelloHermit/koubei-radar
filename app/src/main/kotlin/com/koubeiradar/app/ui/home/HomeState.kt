package com.koubeiradar.app.ui.home

import com.koubeiradar.app.service.PlaceState
import com.koubeiradar.app.settings.PoiProvider
import com.koubeiradar.app.settings.ScoringPreset
import com.koubeiradar.app.ui.UiText
import com.koubeiradar.core.model.Criteria
import com.koubeiradar.core.model.ScoredShop
import com.koubeiradar.data.ShopSourceException

enum class SourceKind(val provider: PoiProvider?) {
    DEMO(null),
    AMAP(PoiProvider.AMAP),
    BAIDU(PoiProvider.BAIDU),
    ;

    companion object {
        fun of(provider: PoiProvider?): SourceKind = entries.first { it.provider == provider }
    }
}

sealed interface LoadState {
    data object Loading : LoadState
    data object Loaded : LoadState
    data class Failed(val kind: ShopSourceException.Kind) : LoadState

    /** The real data source cannot search without a position. */
    data object NeedsLocation : LoadState
}

/** State of the "set location" dialog; `null` in [HomeUiState.addressDialog] means closed. */
data class AddressDialogState(
    val searching: Boolean = false,
    val error: UiText? = null,
)

/** What the list area shows. */
sealed interface HomeContent {
    data object Loading : HomeContent
    data object NeedsLocation : HomeContent
    data class Failed(val kind: ShopSourceException.Kind) : HomeContent

    /** Nothing was found around the user at all. */
    data object NoShops : HomeContent

    /** Shops exist but the filter excludes all of them: the cue to offer "放宽条件". */
    data object NoMatch : HomeContent

    data object Results : HomeContent
}

data class HomeUiState(
    val source: SourceKind = SourceKind.DEMO,
    /** The selected provider, even while the demo data is shown; names the key a typed address needs. */
    val poiProvider: PoiProvider = PoiProvider.AMAP,
    val place: PlaceState = PlaceState.Unknown,
    val locating: Boolean = false,
    val preset: ScoringPreset = ScoringPreset.REPUTATION_FIRST,
    val criteria: Criteria = Criteria(),
    val shops: List<ScoredShop> = emptyList(),
    /** Size of the whole candidate pool before filtering. */
    val poolSize: Int = 0,
    val load: LoadState = LoadState.Loading,
    val canGeocode: Boolean = false,
    val showDemoBanner: Boolean = false,
    val addressDialog: AddressDialogState? = null,
) {
    val content: HomeContent
        get() = when {
            shops.isNotEmpty() -> HomeContent.Results
            load is LoadState.Loading -> HomeContent.Loading
            load is LoadState.NeedsLocation -> HomeContent.NeedsLocation
            load is LoadState.Failed -> HomeContent.Failed(load.kind)
            poolSize == 0 -> HomeContent.NoShops
            else -> HomeContent.NoMatch
        }
}

/** One-shot effects of the home screen. */
sealed interface HomeEvent {
    data object RequestLocationPermission : HomeEvent

    data object OpenAppSettings : HomeEvent

    data class Message(val text: UiText) : HomeEvent
}
