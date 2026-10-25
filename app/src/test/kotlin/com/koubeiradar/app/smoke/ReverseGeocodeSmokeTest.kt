package com.koubeiradar.app.smoke

import android.Manifest
import android.location.Location
import android.location.LocationManager
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.koubeiradar.app.AppContainer
import com.koubeiradar.app.KoubeiApplication
import com.koubeiradar.app.R
import com.koubeiradar.app.service.DataSources
import com.koubeiradar.app.service.DefaultDataSources
import com.koubeiradar.app.settings.AppSettings
import com.koubeiradar.core.model.GeoPoint
import com.koubeiradar.data.ReverseGeocoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import javax.crypto.spec.SecretKeySpec

/** The real container, except that every position resolves to [LABEL] instead of calling a map provider (there is no network here). */
class ReverseGeocodeApplication : KoubeiApplication() {
    override fun createContainer(): AppContainer = AppContainer(
        this,
        secretKeyProvider = { SecretKeySpec(ByteArray(32) { (it * 7 + 1).toByte() }, "AES") },
        dataSourcesFactory = { http -> LabelledDataSources(DefaultDataSources(http)) },
    )
}

private class LabelledDataSources(private val real: DataSources) : DataSources by real {
    override fun reverseGeocoderFor(settings: AppSettings): ReverseGeocoder = object : ReverseGeocoder {
        override suspend fun reverseGeocode(point: GeoPoint): String = ReverseGeocodeSmokeTest.LABEL
    }
}

/** The device-location row of the home screen once the address of the fix is known. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = SmokeTestBase.PHONE, application = ReverseGeocodeApplication::class)
class ReverseGeocodeSmokeTest : SmokeTestBase() {

    @Test
    fun theLocationRowShowsTheAddressOfTheDeviceFixAndItIsRemembered() {
        provideAFix()
        // With the permission already granted the app locates on its own at start.
        shadowOf(application).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        launchAndAwaitHome()

        waitForText(LABEL)
        waitForText(string(R.string.home_location_accuracy, 25))
        assertFalse("the label replaces the plain text", shows(string(R.string.home_location_device)))
        waitFor("the label to be stored with the remembered place") { storedPlace()?.address == LABEL }
        assertTrue("still a device place, not a typed address", storedPlace()?.fromDevice == true)
        screenshot("home-location-label")
    }

    private fun storedPlace() = runBlocking(Dispatchers.Default) { application.container.settings.settings.first() }.savedPlace

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

    companion object {
        const val LABEL = "朝阳区望京街道 · 望京SOHO附近"
    }
}
