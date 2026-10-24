package com.koubeiradar.app.smoke

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.koubeiradar.app.MainActivity
import com.koubeiradar.app.R
import com.koubeiradar.app.service.point
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The very first run of the real app: no API keys, no saved settings, no permission granted. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = SmokeTestBase.PHONE)
class FirstLaunchSmokeTest : SmokeTestBase() {

    @Test
    fun manifestDeclaresTheLauncherEntryAndTheShareTargets() {
        val packageManager = application.packageManager
        val main = MainActivity::class.java.name
        fun handlers(intent: Intent) = packageManager.queryIntentActivities(intent, 0).map { it.activityInfo.name }

        assertTrue(main in handlers(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(application.packageName)))
        assertTrue("text/plain share target", main in handlers(Intent(Intent.ACTION_SEND).setType("text/plain")))
        assertTrue("image share target", main in handlers(Intent(Intent.ACTION_SEND).setType("image/png")))
        assertTrue("multi-image share target", main in handlers(Intent(Intent.ACTION_SEND_MULTIPLE).setType("image/jpeg")))
        assertFalse("other mime types are not accepted", main in handlers(Intent(Intent.ACTION_SEND).setType("application/pdf")))
        assertTrue(packageManager.getActivityInfo(ComponentName(application, MainActivity::class.java), 0).exported)
    }

    @Test
    fun firstLaunchShowsDemoShopsWithAVerdictAndNeverAsksForALocation() {
        assertEquals(PackageManager.PERMISSION_DENIED, ContextCompat.checkSelfPermission(application, Manifest.permission.ACCESS_FINE_LOCATION))
        assertEquals(PackageManager.PERMISSION_DENIED, ContextCompat.checkSelfPermission(application, Manifest.permission.ACCESS_COARSE_LOCATION))

        launch()
        awaitHome()

        // Bottom navigation
        listOf("推荐", "智能体", "导入").forEach { label -> assertTrue("bottom navigation label $label", shows(label)) }

        // Demo-data banner and source chip
        assertTrue("demo banner", shows(string(R.string.home_demo_banner)))
        assertTrue("demo source chip", shows(string(R.string.home_source_demo)))
        assertLabelIsReadable(string(R.string.home_demo_banner_action))
        assertLabelIsReadable(string(R.string.home_demo_banner_dismiss))

        // At least one shop card with a verdict badge, ranked and counted
        val first = firstShopName()
        assertTrue("shop name", first.isNotBlank())
        assertTrue("result count line", (resultCount() ?: 0) >= 1)

        // The location is optional in demo mode: no blocking panel, no permission request, hint shown instead.
        assertFalse(shows(string(R.string.home_needs_location_title)))
        assertFalse(shows(string(R.string.home_action_allow_location)))
        assertTrue(shows(string(R.string.home_location_unknown)))
        scenario!!.onActivity { assertNull("no runtime permission may be requested on first launch", shadowOf(it).lastRequestedPermission) }

        // The real container: real DataStore-backed settings, real pool holding the offline demo catalogue.
        val container = application.container
        assertEquals("DataStoreSettingsRepository", container.settings.javaClass.simpleName)
        assertEquals("PersistingShopStore", container.shopStore.javaClass.simpleName)
        assertNull(container.places.place.value.point)
        assertNotNull(container.shopStore.shops.value.firstOrNull { it.name == "鸿运楼老字号烤鸭(总店)" })

        screenshot("home")
    }
}
