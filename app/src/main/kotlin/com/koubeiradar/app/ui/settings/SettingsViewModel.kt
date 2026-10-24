package com.koubeiradar.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.koubeiradar.agent.llm.LlmProvider
import com.koubeiradar.agent.llm.ProviderPreset
import com.koubeiradar.agent.llm.ProviderPresets
import com.koubeiradar.app.R
import com.koubeiradar.app.settings.AnthropicSettings
import com.koubeiradar.app.settings.AppSettings
import com.koubeiradar.app.settings.Checked
import com.koubeiradar.app.settings.InputProblem
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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Settings screen. Text fields are validated and normalised here before anything is stored; the screen only ever
 * receives [KeyStatus] for credentials, never the keys themselves.
 */
class SettingsViewModel(
    private val settings: SettingsRepository,
    private val store: ShopStore,
) : ViewModel() {

    private val eventChannel = Channel<SettingsEvent>(Channel.BUFFERED)
    val events: Flow<SettingsEvent> = eventChannel.receiveAsFlow()

    val uiState: StateFlow<SettingsUiState> = settings.settings
        .map { it.toUiState() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, SettingsUiState())

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

    private fun AppSettings.toUiState() = SettingsUiState(
        loaded = true,
        amapKey = KeyStatus.of(amapKey),
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
    )
}
