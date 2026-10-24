package com.koubeiradar.app.smoke

import android.Manifest
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.koubeiradar.app.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowActivity

/** The runtime-permission and device-location flows of the home screen, with the real location service. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = SmokeTestBase.PHONE)
class LocationSmokeTest : SmokeTestBase() {

    @Test
    fun locationRowOpensTheAddressDialogThatExplainsDemoMode() {
        launchAndAwaitHome()

        clickText(string(R.string.home_location_unknown))
        waitForText(string(R.string.address_title))
        assertTrue(shows(string(R.string.address_use_current)))
        // Demo data has no geocoder: the dialog says so and offers the settings instead of a dead end.
        assertTrue(shows(string(R.string.home_address_needs_key)))
        assertTrue(shows(string(R.string.action_go_settings)))

        clickText(string(R.string.action_cancel))
        waitForGone(string(R.string.address_title))
        awaitHome()
    }

    @Test
    fun deniedPermissionKeepsTheDemoListAndExplainsTheNextStep() {
        launchAndAwaitHome()

        askForTheCurrentLocation()
        deliverPermissionResult(granted = false)

        waitForText(string(R.string.home_msg_permission_denied))
        awaitHome()
        assertTrue("still unlocated, still usable", shows(string(R.string.home_location_unknown)))
        screenshot("home-permission-denied")
    }

    @Test
    fun grantedPermissionShowsTheDeviceLocationAndKeepsTheList() {
        provideAFix()
        launchAndAwaitHome()

        askForTheCurrentLocation()
        deliverPermissionResult(granted = true)

        waitForText(string(R.string.home_location_device))
        waitForText(string(R.string.home_location_accuracy, 25))
        awaitHome()
        assertEquals(PackageManager.PERMISSION_GRANTED, application.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION))
    }

    private fun askForTheCurrentLocation() {
        clickText(string(R.string.home_location_unknown))
        waitForText(string(R.string.address_title))
        clickText(string(R.string.address_use_current))
        waitFor("the runtime permission request") { lastPermissionRequest() != null }
    }

    private fun lastPermissionRequest(): ShadowActivity.PermissionsRequest? {
        var request: ShadowActivity.PermissionsRequest? = null
        scenario!!.onActivity { request = shadowOf(it).lastRequestedPermission }
        return request
    }

    /** What the system does after its permission dialog: grants (or not) and calls back into the activity. */
    @Suppress("DEPRECATION")
    private fun deliverPermissionResult(granted: Boolean) {
        val request = lastPermissionRequest() ?: throw AssertionError("the app never asked for a permission")
        assertTrue(request.requestedPermissions.toList().containsAll(listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)))
        if (granted) shadowOf(application).grantPermissions(*request.requestedPermissions)
        val results = IntArray(request.requestedPermissions.size) { if (granted) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED }
        scenario!!.onActivity { it.onRequestPermissionsResult(request.requestCode, request.requestedPermissions, results) }
        compose.waitForIdle()
    }

    private fun provideAFix() {
        val shadow = shadowOf(application.getSystemService(LocationManager::class.java))
        shadow.setProviderEnabled(LocationManager.NETWORK_PROVIDER, true)
        val fix = Location(LocationManager.NETWORK_PROVIDER).apply {
            latitude = 39.9087
            longitude = 116.3975
            accuracy = 25f
            time = System.currentTimeMillis()
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
        }
        shadow.setLastKnownLocation(LocationManager.NETWORK_PROVIDER, fix)
    }
}
