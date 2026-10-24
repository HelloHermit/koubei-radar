package com.koubeiradar.app.service

import com.koubeiradar.core.model.GeoPoint
import com.koubeiradar.data.ShopSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Where the user is, as far as the app knows. Coordinates are GCJ-02. */
sealed interface PlaceState {
    data object Unknown : PlaceState

    data class Device(val point: GeoPoint, val accuracyMeters: Int?) : PlaceState

    /** An address the user typed, resolved to [point]. */
    data class Manual(val address: String, val point: GeoPoint) : PlaceState

    /** [permanent]: the system will not show the permission dialog again; only the app settings can grant it. */
    data class PermissionDenied(val permanent: Boolean) : PlaceState

    /** Permission is granted but no position could be determined (providers off, no signal). */
    data object Unavailable : PlaceState
}

val PlaceState.point: GeoPoint?
    get() = when (this) {
        is PlaceState.Device -> point
        is PlaceState.Manual -> point
        PlaceState.Unknown, is PlaceState.PermissionDenied, PlaceState.Unavailable -> null
    }

/**
 * The position that searches should use for [source]: the user's own, or for the offline demo data (which is
 * synthetic anyway) a fixed default so the app is fully usable without any permission.
 */
fun PlaceState.centerFor(source: ShopSource): GeoPoint? =
    point ?: DEMO_CENTER.takeIf { source.id == ShopSourceIds.DEMO }

/** The place shared by the home screen (which finds it) and the agent (which passes it on as context). */
class PlaceStore(initial: PlaceState = PlaceState.Unknown) {
    private val _place = MutableStateFlow(initial)
    val place: StateFlow<PlaceState> = _place.asStateFlow()

    fun set(place: PlaceState) {
        _place.value = place
    }
}
