package com.koubeiradar.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.koubeiradar.agent.llm.LlmProvider
import com.koubeiradar.agent.llm.ProviderPreset
import com.koubeiradar.agent.llm.ProviderPresets
import com.koubeiradar.app.R
import com.koubeiradar.app.meal.MealTestSender
import com.koubeiradar.app.meal.TestOutcome
import com.koubeiradar.app.platform.NotificationPermission
import com.koubeiradar.app.settings.AnthropicSettings
import com.koubeiradar.app.settings.AppSettings
import com.koubeiradar.app.settings.Checked
import com.koubeiradar.app.settings.InputProblem
import com.koubeiradar.app.settings.Meal
import com.koubeiradar.app.settings.PoiProvider
import com.koubeiradar.app.settings.SaveResult
import com.koubeiradar.app.settings.ScoringPreset
import com.koubeiradar.app.settings.Secret
import com.koubeiradar.app.settings.SettingField
import com.koubeiradar.app.settings.SettingsRepository
import com.koubeiradar.app.settings.checkSetting
import com.koubeiradar.app.settings.tryUpdate
import com.koubeiradar.app.settings.with
import com.koubeiradar.app.store.ShopStore
import com.koubeiradar.app.ui.UiText
import com.koubeiradar.core.model.Criteria
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalTime

/**
 * Settings screen. Text fields are validated and normalised here before anything is stored; the screen only ever
 * receives [KeyStatus] for credentials, never the keys themselves.
 */
