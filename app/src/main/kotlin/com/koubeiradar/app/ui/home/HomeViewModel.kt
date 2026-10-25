package com.koubeiradar.app.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.koubeiradar.app.R
import com.koubeiradar.app.platform.LocationResult
import com.koubeiradar.app.platform.LocationService
import com.koubeiradar.app.service.DataSources
import com.koubeiradar.app.service.PlaceState
import com.koubeiradar.app.service.PlaceStore
import com.koubeiradar.app.service.RankerProvider
import com.koubeiradar.app.service.centerFor
import com.koubeiradar.app.service.point
import com.koubeiradar.app.settings.AppSettings
import com.koubeiradar.app.settings.SaveResult
import com.koubeiradar.app.settings.SavedPlace
import com.koubeiradar.app.settings.ScoringPreset
import com.koubeiradar.app.settings.SettingsRepository
import com.koubeiradar.app.settings.tryUpdate
import com.koubeiradar.app.store.ShopStore
import com.koubeiradar.app.ui.UiText
import com.koubeiradar.app.ui.criteria.relaxed
import com.koubeiradar.app.ui.messageRes
import com.koubeiradar.core.model.Criteria
import com.koubeiradar.core.model.ScoredShop
import com.koubeiradar.data.NearbyQuery
import com.koubeiradar.data.ShopSourceException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Home screen: finds the user, loads nearby shops into the shared pool and ranks the pool for the current filter and
 * scoring preset. Ranking runs on [computeDispatcher]; the filter and the preset are persisted settings, so changing
 * them re-ranks the pool without another network request.
 */
