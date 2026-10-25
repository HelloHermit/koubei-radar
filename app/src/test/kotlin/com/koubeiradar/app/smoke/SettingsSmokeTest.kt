package com.koubeiradar.app.smoke

import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.koubeiradar.app.R
import com.koubeiradar.app.ui.components.MascotMood
import com.koubeiradar.app.ui.format.Formatters
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Settings flows that touch real storage: DataStore and the shared shop pool. Typing into the key dialog is not tested
 * here: under Robolectric a text field inside any dialog (even a stock AlertDialog) never lets Compose go idle.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = SmokeTestBase.PHONE)
class SettingsSmokeTest : SmokeTestBase() {

    @Test
    fun clearingTheCandidatePoolShowsTheEmptyStateAndReloadingRestoresTheList() {
        launchAndAwaitHome()
        openSettings()

        clickText(string(R.string.settings_clear_pool))
        waitForText(string(R.string.settings_clear_pool_confirm))
        clickText(string(R.string.action_confirm))
        waitForText(string(R.string.settings_msg_pool_cleared))

        compose.onNodeWithContentDescription(string(R.string.cd_back)).performClick()
        waitForText(string(R.string.home_no_shops_title))
        assertTrue("a quiet whale for an empty pool", showsMascot(MascotMood.SLEEPY))
        screenshot("home-empty-pool")

        clickText(string(R.string.home_action_reload))
        awaitHome()
    }

    @Test
    fun confirmDialogsCanBeCancelledWithoutSideEffects() {
        launchAndAwaitHome()
        openSettings()

        clickText(string(R.string.settings_clear_keys))
        waitForText(string(R.string.settings_clear_keys_confirm))
        clickText(string(R.string.action_cancel))
        waitForGone(string(R.string.settings_clear_keys_confirm))
        assertFalse(shows(string(R.string.settings_msg_keys_cleared)))
    }

    @Test
    fun defaultCriteriaSetInTheSettingsApplyOnTheHomeScreen() {
        launchAndAwaitHome()
        val before = resultCount()!!
        openSettings()

        clickText(string(R.string.settings_default_criteria))
        waitForText(string(R.string.criteria_sheet_title))
        clickText(string(R.string.criteria_option_min_rating, Formatters.rating(4.7)))
        compose.onNode(androidx.compose.ui.test.hasText(string(R.string.criteria_apply))).performScrollTo().performClick()
        waitForGone(string(R.string.criteria_sheet_title))
        waitFor("the summary to show the filter") { shows(string(R.string.criteria_min_rating, Formatters.rating(4.7))) }

        compose.onNodeWithContentDescription(string(R.string.cd_back)).performClick()
        awaitHome()
        assertTrue(shows(string(R.string.criteria_min_rating, Formatters.rating(4.7))))
        assertTrue(resultCount()!! < before)
    }

    @Test
    fun theProviderSelectorOffersBothMapSourcesAndSwitchesTheKeyRow() {
        launchAndAwaitHome()
        openSettings()

        assertTrue(shows(string(R.string.settings_poi_provider)))
        assertTrue(shows(string(R.string.settings_amap_key)))
        assertFalse(shows(string(R.string.settings_baidu_key)))

        clickText(string(R.string.settings_poi_provider))
        waitForText(string(R.string.poi_provider_baidu))
        assertTrue(shows(string(R.string.poi_provider_amap)))
        clickText(string(R.string.poi_provider_baidu))

        waitForText(string(R.string.settings_baidu_key))
        waitForGone(string(R.string.settings_amap_key))
        assertTrue(shows(string(R.string.settings_baidu_hint)))
    }

    private fun openSettings() {
        compose.onNodeWithContentDescription(string(R.string.home_action_settings)).performClick()
        waitForText(string(R.string.settings_section_data))
    }
}
