package com.koubeiradar.app.smoke

import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.koubeiradar.app.BuildConfig
import com.koubeiradar.app.R
import com.koubeiradar.app.ui.format.Formatters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Walks through every screen and sheet of the real app. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = SmokeTestBase.PHONE)
class NavigationSmokeTest : SmokeTestBase() {

    @Test
    fun bottomTabsSwitchBetweenTheThreeMainScreens() {
        launchAndAwaitHome()

        clickTab("智能体")
        waitForText(string(R.string.agent_welcome_title))
        clickTab("导入")
        waitForText(string(R.string.import_howto_title))
        clickTab("推荐")
        awaitHome()

        listOf("推荐", "智能体", "导入").forEach { assertTrue("bottom navigation label $it", shows(it)) }
        compose.onAllNodes(hasText("推荐") and hasClickAction())[0].assertIsSelected()
    }

    @Test
    fun refreshAndTheScoringPresetsKeepTheListAlive() {
        launchAndAwaitHome()
        val initial = resultCount()

        compose.onNodeWithContentDescription(string(R.string.home_action_refresh)).performClick()
        compose.waitForIdle()
        awaitHome()
        assertEquals(initial, resultCount())

        listOf(R.string.preset_value, R.string.preset_fast, R.string.preset_reputation).forEach { preset ->
            clickText(string(preset))
            waitForSelected(string(preset))
            awaitHome()
            assertTrue("the list is still populated after switching to ${string(preset)}", (resultCount() ?: 0) > 0)
        }
    }

    @Test
    fun settingsOpensFromTheGearAndBackReturnsToTheList() {
        launchAndAwaitHome()

        compose.onNodeWithContentDescription(string(R.string.home_action_settings)).performClick()
        waitForText(string(R.string.settings_section_data))
        // pushed screens have no bottom bar
        waitForGone("推荐")

        listOf(
            R.string.settings_section_ai,
            R.string.settings_section_preferences,
            R.string.settings_section_manage,
            R.string.settings_section_about,
            R.string.settings_privacy_title,
        ).forEach { assertTrue("settings section ${string(it)}", shows(string(it))) }
        val version = string(R.string.settings_version, BuildConfig.VERSION_NAME)
        assertTrue(shows(version))
        screenshot("settings")

        compose.onNodeWithText(version).performScrollTo()
        compose.waitForIdle()
        screenshot("settings-about")

        compose.onNodeWithContentDescription(string(R.string.cd_back)).performClick()
        awaitHome()
        assertTrue(shows("推荐"))
    }

    @Test
    fun shopDetailOpensFromTheListAndBackReturnsToIt() {
        launchAndAwaitHome()
        val name = firstShopName()

        clickText(string(R.string.action_details))
        waitForText(string(R.string.detail_section_breakdown))
        waitForGone("推荐")
        assertTrue("detail screen shows the shop", shows(name))
        assertTrue(shows(string(R.string.detail_title)))
        assertFalse(shows(string(R.string.detail_not_found_title)))
        assertTrue(shows(string(R.string.detail_section_why)))
        assertTrue(shows(string(R.string.detail_section_info)))
        screenshot("detail")

        compose.onNodeWithContentDescription(string(R.string.cd_back)).performClick()
        awaitHome()
        assertTrue(shows(name))
    }

    @Test
    fun criteriaSheetAppliesAFilterAndTheListShrinks() {
        launchAndAwaitHome()
        val before = resultCount()!!

        clickText(string(R.string.criteria_edit_unrestricted))
        waitForText(string(R.string.criteria_sheet_title))

        applyMinRating(4.7)

        val after = resultCount()!!
        assertTrue("filter must remove shops: $before -> $after", after in 1 until before)
        assertTrue(shows(string(R.string.criteria_edit)))
        assertTrue(shows(string(R.string.criteria_min_rating, Formatters.rating(4.7))))
        assertFalse(shows(string(R.string.criteria_edit_unrestricted)))
    }

    @Test
    fun orderSheetOffersTheDeliveryAppsAndCopiesTheShopName() {
        launchAndAwaitHome()
        val name = firstShopName()

        clickText(string(R.string.action_order))
        waitForText(string(R.string.order_explain, name))
        assertTrue(shows("美团外卖"))
        assertTrue(shows("饿了么"))

        clickText("饿了么")
        waitForGone(string(R.string.order_explain, name))
        // Nothing is installed in the sandbox: the name is still copied, and the user is told.
        waitForText(string(R.string.order_msg_not_installed, name, "饿了么"))
        assertEquals(name, clipboardText())
        assertTrue(shows(string(R.string.app_name)))
    }

    /** Opens nothing: applies [rating] in the criteria sheet that is already open. */
    private fun applyMinRating(rating: Double) {
        clickText(string(R.string.criteria_option_min_rating, Formatters.rating(rating)))
        compose.onNodeWithText(string(R.string.criteria_apply)).performScrollTo().performClick()
        compose.waitForIdle()
        waitForGone(string(R.string.criteria_sheet_title))
        waitFor("the filter to be applied") { shows(string(R.string.criteria_min_rating, Formatters.rating(rating))) }
        awaitHome()
    }
}