class HomeViewModel(
    private val settings: SettingsRepository,
    private val store: ShopStore,
    private val rankers: RankerProvider,
    private val dataSources: DataSources,
    private val location: LocationService,
    private val places: PlaceStore,
    private val computeDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {

    private val settingsState: StateFlow<AppSettings?> =
        settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    private val load = MutableStateFlow<LoadState>(LoadState.Loading)

    /** Number of position lookups in flight; a counter, so a cancelled lookup cannot switch the indicator off under its successor. */
    private val locatingCount = MutableStateFlow(0)
    private val addressDialog = MutableStateFlow<AddressDialogState?>(null)
    private val eventChannel = Channel<HomeEvent>(Channel.BUFFERED)

    private var loadJob: Job? = null
    private var locateJob: Job? = null
    private var geocodeJob: Job? = null

    val events: Flow<HomeEvent> = eventChannel.receiveAsFlow()

    private val ranking: Flow<Ranking> = combine(
        store.shops,
        settingsState.filterNotNull().map { it.criteria to it.preset }.distinctUntilChanged(),
    ) { shops, (criteria, preset) ->
        Ranking(poolSize = shops.size, shops = rankers.forPreset(preset).select(shops, criteria))
    }.flowOn(computeDispatcher)

    val uiState: StateFlow<HomeUiState> = combine(
        settingsState.filterNotNull(),
        ranking,
        places.place,
        load,
        combine(locatingCount, addressDialog) { count, dialog -> (count > 0) to dialog },
    ) { current, ranked, place, loadState, (isLocating, dialog) ->
        val source = if (current.usesAmap) SourceKind.AMAP else SourceKind.DEMO
        HomeUiState(
            source = source,
            place = place,
            locating = isLocating,
            preset = current.preset,
            criteria = current.criteria,
            shops = ranked.shops,
            poolSize = ranked.poolSize,
            load = loadState,
            canGeocode = dataSources.geocoderFor(current) != null,
            showDemoBanner = source == SourceKind.DEMO && !current.demoBannerDismissed,
            addressDialog = dialog,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, HomeUiState())

    init {
        viewModelScope.launch {
            establishPlace()
            settingsState.filterNotNull()
                .map { it.usesAmap to it.amapKey }
                .distinctUntilChanged()
                .collect { reload(force = false) }
        }
    }

    /** Pull-to-refresh and the retry buttons: re-locates when the position came from the device, then reloads for real. */
    fun refresh() {
        locateJob?.cancel()
        locateJob = viewModelScope.launch {
            if (places.place.value is PlaceState.Device && location.hasPermission()) locateNow(userInitiated = false)
            reload(force = true)
        }
    }

    /** "使用当前位置": locates, asks the UI for the permission first, or sends the user to the app settings if the system will not ask again. */
    fun onLocateRequested() {
        val place = places.place.value
        when {
            location.hasPermission() -> locate()
            place is PlaceState.PermissionDenied && place.permanent -> emit(HomeEvent.OpenAppSettings)
            else -> emit(HomeEvent.RequestLocationPermission)
        }
    }

    /** Result of the runtime permission dialog; [permanentlyDenied] is only meaningful when [granted] is false. */
    fun onPermissionResult(granted: Boolean, permanentlyDenied: Boolean) {
        if (granted) {
            locate()
            return
        }
        if (places.place.value.point == null) places.set(PlaceState.PermissionDenied(permanentlyDenied))
        emit(HomeEvent.Message(UiText(R.string.home_msg_permission_denied)))
    }

    /** The user may have granted the permission in the system settings while the app was in the background. */
    fun onScreenResumed() {
        if (places.place.value is PlaceState.PermissionDenied && location.hasPermission()) locate()
    }

    fun setPreset(preset: ScoringPreset) {
        viewModelScope.launch { saveSettings { it.copy(preset = preset) } }
    }

    fun setCriteria(criteria: Criteria) {
        viewModelScope.launch { saveSettings { it.copy(criteria = criteria) } }
    }

    /** One tap on "放宽条件" in the empty state. */
    fun relaxCriteria() {
        val relaxed = uiState.value.criteria.relaxed()
        viewModelScope.launch {
            if (saveSettings { it.copy(criteria = relaxed) }) emit(HomeEvent.Message(UiText(R.string.home_msg_relaxed)))
        }
    }

    fun dismissDemoBanner() {
        viewModelScope.launch { saveSettings { it.copy(demoBannerDismissed = true) } }
    }

    fun openAddressDialog() {
        addressDialog.value = AddressDialogState()
    }

    fun closeAddressDialog() {
        geocodeJob?.cancel()
        addressDialog.value = null
    }

    /** Resolves a typed address to a position (Amap geocoding) and makes it the search centre. */
    fun submitAddress(text: String) {
        val address = text.trim()
        if (address.isEmpty()) {
            addressDialog.update { it?.copy(error = UiText(R.string.home_address_empty)) }
            return
        }
        geocodeJob?.cancel()
        geocodeJob = viewModelScope.launch {
            val geocoder = settingsState.value?.let(dataSources::geocoderFor)
            if (geocoder == null) {
                addressDialog.update { it?.copy(error = UiText(R.string.home_address_needs_key)) }
                return@launch
            }
            addressDialog.value = AddressDialogState(searching = true)
            try {
                val point = geocoder.geocode(address)
                if (point == null) {
                    addressDialog.value = AddressDialogState(error = UiText(R.string.home_address_not_found))
                } else {
                    places.set(PlaceState.Manual(address, point))
                    addressDialog.value = null
                    saveSettings { it.copy(savedPlace = SavedPlace(address, point)) }
                    reload(force = false)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ShopSourceException) {
                addressDialog.value = AddressDialogState(error = UiText(e.kind.messageRes()))
            } catch (e: Exception) {
                addressDialog.value = AddressDialogState(error = UiText(ShopSourceException.Kind.OTHER.messageRes()))
            }
        }
    }

    /** A fresh start: GPS if the permission is already granted, otherwise the address typed last time. */
    private suspend fun establishPlace() {
        if (places.place.value != PlaceState.Unknown) return
        if (location.hasPermission()) {
            locateNow(userInitiated = false)
        } else {
            // A remembered device position is only for the daily recommendation: it is not a place the user typed.
            settingsState.filterNotNull().first().savedPlace?.takeUnless { it.fromDevice }
                ?.let { places.set(PlaceState.Manual(it.address, it.point)) }
        }
    }

    private fun locate() {
        locateJob?.cancel()
        locateJob = viewModelScope.launch {
            if (locateNow(userInitiated = true)) reload(force = false)
        }
    }

    /** Returns whether a new position was found. A failure keeps the previous position, if there was one. */
    private suspend fun locateNow(userInitiated: Boolean): Boolean {
        locatingCount.update { it + 1 }
        try {
            when (val result = location.currentLocation()) {
                is LocationResult.Found -> {
                    places.set(PlaceState.Device(result.point, result.accuracyMeters))
                    rememberPlace(SavedPlace(address = "", point = result.point, fromDevice = true))
                    return true
                }

                LocationResult.PermissionDenied -> {
                    if (places.place.value.point == null) places.set(PlaceState.PermissionDenied(permanent = false))
                    if (userInitiated) emit(HomeEvent.Message(UiText(R.string.home_msg_permission_denied)))
                }

                LocationResult.Unavailable -> {
                    if (places.place.value.point == null) places.set(PlaceState.Unavailable)
                    if (userInitiated) emit(HomeEvent.Message(UiText(R.string.home_msg_locating_failed)))
                }
            }
            return false
        } finally {
            locatingCount.update { it - 1 }
        }
    }

    private fun reload(force: Boolean) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch { loadNearby(force) }
    }

    private suspend fun loadNearby(force: Boolean) {
        val current = settingsState.filterNotNull().first()
        val source = dataSources.sourceFor(current)
        val center = places.place.value.centerFor(source)
        if (center == null) {
            load.value = LoadState.NeedsLocation
            return
        }
        load.value = LoadState.Loading
        load.value = try {
            store.refreshNearby(source, NearbyQuery(center, NEARBY_RADIUS_METERS, keyword = null, limit = NEARBY_LIMIT), force)
            LoadState.Loaded
        } catch (e: CancellationException) {
            throw e
        } catch (e: ShopSourceException) {
            LoadState.Failed(e.kind)
        } catch (e: Exception) {
            // The source is another module's code: whatever it throws becomes an error state, never a crash.
            LoadState.Failed(ShopSourceException.Kind.OTHER)
        }
    }

    /** The daily recommendation looks around the last place set here, without reading the location itself. A failed write only means it keeps the older place. */
    private fun rememberPlace(place: SavedPlace) {
        viewModelScope.launch { settings.tryUpdate { it.copy(savedPlace = place) } }
    }

    private suspend fun saveSettings(transform: (AppSettings) -> AppSettings): Boolean {
        val saved = settings.tryUpdate(transform) == SaveResult.SAVED
        if (!saved) emit(HomeEvent.Message(UiText(R.string.msg_save_failed)))
        return saved
    }

    private fun emit(event: HomeEvent) {
        eventChannel.trySend(event)
    }

    private class Ranking(val poolSize: Int, val shops: List<ScoredShop>)

    companion object {
        /** Wide enough to include every demo shop (the farthest is 4.6 km away) and Amap's 5 km maximum useful radius. */
        const val NEARBY_RADIUS_METERS = 5000
        const val NEARBY_LIMIT = 50
    }
}
