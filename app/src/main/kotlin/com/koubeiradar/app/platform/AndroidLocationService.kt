package com.koubeiradar.app.platform

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.util.Consumer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * One-shot location from the system [LocationManager] (no Google Play services, which many devices in China lack).
 *
 * All enabled providers are asked for a fresh fix in parallel and the first answer wins, so a quick network fix is not
 * held up by a GPS that has no sky view. If nothing arrives within [freshFixTimeoutMillis] the newest last-known
 * position that is at most [lastKnownMaxAgeMillis] old is used.
 */
class AndroidLocationService(
    context: Context,
    private val freshFixTimeoutMillis: Long = 8_000,
    private val lastKnownMaxAgeMillis: Long = 60 * 60_000,
    private val currentTimeMillis: () -> Long = System::currentTimeMillis,
) : LocationService {
    private val appContext = context.applicationContext
    private val locationManager = appContext.getSystemService(LocationManager::class.java)

    override fun hasPermission(): Boolean = isGranted(Manifest.permission.ACCESS_FINE_LOCATION) ||
        isGranted(Manifest.permission.ACCESS_COARSE_LOCATION)

    override suspend fun currentLocation(): LocationResult {
        val fine = isGranted(Manifest.permission.ACCESS_FINE_LOCATION)
        if (!fine && !isGranted(Manifest.permission.ACCESS_COARSE_LOCATION)) return LocationResult.PermissionDenied
        val manager = locationManager ?: return LocationResult.Unavailable
        return try {
            val providers = enabledProviders(manager, fine)
            if (providers.isEmpty()) {
                LocationResult.Unavailable
            } else {
                val fix = withTimeoutOrNull(freshFixTimeoutMillis) { freshFix(manager, providers) }
                    ?: lastKnownFix(manager, providers)
                fix?.toLocation() ?: LocationResult.Unavailable
            }
        } catch (e: SecurityException) {
            LocationResult.PermissionDenied
        }
    }

    private fun isGranted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(appContext, permission) == PackageManager.PERMISSION_GRANTED

    private fun enabledProviders(manager: LocationManager, fine: Boolean): List<String> {
        val candidates = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(LocationManager.FUSED_PROVIDER)
            if (fine) add(LocationManager.GPS_PROVIDER)
            add(LocationManager.NETWORK_PROVIDER)
        }
        return candidates.filter {
            try {
                manager.isProviderEnabled(it)
            } catch (e: IllegalArgumentException) {
                false
            }
        }
    }

    private suspend fun freshFix(manager: LocationManager, providers: List<String>): RawFix? = coroutineScope {
        val firstFix = CompletableDeferred<RawFix?>()
        val requests = providers.map { provider -> launch { singleFix(manager, provider)?.let(firstFix::complete) } }
        launch {
            requests.joinAll()
            firstFix.complete(null)
        }
        firstFix.await().also { coroutineContext.cancelChildren() }
    }

    @SuppressLint("MissingPermission")
    private suspend fun singleFix(manager: LocationManager, provider: String): RawFix? =
        suspendCancellableCoroutine { continuation ->
            val signal = CancellationSignal()
            continuation.invokeOnCancellation { signal.cancel() }
            LocationManagerCompat.getCurrentLocation(
                manager,
                provider,
                signal,
                Runnable::run,
                Consumer<Location?> { location -> continuation.resume(location?.toRawFix()) },
            )
        }

    @SuppressLint("MissingPermission")
    private fun lastKnownFix(manager: LocationManager, providers: List<String>): RawFix? =
        providers
            .mapNotNull { provider ->
                try {
                    manager.getLastKnownLocation(provider)?.toRawFix()
                } catch (e: SecurityException) {
                    null
                } catch (e: IllegalArgumentException) {
                    null
                }
            }
            .newestWithin(currentTimeMillis(), lastKnownMaxAgeMillis)

    private fun Location.toRawFix() = RawFix(
        lat = latitude,
        lng = longitude,
        accuracyMeters = if (hasAccuracy()) accuracy else null,
        timeMillis = time,
    )
}
