package com.koubeiradar.app.smoke

import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.koubeiradar.app.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Dark theme and a 1.5x system font scale: the two settings most likely to break a layout. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = SmokeTestBase.PHONE)
class AppearanceSmokeTest : SmokeTestBase() {

    @Test
    fun homeInTheDarkTheme() {
        RuntimeEnvironment.setQualifiers("+night")
        launchAndAwaitHome()

        assertTrue("night mode is on", (application.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES)
        screenshot("home-dark")
    }

    @Test
    fun homeAtFontScale15() {
        RuntimeEnvironment.setFontScale(1.5f)
        launchAndAwaitHome()

        assertEquals(1.5f, application.resources.configuration.fontScale, 0.001f)
        listOf("推荐", "智能体", "导入").forEach { assertTrue("bottom navigation label $it", shows(it)) }
        screenshot("home-fontscale-1.5")
    }

    @Test
    fun otherScreensAtFontScale15() {
        RuntimeEnvironment.setFontScale(1.5f)
        launchAndAwaitHome()

        clickText(string(R.string.action_details))
        waitForText(string(R.string.detail_section_breakdown))
        screenshot("detail-fontscale-1.5")
        compose.onNodeWithContentDescription(string(R.string.cd_back)).performClick()
        awaitHome()

        clickTab("智能体")
        waitForText(string(R.string.agent_setup_body))
        screenshot("agent-fontscale-1.5")

        clickTab("导入")
        waitForText(string(R.string.import_not_configured))
        screenshot("import-fontscale-1.5")

        clickTab("推荐")
        awaitHome()
        compose.onNodeWithContentDescription(string(R.string.home_action_settings)).performClick()
        waitForText(string(R.string.settings_section_data))
        screenshot("settings-fontscale-1.5")
    }

}
