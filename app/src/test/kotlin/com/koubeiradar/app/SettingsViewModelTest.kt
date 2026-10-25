package com.koubeiradar.app

import com.koubeiradar.agent.llm.LlmProvider
import com.koubeiradar.agent.llm.ProviderPresets
import com.koubeiradar.app.meal.TestOutcome
import com.koubeiradar.app.settings.AppSettings
import com.koubeiradar.app.settings.InputProblem
import com.koubeiradar.app.settings.Meal
import com.koubeiradar.app.settings.MealNotificationSettings
import com.koubeiradar.app.settings.PoiProvider
import com.koubeiradar.app.settings.SavedPlace
import com.koubeiradar.app.settings.ScoringPreset
import com.koubeiradar.app.settings.Secret
import com.koubeiradar.app.settings.SettingField
import com.koubeiradar.app.ui.UiText
import com.koubeiradar.app.ui.settings.KeyStatus
import com.koubeiradar.app.ui.settings.SettingsEvent
import com.koubeiradar.app.ui.settings.SettingsViewModel
import com.koubeiradar.core.model.Criteria
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import java.io.IOException
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
    @get:Rule
    val main = MainDispatcherRule(StandardTestDispatcher())

    private val settings = FakeSettings()
    private val store = FakeShopStore()
    private val permission = FakeNotificationPermission()
    private val mealTests = FakeMealTests()

    private fun TestScope.viewModel() = SettingsViewModel(settings, store, permission, mealTests)

    private fun TestScope.eventsOf(viewModel: SettingsViewModel): MutableList<SettingsEvent> {
        val events = mutableListOf<SettingsEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.events.collect { events += it } }
        return events
    }

    @Test
    fun `the baidu key is stored apart from the amap key and only its status is exposed`() = runTest {
        val viewModel = viewModel()

        assertNull(viewModel.submit(SettingField.BAIDU_KEY, "  baiduak1234567890abcd \n"))
        advanceUntilIdle()

        assertEquals("baiduak1234567890abcd", settings.current.baiduKey.value)
        assertFalse(settings.current.amapKey.isSet)
        assertEquals(KeyStatus(isSet = true, tail = "abcd"), viewModel.uiState.value.baiduKey)
        assertEquals(KeyStatus.NOT_SET, viewModel.uiState.value.amapKey)
        assertEquals(InputProblem.CONTAINS_WHITESPACE, viewModel.submit(SettingField.BAIDU_KEY, "a b"))
        assertEquals(InputProblem.EMPTY, viewModel.submit(SettingField.BAIDU_KEY, "  "))

        viewModel.clearCredential(SettingField.BAIDU_KEY)
        advanceUntilIdle()
        assertFalse(settings.current.baiduKey.isSet)
    }

    @Test
    fun `choosing the poi provider is saved and shown`() = runTest {
        val viewModel = viewModel()
        advanceUntilIdle()
        assertEquals(PoiProvider.AMAP, viewModel.uiState.value.poiProvider)

        viewModel.setPoiProvider(PoiProvider.BAIDU)
        advanceUntilIdle()

        assertEquals(PoiProvider.BAIDU, settings.current.poiProvider)
        assertEquals(PoiProvider.BAIDU, viewModel.uiState.value.poiProvider)
    }

    @Test
    fun `clearing all credentials also removes the baidu key`() = runTest {
        settings.state.value = AppSettings(amapKey = Secret("a-key-1234567890"), baiduKey = Secret("b-key-1234567890"), poiProvider = PoiProvider.BAIDU)
        val viewModel = viewModel()

        viewModel.clearAllCredentials()
        advanceUntilIdle()

        assertFalse(settings.current.baiduKey.isSet)
        assertFalse(settings.current.amapKey.isSet)
        assertEquals(PoiProvider.BAIDU, settings.current.poiProvider, "the choice itself is not a credential")
    }

    @Test
    fun `accepted input is trimmed before it is stored`() = runTest {
        val viewModel = viewModel()

        assertNull(viewModel.submit(SettingField.AMAP_KEY, "  abcd1234efgh5678 \n"))
        assertNull(viewModel.submit(SettingField.OPENAI_MODEL, "  qwen-plus "))
        assertNull(viewModel.submit(SettingField.OPENAI_BASE_URL, " https://api.example.com/v1/ "))
        advanceUntilIdle()

        assertEquals("abcd1234efgh5678", settings.current.amapKey.value)
        assertEquals("qwen-plus", settings.current.openAi.model)
        assertEquals("https://api.example.com/v1", settings.current.openAi.baseUrl)
    }

    @Test
    fun `rejected input stores nothing and says why`() = runTest {
        val viewModel = viewModel()
        val before = settings.current

        assertEquals(InputProblem.NOT_HTTPS, viewModel.submit(SettingField.OPENAI_BASE_URL, "http://api.example.com"))
        assertEquals(InputProblem.NOT_HTTPS, viewModel.submit(SettingField.ANTHROPIC_BASE_URL, "http://gateway.example.com"))
        assertEquals(InputProblem.EMPTY, viewModel.submit(SettingField.OPENAI_MODEL, "   "))
        assertEquals(InputProblem.CONTAINS_WHITESPACE, viewModel.submit(SettingField.OPENAI_KEY, "sk a b"))
        assertEquals(InputProblem.NOT_PLAIN_ASCII, viewModel.submit(SettingField.ANTHROPIC_KEY, "密钥"))
        advanceUntilIdle()

        assertEquals(before, settings.current)
    }

    @Test
    fun `optional fields can be cleared`() = runTest {
        val viewModel = viewModel()
        assertNull(viewModel.submit(SettingField.ANTHROPIC_BASE_URL, "https://gateway.example.com"))
        advanceUntilIdle()
        assertNull(viewModel.submit(SettingField.ANTHROPIC_BASE_URL, ""))
        assertNull(viewModel.submit(SettingField.OPENAI_VISION_MODEL, ""))
        advanceUntilIdle()
        assertEquals("", settings.current.anthropic.baseUrl)
        assertEquals("", settings.current.openAi.visionModel)
    }

    @Test
    fun `the screen only ever sees that a key exists and how it ends`() = runTest {
        val viewModel = viewModel()
        val key = "sk-ant-api03-ABCDEFGHIJKLMNOP"

        assertNull(viewModel.submit(SettingField.ANTHROPIC_KEY, key))
        assertNull(viewModel.submit(SettingField.AMAP_KEY, "short"))
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(KeyStatus(isSet = true, tail = "MNOP"), state.anthropicKey)
        assertEquals(KeyStatus(isSet = true, tail = ""), state.amapKey, "short keys reveal nothing")
        assertEquals(KeyStatus.NOT_SET, state.openAiKey)
        assertFalse("ABCDEFGH" in state.toString())
        assertEquals(key, settings.current.anthropic.apiKey.value)
    }

    @Test
    fun `a credential can be cleared on its own, or all at once`() = runTest {
        settings.state.value = AppSettings(amapKey = Secret("a-key-1234567890"))
            .let { it.copy(anthropic = it.anthropic.copy(apiKey = Secret("c-key-1234567890")), openAi = it.openAi.copy(apiKey = Secret("o-key-1234567890"))) }
        val viewModel = viewModel()
        val events = eventsOf(viewModel)

        viewModel.clearCredential(SettingField.AMAP_KEY)
        advanceUntilIdle()
        assertFalse(settings.current.amapKey.isSet)
        assertTrue(settings.current.anthropic.apiKey.isSet)
        assertEquals(SettingsEvent.Message(UiText(R.string.settings_msg_cleared)), events.last())

        viewModel.clearAllCredentials()
        advanceUntilIdle()
        assertFalse(settings.current.anthropic.apiKey.isSet || settings.current.openAi.apiKey.isSet)
        assertEquals(SettingsEvent.Message(UiText(R.string.settings_msg_keys_cleared)), events.last())

        assertFailsWith<IllegalArgumentException> { viewModel.clearCredential(SettingField.OPENAI_MODEL) }
    }

    @Test
    fun `a provider preset fills url, model and vision model, which stay editable`() = runTest {
        val viewModel = viewModel()
        val qwen = ProviderPresets.openAiCompatible.first { it.suggestedVisionModel != null }

        viewModel.selectOpenAiPreset(qwen)
        advanceUntilIdle()

        assertEquals(qwen.baseUrl, settings.current.openAi.baseUrl)
        assertEquals(qwen.suggestedModel, settings.current.openAi.model)
        assertEquals(qwen.suggestedVisionModel, settings.current.openAi.visionModel)
        assertEquals(qwen, viewModel.uiState.value.openAiPreset)

        assertNull(viewModel.submit(SettingField.OPENAI_MODEL, "my-own-model"))
        advanceUntilIdle()
        assertEquals(qwen, viewModel.uiState.value.openAiPreset, "the preset is recognised by its URL")

        assertNull(viewModel.submit(SettingField.OPENAI_BASE_URL, "https://llm.example.com/v1"))
        advanceUntilIdle()
        assertNull(viewModel.uiState.value.openAiPreset, "a custom endpoint is not a preset")
    }

    @Test
    fun `simple preferences are persisted`() = runTest {
        val viewModel = viewModel()

        viewModel.setProvider(LlmProvider.OPENAI_COMPATIBLE)
        viewModel.setUseDemoData(true)
        viewModel.setScoringPreset(ScoringPreset.FAST_FIRST)
        viewModel.setCriteria(Criteria(minRating = 4.4))
        viewModel.setClaudeEffort("high")
        advanceUntilIdle()

        val current = settings.current
        assertEquals(LlmProvider.OPENAI_COMPATIBLE, current.provider)
        assertTrue(current.useDemoData)
        assertEquals(ScoringPreset.FAST_FIRST, current.preset)
        assertEquals(Criteria(minRating = 4.4), current.criteria)
        assertEquals("high", current.anthropic.effort)
        assertEquals(current.criteria, viewModel.uiState.value.criteria)

        viewModel.setClaudeEffort("")
        advanceUntilIdle()
        assertEquals("", settings.current.anthropic.effort)
        assertFailsWith<IllegalArgumentException> { viewModel.setClaudeEffort("turbo") }
    }

    @Test
    fun `clearing data goes through the pool and is confirmed`() = runTest {
        val viewModel = viewModel()
        val events = eventsOf(viewModel)

        viewModel.clearImportedShops()
        assertEquals(1, store.clearedImported)
        assertEquals(SettingsEvent.Message(UiText(R.string.settings_msg_imported_cleared)), events.last())

        viewModel.clearCandidatePool()
        assertEquals(1, store.clearedAll)
        assertEquals(SettingsEvent.Message(UiText(R.string.settings_msg_pool_cleared)), events.last())
    }

    @Test
    fun `an unusable key store is reported, not hidden`() = runTest {
        val viewModel = viewModel()
        val events = eventsOf(viewModel)
        settings.failure = secureStorageFailure

        viewModel.submit(SettingField.AMAP_KEY, "abcdefgh12345678")
        advanceUntilIdle()

        assertEquals(SettingsEvent.Message(UiText(R.string.settings_msg_secure_storage_failed)), events.last())
        assertFalse(settings.current.amapKey.isSet)

        settings.failure = java.io.IOException("disk full")
        viewModel.setUseDemoData(true)
        advanceUntilIdle()
        assertEquals(SettingsEvent.Message(UiText(R.string.msg_save_failed)), events.last())
    }

    @Test
    fun `the state is empty until the settings are read`() = runTest {
        val viewModel = viewModel()
        assertFalse(viewModel.uiState.value.loaded)
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.loaded)
    }

    @Test
    fun `the meal section starts off and shows what the settings hold`() = runTest {
        val viewModel = viewModel()
        advanceUntilIdle()
        assertEquals(MealNotificationSettings(), viewModel.uiState.value.meals)
        assertTrue(viewModel.uiState.value.notificationsAllowed)

        val place = SavedPlace("望京SOHO", somewhereInTown)
        settings.state.value = settings.current.copy(savedPlace = place, meals = MealNotificationSettings(lunchEnabled = true))
        advanceUntilIdle()
        assertEquals(place, viewModel.uiState.value.savedPlace)
        assertTrue(viewModel.uiState.value.meals.lunchEnabled)
    }

    @Test
    fun `a meal is turned on at once when notifications are allowed`() = runTest {
        val viewModel = viewModel()
        val events = eventsOf(viewModel)

        viewModel.setMealEnabled(Meal.DINNER, true)
        advanceUntilIdle()

        assertTrue(settings.current.meals.dinnerEnabled)
        assertFalse(settings.current.meals.lunchEnabled)
        assertTrue(events.isEmpty())
    }

    @Test
    fun `turning a meal on without permission asks for it first and stores nothing`() = runTest {
        permission.granted = false
        val viewModel = viewModel()
        val events = eventsOf(viewModel)

        viewModel.setMealEnabled(Meal.LUNCH, true)
        advanceUntilIdle()

        assertEquals(listOf<SettingsEvent>(SettingsEvent.RequestNotificationPermission(Meal.LUNCH)), events)
        assertFalse(settings.current.meals.lunchEnabled)
    }

    @Test
    fun `a granted permission turns the meal on`() = runTest {
        permission.granted = false
        val viewModel = viewModel()
        val events = eventsOf(viewModel)

        viewModel.onNotificationPermissionResult(Meal.LUNCH, granted = true)
        advanceUntilIdle()

        assertTrue(settings.current.meals.lunchEnabled)
        assertTrue(viewModel.uiState.value.notificationsAllowed)
        assertTrue(events.isEmpty())
    }

    @Test
    fun `a denied permission keeps the meal off and offers the system settings`() = runTest {
        permission.granted = false
        val viewModel = viewModel()
        val events = eventsOf(viewModel)

        viewModel.onNotificationPermissionResult(Meal.DINNER, granted = false)
        advanceUntilIdle()

        assertEquals(listOf<SettingsEvent>(SettingsEvent.NotificationsBlocked), events)
        assertFalse(settings.current.meals.dinnerEnabled)
        assertFalse(viewModel.uiState.value.notificationsAllowed)
    }

    @Test
    fun `turning a meal off never needs the permission`() = runTest {
        settings.state.value = AppSettings(meals = MealNotificationSettings(lunchEnabled = true, dinnerEnabled = true))
        permission.granted = false
        val viewModel = viewModel()
        val events = eventsOf(viewModel)

        viewModel.setMealEnabled(Meal.LUNCH, false)
        advanceUntilIdle()

        assertFalse(settings.current.meals.lunchEnabled)
        assertTrue(settings.current.meals.dinnerEnabled)
        assertTrue(events.isEmpty())
    }

    @Test
    fun `the screen learns when the user changed the notification settings of the app`() = runTest {
        val viewModel = viewModel()
        advanceUntilIdle()

        permission.granted = false
        viewModel.refreshNotificationStatus()
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.notificationsAllowed)

        permission.granted = true
        viewModel.refreshNotificationStatus()
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.notificationsAllowed)
    }

    @Test
    fun `meal times are stored per meal in whole minutes`() = runTest {
        val viewModel = viewModel()

        viewModel.setMealTime(Meal.LUNCH, 12, 5)
        viewModel.setMealTime(Meal.DINNER, 0, 0)
        advanceUntilIdle()

        assertEquals(LocalTime.of(12, 5), settings.current.meals.lunchTime)
        assertEquals(LocalTime.MIDNIGHT, settings.current.meals.dinnerTime)
    }

    @Test
    fun `a time outside the day is ignored`() = runTest {
        val viewModel = viewModel()

        listOf(24 to 0, 12 to 60, -1 to 30, 12 to -1).forEach { (hour, minute) -> viewModel.setMealTime(Meal.LUNCH, hour, minute) }
        advanceUntilIdle()

        assertEquals(MealNotificationSettings(), settings.current.meals)
    }

    @Test
    fun `weekdays only is stored`() = runTest {
        val viewModel = viewModel()

        viewModel.setWeekdaysOnly(true)
        advanceUntilIdle()

        assertTrue(settings.current.meals.weekdaysOnly)
    }

    @Test
    fun `a meal setting that cannot be written says so`() = runTest {
        val viewModel = viewModel()
        val events = eventsOf(viewModel)
        settings.failure = IOException("disk full")

        viewModel.setMealEnabled(Meal.LUNCH, true)
        advanceUntilIdle()

        assertEquals(listOf<SettingsEvent>(SettingsEvent.Message(UiText(R.string.msg_save_failed))), events)
        assertFalse(settings.current.meals.lunchEnabled)
    }

    @Test
    fun `the test notification reports each outcome`() = runTest {
        val viewModel = viewModel()
        val events = eventsOf(viewModel)

        mapOf(
            TestOutcome.SENT to SettingsEvent.Message(UiText(R.string.settings_meals_msg_sent)),
            TestOutcome.NOTHING_TO_RECOMMEND to SettingsEvent.Message(UiText(R.string.settings_meals_msg_nothing)),
            TestOutcome.FAILED to SettingsEvent.Message(UiText(R.string.settings_meals_msg_failed)),
            TestOutcome.NOTIFICATIONS_BLOCKED to SettingsEvent.NotificationsBlocked,
        ).forEach { (outcome, expected) ->
            events.clear()
            mealTests.outcome = outcome
            viewModel.sendTestNotification()
            advanceUntilIdle()
            assertEquals(listOf(expected), events, "$outcome")
        }
        assertEquals(4, mealTests.calls)
    }

    @Test
    fun `a test notification that crashes is reported as a failure`() = runTest {
        val viewModel = viewModel()
        val events = eventsOf(viewModel)
        mealTests.failure = IllegalStateException("boom")

        viewModel.sendTestNotification()
        advanceUntilIdle()

        assertEquals(listOf<SettingsEvent>(SettingsEvent.Message(UiText(R.string.settings_meals_msg_failed))), events)
        assertFalse(viewModel.uiState.value.sendingTest)
    }

    @Test
    fun `a second tap while a test notification is being prepared does nothing`() = runTest {
        val viewModel = viewModel()
        mealTests.gate = CompletableDeferred()

        viewModel.sendTestNotification()
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.sendingTest)
        viewModel.sendTestNotification()
        advanceUntilIdle()
        assertEquals(1, mealTests.calls)

        mealTests.gate?.complete(Unit)
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.sendingTest)
        viewModel.sendTestNotification()
        advanceUntilIdle()
        assertEquals(2, mealTests.calls)
    }
}
