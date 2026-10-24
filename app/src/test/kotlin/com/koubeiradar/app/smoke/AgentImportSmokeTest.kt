package com.koubeiradar.app.smoke

import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.koubeiradar.app.R
import com.koubeiradar.app.platform.ImportLimits
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Agent and import without any LLM key: guidance instead of a crash, and what still works without a key. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = SmokeTestBase.PHONE)
class AgentImportSmokeTest : SmokeTestBase() {

    @Test
    fun agentWithoutAKeyShowsTheSetupHintAndADisabledInput() {
        launchAndAwaitHome()
        clickTab("智能体")
        waitForText(string(R.string.agent_welcome_title))

        waitForText(string(R.string.agent_setup_body))
        assertTrue(shows(string(R.string.agent_action_rule_based)))
        assertTrue(shows(string(R.string.action_go_settings)))
        // Buttons inside the notice card: a filled one once rendered its label white on white.
        assertLabelIsReadable(string(R.string.agent_action_rule_based))
        assertLabelIsReadable(string(R.string.action_go_settings))
        assertTrue("disabled input explains why", shows(string(R.string.agent_input_hint_disabled)))
        assertFalse("example prompts need a key", shows(string(R.string.agent_example_1)))
        // A disabled text field does not offer the set-text action at all.
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        compose.onNodeWithContentDescription(string(R.string.agent_action_send)).assertIsNotEnabled()
        screenshot("agent")
    }

    @Test
    fun agentSetupButtonLeadsToTheSettingsAndBackReturnsToTheAgent() {
        launchAndAwaitHome()
        clickTab("智能体")
        waitForText(string(R.string.agent_setup_body))

        clickText(string(R.string.action_go_settings))
        waitForText(string(R.string.settings_section_ai))
        compose.onNodeWithContentDescription(string(R.string.cd_back)).performClickAndIdle()
        waitForText(string(R.string.agent_welcome_title))
        assertTrue(shows(string(R.string.agent_setup_body)))
    }

    @Test
    fun agentRuleBasedRankingWorksWithoutAKey() {
        launchAndAwaitHome()
        clickTab("智能体")
        waitForText(string(R.string.agent_setup_body))

        clickText(string(R.string.agent_action_rule_based))
        // Title and summary are one multi-line text: "规则排序结果（未使用 AI）\n已按口碑评分为你排出前 5 家。"
        waitForTextContaining(string(R.string.agent_rule_based_title))
        waitForAnyText(VERDICT_LABELS)
        assertFalse("not an error", shows(string(R.string.error_agent_unexpected)))
        screenshot("agent-rule-based")
    }

    @Test
    fun importWithoutAKeyShowsTheSetupHintAndKeepsRecogniseDisabled() {
        launchAndAwaitHome()
        clickTab("导入")
        waitForText(string(R.string.import_howto_title))

        waitForText(string(R.string.import_not_configured))
        assertTrue(shows(string(R.string.action_go_settings)))
        assertLabelIsReadable(string(R.string.action_go_settings))
        assertTrue("privacy note", allTexts().any { it.startsWith("截图和文字会发送给你选择的 AI 服务商") })
        assertTrue(shows(string(R.string.import_pick_images, ImportLimits.MAX_IMAGES)))
        compose.onAllNodes(hasText(string(R.string.import_action_recognize)) and hasClickAction())[0].assertIsNotEnabled()
        screenshot("import")
    }

    @Test
    fun importAcceptsTypedTextButStillCannotRecogniseWithoutAKey() {
        launchAndAwaitHome()
        clickTab("导入")
        waitForText(string(R.string.import_not_configured))

        val typed = "1. 鸿运楼老字号烤鸭 4.7分 人均86"
        compose.onNode(hasSetTextAction()).performTextInput(typed)
        compose.waitForIdle()
        waitForText(string(R.string.import_text_counter, typed.length, ImportLimits.MAX_TEXT_CHARS))
        compose.onAllNodes(hasText(string(R.string.import_action_recognize)) and hasClickAction())[0].assertIsNotEnabled()
    }

    private fun SemanticsNodeInteraction.performClickAndIdle() {
        performClick()
        compose.waitForIdle()
    }
}