class SettingsViewModel(
    private val settings: SettingsRepository,
    private val store: ShopStore,
    private val notifications: NotificationPermission,
    private val mealTests: MealTestSender,
) : ViewModel() {

    private val eventChannel = Channel<SettingsEvent>(Channel.BUFFERED)
    val events: Flow<SettingsEvent> = eventChannel.receiveAsFlow()

    private val notificationsAllowed = MutableStateFlow(notifications.isGranted())
    private val sendingTest = MutableStateFlow(false)

    val uiState: StateFlow<SettingsUiState> = combine(settings.settings, notificationsAllowed, sendingTest) { current, allowed, sending ->
        current.toUiState(allowed, sending)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, SettingsUiState())

    /**
     * Validates [raw] and stores it. Returns the problem when the input is rejected (nothing is stored), `null` when it
     * was accepted; the write itself happens asynchronously and reports failures as a message.
     */
    fun submit(field: SettingField, raw: String): InputProblem? {
        val value = when (val checked = checkSetting(field, raw)) {
            is Checked.Rejected -> return checked.problem
            is Checked.Ok -> checked.value
        }
        viewModelScope.launch { save(R.string.settings_msg_saved) { it.with(field, value) } }
        return null
    }

    fun clearCredential(field: SettingField) {
        require(field.isCredential) { "not a credential: $field" }
        viewModelScope.launch { save(R.string.settings_msg_cleared) { it.with(field, "") } }
    }

    fun setUseDemoData(enabled: Boolean) {
        viewModelScope.launch { save { it.copy(useDemoData = enabled) } }
    }

    fun setPoiProvider(provider: PoiProvider) {
        viewModelScope.launch { save { it.copy(poiProvider = provider) } }
    }

    fun setProvider(provider: LlmProvider) {
        viewModelScope.launch { save { it.copy(provider = provider) } }
    }

    /** Fills URL, model and vision model from [preset]; every field stays editable afterwards. */
    fun selectOpenAiPreset(preset: ProviderPreset) {
        viewModelScope.launch {
            save {
                it.copy(
                    openAi = it.openAi.copy(
                        baseUrl = preset.baseUrl,
                        model = preset.suggestedModel,
                        visionModel = preset.suggestedVisionModel.orEmpty(),
                    ),
                )
            }
        }
    }

    /** One of [AnthropicSettings.EFFORT_LEVELS], or blank for the model's default. */
    fun setClaudeEffort(effort: String) {
        require(effort.isEmpty() || effort in AnthropicSettings.EFFORT_LEVELS) { "unknown effort: $effort" }
        viewModelScope.launch { save { it.copy(anthropic = it.anthropic.copy(effort = effort)) } }
    }

    fun setScoringPreset(preset: ScoringPreset) {
        viewModelScope.launch { save { it.copy(preset = preset) } }
    }

    fun setCriteria(criteria: Criteria) {
        viewModelScope.launch { save { it.copy(criteria = criteria) } }
    }

    /** Turning a meal on needs the notification permission; without it the switch stays off and the screen is asked to request it. */
    fun setMealEnabled(meal: Meal, enabled: Boolean) {
        if (enabled && !notifications.isGranted()) {
            eventChannel.trySend(SettingsEvent.RequestNotificationPermission(meal))
            return
        }
        viewModelScope.launch { save { it.copy(meals = it.meals.withEnabled(meal, enabled)) } }
    }

    /** Result of the system permission dialog that [SettingsEvent.RequestNotificationPermission] led to. */
    fun onNotificationPermissionResult(meal: Meal, granted: Boolean) {
        notificationsAllowed.value = granted
        if (granted) {
            viewModelScope.launch { save { it.copy(meals = it.meals.withEnabled(meal, true)) } }
        } else {
            eventChannel.trySend(SettingsEvent.NotificationsBlocked)
        }
    }

    /** The user may have changed the notification settings of the app in the system while this screen was in the background. */
    fun refreshNotificationStatus() {
        notificationsAllowed.value = notifications.isGranted()
    }

    fun setMealTime(meal: Meal, hour: Int, minute: Int) {
        if (hour !in 0..23 || minute !in 0..59) return
        viewModelScope.launch { save { it.copy(meals = it.meals.withTime(meal, LocalTime.of(hour, minute))) } }
    }

    fun setWeekdaysOnly(enabled: Boolean) {
        viewModelScope.launch { save { it.copy(meals = it.meals.copy(weekdaysOnly = enabled)) } }
    }

    /** Runs the daily recommendation once, now, with the current settings. */
    fun sendTestNotification() {
        if (!sendingTest.compareAndSet(expect = false, update = true)) return
        viewModelScope.launch {
            try {
                when (mealTests.sendTest()) {
                    TestOutcome.SENT -> emit(R.string.settings_meals_msg_sent)
                    TestOutcome.NOTHING_TO_RECOMMEND -> emit(R.string.settings_meals_msg_nothing)
                    TestOutcome.NOTIFICATIONS_BLOCKED -> eventChannel.trySend(SettingsEvent.NotificationsBlocked)
                    TestOutcome.FAILED -> emit(R.string.settings_meals_msg_failed)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emit(R.string.settings_meals_msg_failed)
            } finally {
                sendingTest.value = false
            }
        }
    }

    fun clearImportedShops() {
        store.clearImported()
        emit(R.string.settings_msg_imported_cleared)
    }

    fun clearCandidatePool() {
        store.clearAll()
        emit(R.string.settings_msg_pool_cleared)
    }

    fun clearAllCredentials() {
        viewModelScope.launch {
            save(R.string.settings_msg_keys_cleared) {
                it.copy(
                    amapKey = Secret.EMPTY,
                    baiduKey = Secret.EMPTY,
                    anthropic = it.anthropic.copy(apiKey = Secret.EMPTY),
                    openAi = it.openAi.copy(apiKey = Secret.EMPTY),
                )
            }
        }
    }

    private suspend fun save(successMessage: Int? = null, transform: (AppSettings) -> AppSettings) {
        when (settings.tryUpdate(transform)) {
            SaveResult.SAVED -> successMessage?.let(::emit)
            SaveResult.FAILED -> emit(R.string.msg_save_failed)
            SaveResult.SECURE_STORAGE_UNAVAILABLE -> emit(R.string.settings_msg_secure_storage_failed)
        }
    }

    private fun emit(messageRes: Int) {
        eventChannel.trySend(SettingsEvent.Message(UiText(messageRes)))
    }

    private fun AppSettings.toUiState(notificationsAllowed: Boolean, sendingTest: Boolean) = SettingsUiState(
        loaded = true,
        poiProvider = poiProvider,
        amapKey = KeyStatus.of(amapKey),
        baiduKey = KeyStatus.of(baiduKey),
        useDemoData = useDemoData,
        provider = provider,
        anthropicKey = KeyStatus.of(anthropic.apiKey),
        anthropicModel = anthropic.model,
        anthropicBaseUrl = anthropic.baseUrl,
        anthropicEffort = anthropic.effort,
        openAiKey = KeyStatus.of(openAi.apiKey),
        openAiBaseUrl = openAi.baseUrl,
        openAiModel = openAi.model,
        openAiVisionModel = openAi.visionModel,
        openAiPreset = ProviderPresets.openAiCompatible.firstOrNull { it.baseUrl == openAi.baseUrl },
        scoringPreset = preset,
        criteria = criteria,
        meals = meals,
        notificationsAllowed = notificationsAllowed,
        savedPlace = savedPlace,
        sendingTest = sendingTest,
    )
}
