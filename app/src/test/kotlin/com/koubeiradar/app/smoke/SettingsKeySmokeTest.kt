package com.koubeiradar.app.smoke

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.koubeiradar.app.AppContainer
import com.koubeiradar.app.KoubeiApplication
import com.koubeiradar.app.R
import com.koubeiradar.app.settings.Checked
import com.koubeiradar.app.settings.SaveResult
import com.koubeiradar.app.settings.SettingField
import com.koubeiradar.app.settings.checkSetting
import com.koubeiradar.app.settings.tryUpdate
import com.koubeiradar.app.settings.with
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import javax.crypto.spec.SecretKeySpec

/** The real container with one difference: credentials are sealed with an in-memory key, as Robolectric has no AndroidKeyStore. */
class InMemoryKeyApplication : KoubeiApplication() {
    override fun createContainer(): AppContainer = AppContainer(this) { SecretKeySpec(ByteArray(32) { (it * 7 + 1).toByte() }, "AES") }
}

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = SmokeTestBase.PHONE, application = InMemoryKeyApplication::class)
class SettingsKeySmokeTest : SmokeTestBase() {

    /**
     * What saving a key in the settings does (input check, secret box, DataStore file on disk), with the agent screen open
     * and reacting to it. The key dialog itself cannot be driven here: under Robolectric a text field inside any dialog
     * never lets Compose go idle.
     */
    @Test
    fun anApiKeyIsSealedAtRestAndTurnsTheAgentOn() {
        launchAndAwaitHome()
        clickTab("智能体")
        waitForText(string(R.string.agent_setup_body))
        assertFalse(storedSettings().contains("anthropic_key_sealed"))

        val settings = application.container.settings
        val accepted = checkSetting(SettingField.ANTHROPIC_KEY, KEY) as Checked.Ok
        val result = runBlocking(Dispatchers.IO) { settings.tryUpdate { it.with(SettingField.ANTHROPIC_KEY, accepted.value) } }
        assertEquals(SaveResult.SAVED, result)

        assertTrue("the sealed entry is stored", storedSettings().contains("anthropic_key_sealed"))
        assertFalse("the plain key must not be on disk", storedSettings().contains(KEY))
        assertEquals("the sealed key decrypts again", KEY, runBlocking(Dispatchers.Default) { settings.settings.first() }.anthropic.apiKey.value)

        // The open agent screen reacts: examples and an enabled input instead of the setup hint.
        waitForText(string(R.string.agent_example_1))
        assertFalse(shows(string(R.string.agent_setup_body)))
        assertTrue(shows(string(R.string.agent_input_hint)))
        screenshot("agent-configured")
    }

    private fun storedSettings(): String {
        val file = File(application.filesDir, "datastore/settings.preferences_pb")
        return if (file.exists()) file.readBytes().toString(Charsets.ISO_8859_1) else ""
    }

    private companion object {
        const val KEY = "sk-ant-api03-ABCDEFGHIJKLMNOP"
    }
}
