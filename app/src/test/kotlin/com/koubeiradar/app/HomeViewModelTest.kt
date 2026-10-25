package com.koubeiradar.app

import com.koubeiradar.app.platform.LocationResult
import com.koubeiradar.app.service.DEMO_CENTER
import com.koubeiradar.app.service.PlaceState
import com.koubeiradar.app.service.PlaceStore
import com.koubeiradar.app.settings.AppSettings
import com.koubeiradar.app.settings.PoiProvider
import com.koubeiradar.app.settings.SavedPlace
import com.koubeiradar.app.settings.ScoringPreset
import com.koubeiradar.app.settings.Secret
import com.koubeiradar.app.ui.UiText
import com.koubeiradar.app.ui.home.HomeContent
import com.koubeiradar.app.ui.home.HomeEvent
import com.koubeiradar.app.ui.home.HomeViewModel
import com.koubeiradar.app.ui.home.LoadState
import com.koubeiradar.app.ui.home.SourceKind
import com.koubeiradar.core.model.Criteria
import com.koubeiradar.core.model.GeoPoint
import com.koubeiradar.data.ShopSourceException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {
    @get:Rule
    val main = MainDispatcherRule(StandardTestDispatcher())

    private val settings = FakeSettings()
    private val store = FakeShopStore()
    private val rankers = FakeRankers()
    private val geocoder = FakeGeocoder()
    private val dataSources = FakeDataSources(geocoder)
    private val location = FakeLocation()
    private val places = PlaceStore()

    private val amap = AppSettings(amapKey = Secret("amap-key"))
    private val somewhere = GeoPoint(30.1, 120.2)

    private fun TestScope.viewModel() =
        HomeViewModel(settings, store, rankers, dataSources, location, places, StandardTestDispatcher(testScheduler))

    private fun TestScope.eventsOf(viewModel: HomeViewModel): List<HomeEvent> {
        val events = mutableListOf<HomeEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.events.collect { events += it } }
        return events
    }

    @Test
    fun `loading, then results ranked best first`() = runTest {
        store.nearby = listOf(shop("a", rating = 4.2), shop("b", rating = 4.8))
        store.gate = CompletableDeferred()
        val viewModel = viewModel()
        advanceUntilIdle()

        assertEquals(HomeContent.Loading, viewModel.uiState.value.content)
        val request = store.refreshes.single()
        assertEquals(DEMO_CENTER, request.query.center, "demo data is centred on the default place without any permission")
        assertFalse(request.force)

        store.gate?.complete(Unit)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(HomeContent.Results, state.content)
        assertEquals(listOf("b", "a"), state.shops.map { it.shop.id })
        assertEquals(SourceKind.DEMO, state.source)
        assertEquals(LoadState.Loaded, state.load)
    }

    @Test
    fun `a filter that excludes everything shows no match, and relaxing brings the shops back`() = runTest {
        store.nearby = listOf(shop("a", rating = 4.2), shop("b", rating = 4.4))
        settings.state.value = AppSettings(criteria = Criteria(minRating = 4.5))
        val viewModel = viewModel()
        val events = eventsOf(viewModel)
        advanceUntilIdle()

        assertEquals(HomeContent.NoMatch, viewModel.uiState.value.content)
        assertEquals(2, viewModel.uiState.value.poolSize)

        viewModel.relaxCriteria()
        advanceUntilIdle()

        assertEquals(4.2, settings.current.criteria.minRating)
        assertEquals(HomeContent.Results, viewModel.uiState.value.content)
        assertEquals(1, store.refreshes.size, "relaxing re-ranks the pool, it does not reload it")
        assertTrue(events.contains(HomeEvent.Message(UiText(R.string.home_msg_relaxed))))
    }

    @Test
    fun `nothing nearby is told apart from nothing matching`() = runTest {
        val viewModel = viewModel()
        advanceUntilIdle()
        assertEquals(HomeContent.NoShops, viewModel.uiState.value.content)
    }

    @Test
    fun `a failing source shows its error kind and retry recovers`() = runTest {
        store.failure = ShopSourceException("bad key", ShopSourceException.Kind.AUTH)
        store.nearby = listOf(shop("a"))
        val viewModel = viewModel()
        advanceUntilIdle()

        assertEquals(HomeContent.Failed(ShopSourceException.Kind.AUTH), viewModel.uiState.value.content)

        store.failure = null
        viewModel.refresh()
        advanceUntilIdle()

        assertEquals(HomeContent.Results, viewModel.uiState.value.content)
        assertTrue(store.refreshes.last().force, "a retry bypasses the cache")
    }

    @Test
    fun `an unexpected exception from the source becomes an error state`() = runTest {
        store.failure = IllegalStateException("boom")
        val viewModel = viewModel()
        advanceUntilIdle()
        assertEquals(HomeContent.Failed(ShopSourceException.Kind.OTHER), viewModel.uiState.value.content)
    }

    @Test
    fun `old results stay visible when a refresh fails`() = runTest {
        store.nearby = listOf(shop("a"))
        val viewModel = viewModel()
        advanceUntilIdle()
        store.failure = ShopSourceException("offline", ShopSourceException.Kind.NETWORK)
        viewModel.refresh()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(HomeContent.Results, state.content)
        assertEquals(LoadState.Failed(ShopSourceException.Kind.NETWORK), state.load)
    }

    @Test
    fun `changing the filter re-ranks the pool without another request`() = runTest {
        store.nearby = listOf(shop("a", rating = 4.2), shop("b", rating = 4.8), shop("c", rating = 4.5))
        val viewModel = viewModel()
        advanceUntilIdle()
        assertEquals(3, viewModel.uiState.value.shops.size)

        viewModel.setCriteria(Criteria(minRating = 4.4))
        advanceUntilIdle()

        assertEquals(listOf("b", "c"), viewModel.uiState.value.shops.map { it.shop.id })
        assertEquals(Criteria(minRating = 4.4), rankers.calls.last().second)
        assertEquals(Criteria(minRating = 4.4), settings.current.criteria, "the filter is persisted")
        assertEquals(1, store.refreshes.size)
    }

    @Test
    fun `changing the scoring preset re-ranks with that preset`() = runTest {
        store.nearby = listOf(shop("dear", rating = 4.8, price = 80.0), shop("cheap", rating = 4.4, price = 15.0))
        val viewModel = viewModel()
        advanceUntilIdle()
        assertEquals(listOf("dear", "cheap"), viewModel.uiState.value.shops.map { it.shop.id })

        viewModel.setPreset(ScoringPreset.VALUE_FIRST)
        advanceUntilIdle()

        assertEquals(ScoringPreset.VALUE_FIRST, viewModel.uiState.value.preset)
        assertEquals(listOf("cheap", "dear"), viewModel.uiState.value.shops.map { it.shop.id })
        assertEquals(ScoringPreset.VALUE_FIRST, rankers.calls.last().first)
        assertEquals(1, store.refreshes.size)
    }

    @Test
    fun `real data without a position asks for one instead of failing, also on refresh`() = runTest {
        settings.state.value = amap
        val viewModel = viewModel()
        advanceUntilIdle()

        assertEquals(HomeContent.NeedsLocation, viewModel.uiState.value.content)
        assertEquals(SourceKind.AMAP, viewModel.uiState.value.source)
        assertFalse(viewModel.uiState.value.showDemoBanner)

        viewModel.refresh()
        advanceUntilIdle()

        assertEquals(HomeContent.NeedsLocation, viewModel.uiState.value.content)
        assertTrue(store.refreshes.isEmpty(), "no request without a position")
    }

    @Test
    fun `permission denied is never a dead end`() = runTest {
        settings.state.value = amap
        val viewModel = viewModel()
        val events = eventsOf(viewModel)
        advanceUntilIdle()

        viewModel.onLocateRequested()
        advanceUntilIdle()
        assertEquals(HomeEvent.RequestLocationPermission, events.last())

        viewModel.onPermissionResult(granted = false, permanentlyDenied = false)
        advanceUntilIdle()
        assertEquals(PlaceState.PermissionDenied(permanent = false), viewModel.uiState.value.place)
        viewModel.onLocateRequested()
        advanceUntilIdle()
        assertEquals(HomeEvent.RequestLocationPermission, events.last(), "the system dialog may still be shown")

        viewModel.onPermissionResult(granted = false, permanentlyDenied = true)
        advanceUntilIdle()
        assertEquals(PlaceState.PermissionDenied(permanent = true), viewModel.uiState.value.place)
        viewModel.onLocateRequested()
        advanceUntilIdle()
        assertEquals(HomeEvent.OpenAppSettings, events.last(), "after \"don't ask again\" only the app settings can help")
        assertEquals(HomeContent.NeedsLocation, viewModel.uiState.value.content)

        // ... and the address path works without any permission.
        geocoder.result = somewhere
        viewModel.openAddressDialog()
        viewModel.submitAddress("  望京SOHO ")
        advanceUntilIdle()

        assertEquals(listOf("望京SOHO"), geocoder.requests)
        assertEquals(PlaceState.Manual("望京SOHO", somewhere), viewModel.uiState.value.place)
        assertEquals(somewhere, store.refreshes.last().query.center)
        assertEquals(SavedPlace("望京SOHO", somewhere), settings.current.savedPlace)
        assertEquals(null, viewModel.uiState.value.addressDialog)
    }

    @Test
    fun `a granted permission locates once and uses the position as it is`() = runTest {
        settings.state.value = amap
        location.result = LocationResult.Found(somewhere, accuracyMeters = 25)
        val viewModel = viewModel()
        advanceUntilIdle()
        assertEquals(HomeContent.NeedsLocation, viewModel.uiState.value.content)

        location.permission = true
        viewModel.onPermissionResult(granted = true, permanentlyDenied = false)
        advanceUntilIdle()

        assertEquals(PlaceState.Device(somewhere, 25), viewModel.uiState.value.place)
        assertEquals(somewhere, store.refreshes.single().query.center, "the point is already GCJ-02: no second conversion")
        assertEquals(1, location.lookups)
        assertFalse(viewModel.uiState.value.locating)
    }

    @Test
    fun `with the permission already granted the first load waits for the position and happens once`() = runTest {
        location.permission = true
        location.result = LocationResult.Found(somewhere, accuracyMeters = null)
        val viewModel = viewModel()
        advanceUntilIdle()

        assertEquals(somewhere, store.refreshes.single().query.center)
        assertEquals(PlaceState.Device(somewhere, null), viewModel.uiState.value.place)
    }

    @Test
    fun `a failed lookup keeps the previous place and says so`() = runTest {
        places.set(PlaceState.Manual("家", somewhere))
        location.permission = true
        location.result = LocationResult.Unavailable
        val viewModel = viewModel()
        val events = eventsOf(viewModel)
        advanceUntilIdle()

        viewModel.onLocateRequested()
        advanceUntilIdle()

        assertEquals(PlaceState.Manual("家", somewhere), viewModel.uiState.value.place)
        assertTrue(events.contains(HomeEvent.Message(UiText(R.string.home_msg_locating_failed))))
    }

    @Test
    fun `the address typed last time is used when there is no permission`() = runTest {
        settings.state.value = amap.copy(savedPlace = SavedPlace("公司", somewhere))
        val viewModel = viewModel()
        advanceUntilIdle()

        assertEquals(PlaceState.Manual("公司", somewhere), viewModel.uiState.value.place)
        assertEquals(somewhere, store.refreshes.single().query.center)
    }

    @Test
    fun `a position found by the device is remembered for the daily recommendation`() = runTest {
        settings.state.value = amap
        location.permission = true
        location.result = LocationResult.Found(somewhere, accuracyMeters = 25)

        viewModel()
        advanceUntilIdle()

        assertEquals(SavedPlace(address = "", point = somewhere, fromDevice = true), settings.current.savedPlace)
    }

    @Test
    fun `a failed position lookup leaves the remembered place alone`() = runTest {
        settings.state.value = amap.copy(savedPlace = SavedPlace("公司", somewhere))
        location.permission = true
        location.result = LocationResult.Unavailable

        viewModel()
        advanceUntilIdle()

        assertEquals(SavedPlace("公司", somewhere), settings.current.savedPlace)
    }

    @Test
    fun `a remembered device position is not mistaken for a typed address when there is no permission`() = runTest {
        settings.state.value = amap.copy(savedPlace = SavedPlace("", somewhere, fromDevice = true))
        val viewModel = viewModel()
        advanceUntilIdle()

        assertEquals(PlaceState.Unknown, viewModel.uiState.value.place)
        assertEquals(HomeContent.NeedsLocation, viewModel.uiState.value.content)
        assertTrue(store.refreshes.isEmpty())
    }

    @Test
    fun `address lookup reports what went wrong inside the dialog`() = runTest {
        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.openAddressDialog()

        viewModel.submitAddress("   ")
        advanceUntilIdle()
        assertEquals(UiText(R.string.home_address_empty), viewModel.uiState.value.addressDialog?.error)

        viewModel.submitAddress("望京")
        advanceUntilIdle()
        assertEquals(UiText(R.string.home_address_needs_key), viewModel.uiState.value.addressDialog?.error, "demo data cannot geocode")
        assertFalse(viewModel.uiState.value.canGeocode)

        settings.state.value = amap
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.canGeocode)
        viewModel.submitAddress("不存在的地方")
        advanceUntilIdle()
        assertEquals(UiText(R.string.home_address_not_found), viewModel.uiState.value.addressDialog?.error)

        geocoder.failure = ShopSourceException("quota", ShopSourceException.Kind.QUOTA)
        viewModel.submitAddress("望京")
        advanceUntilIdle()
        assertEquals(UiText(R.string.error_source_quota), viewModel.uiState.value.addressDialog?.error)

        viewModel.closeAddressDialog()
        advanceUntilIdle()
        assertEquals(null, viewModel.uiState.value.addressDialog)
    }

    @Test
    fun `changing the data source reloads`() = runTest {
        store.nearby = listOf(shop("a"))
        val viewModel = viewModel()
        advanceUntilIdle()
        assertEquals("demo", store.refreshes.single().source.id)

        places.set(PlaceState.Manual("家", somewhere))
        settings.state.value = amap
        advanceUntilIdle()

        assertEquals(listOf("demo", "amap"), store.refreshes.map { it.source.id })
        assertEquals(SourceKind.AMAP, viewModel.uiState.value.source)
    }

    @Test
    fun `the baidu provider searches with its own key, names itself and can geocode`() = runTest {
        places.set(PlaceState.Manual("家", somewhere))
        settings.state.value = AppSettings(poiProvider = PoiProvider.BAIDU, baiduKey = Secret("baidu-ak"))
        val viewModel = viewModel()
        advanceUntilIdle()

        assertEquals("baidu", store.refreshes.single().source.id)
        assertEquals(SourceKind.BAIDU, viewModel.uiState.value.source)
        assertEquals(PoiProvider.BAIDU, viewModel.uiState.value.poiProvider)
        assertTrue(viewModel.uiState.value.canGeocode)
        assertFalse(viewModel.uiState.value.showDemoBanner)
    }

    @Test
    fun `a key of the other provider keeps the demo data and the address hint names the selected provider`() = runTest {
        settings.state.value = AppSettings(amapKey = Secret("amap-key"), poiProvider = PoiProvider.BAIDU)
        val viewModel = viewModel()
        advanceUntilIdle()
        assertEquals(SourceKind.DEMO, viewModel.uiState.value.source)
        assertEquals("demo", store.refreshes.single().source.id)
        assertTrue(viewModel.uiState.value.showDemoBanner)

        viewModel.openAddressDialog()
        viewModel.submitAddress("望京")
        advanceUntilIdle()

        assertEquals(UiText(R.string.home_address_needs_key_baidu), viewModel.uiState.value.addressDialog?.error)
        assertTrue(geocoder.requests.isEmpty())
    }

    @Test
    fun `switching the provider reloads from the new source`() = runTest {
        places.set(PlaceState.Manual("家", somewhere))
        settings.state.value = amap
        val viewModel = viewModel()
        advanceUntilIdle()

        settings.state.value = amap.copy(baiduKey = Secret("baidu-ak"), poiProvider = PoiProvider.BAIDU)
        advanceUntilIdle()

        assertEquals(listOf("amap", "baidu"), store.refreshes.map { it.source.id })
        assertEquals(SourceKind.BAIDU, viewModel.uiState.value.source)
    }

    @Test
    fun `the demo banner can be dismissed for good`() = runTest {
        val viewModel = viewModel()
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.showDemoBanner)

        viewModel.dismissDemoBanner()
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.showDemoBanner)
        assertTrue(settings.current.demoBannerDismissed)
    }

    @Test
    fun `a settings write failure is reported`() = runTest {
        val viewModel = viewModel()
        val events = eventsOf(viewModel)
        advanceUntilIdle()
        settings.failure = IOException("disk full")

        viewModel.setPreset(ScoringPreset.FAST_FIRST)
        advanceUntilIdle()

        assertTrue(events.contains(HomeEvent.Message(UiText(R.string.msg_save_failed))))
        assertEquals(ScoringPreset.REPUTATION_FIRST, viewModel.uiState.value.preset)
    }
}
