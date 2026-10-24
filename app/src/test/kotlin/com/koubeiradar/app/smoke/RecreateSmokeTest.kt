package com.koubeiradar.app.smoke

import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.koubeiradar.app.R
import com.koubeiradar.app.ui.format.Formatters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** `ActivityScenario.recreate()` is what a rotation does: destroy and rebuild the activity from its saved state. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = SmokeTestBase.PHONE)
class RecreateSmokeTest : SmokeTestBase() {

    @Test
    fun tabsBannerPresetAndFilterSurviveARecreate() {
        launchAndAwaitHome()

        clickText(string(R.string.home_demo_banner_dismiss))
        waitForGone(string(R.string.home_demo_banner))
        clickText(string(R.string.preset_value))
        waitForSelected(string(R.string.preset_value))
        clickText(string(R.string.criteria_edit_unrestricted))
        waitForText(string(R.string.criteria_sheet_title))
        clickText(string(R.string.criteria_option_min_rating, Formatters.rating(4.7)))
        compose.onNodeWithText(string(R.string.criteria_apply)).performScrollTo().performClick()
        waitForGone(string(R.string.criteria_sheet_title))
        waitForText(string(R.string.criteria_min_rating, Formatters.rating(4.7)))
        val filtered = resultCount()!!
        clickTab("智能体")
        waitForText(string(R.string.agent_welcome_title))

        recreateActivity()

        waitForText(string(R.string.agent_welcome_title))
        assertTrue("still on the agent tab", shows(string(R.string.agent_setup_body)))
        clickTab("推荐")
        awaitHome()
        assertFalse("the dismissed banner stays dismissed", shows(string(R.string.home_demo_banner)))
        waitForSelected(string(R.string.preset_value))
        assertTrue(shows(string(R.string.criteria_min_rating, Formatters.rating(4.7))))
        assertEquals(filtered, resultCount())
    }

    @Test
    fun openOrderSheetSurvivesARecreate() {
        launchAndAwaitHome()
        val name = firstShopName()
        clickText(string(R.string.action_order))
        waitForText(string(R.string.order_explain, name))

        recreateActivity()

        waitForText(string(R.string.order_explain, name))
        assertTrue(shows("美团外卖"))
        clickText("美团外卖")
        waitForText(string(R.string.order_msg_not_installed, name, "美团外卖"))
        assertEquals(name, clipboardText())
    }

    @Test
    fun openCriteriaSheetWithItsDraftSurvivesARecreate() {
        launchAndAwaitHome()
        clickText(string(R.string.criteria_edit_unrestricted))
        waitForText(string(R.string.criteria_sheet_title))
        val option = string(R.string.criteria_option_min_rating, Formatters.rating(4.5))
        clickText(option)
        compose.onNodeWithText(option).assertIsSelected()

        recreateActivity()

        waitForText(string(R.string.criteria_sheet_title))
        compose.onNodeWithText(option).assertIsSelected()
        compose.onNodeWithText(string(R.string.criteria_apply)).performScrollTo().performClick()
        waitForGone(string(R.string.criteria_sheet_title))
        waitForText(string(R.string.criteria_min_rating, Formatters.rating(4.5)))
    }

    @Test
    fun detailScreenSurvivesARecreate() {
        launchAndAwaitHome()
        val name = firstShopName()
        clickText(string(R.string.action_details))
        waitForText(string(R.string.detail_section_breakdown))

        recreateActivity()

        waitForText(string(R.string.detail_section_breakdown))
        assertTrue(shows(name))
        assertFalse(shows(string(R.string.detail_not_found_title)))
    }

    @Test
    fun typedImportTextSurvivesARecreate() {
        launchAndAwaitHome()
        clickTab("导入")
        waitForText(string(R.string.import_howto_title))
        compose.onNode(hasSetTextAction()).performTextInput("巷尾一碗面 4.6分")
        compose.waitForIdle()
        waitForText("巷尾一碗面 4.6分")

        recreateActivity()

        waitForText(string(R.string.import_howto_title))
        waitForText("巷尾一碗面 4.6分")
    }
}
