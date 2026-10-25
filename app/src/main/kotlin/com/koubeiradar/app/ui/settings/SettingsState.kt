package com.koubeiradar.app.ui.settings

import com.koubeiradar.agent.llm.LlmProvider
import com.koubeiradar.agent.llm.ProviderPreset
import com.koubeiradar.app.settings.Meal
import com.koubeiradar.app.settings.MealNotificationSettings
import com.koubeiradar.app.settings.PoiProvider
import com.koubeiradar.app.settings.SavedPlace
import com.koubeiradar.app.settings.ScoringPreset
import com.koubeiradar.app.settings.Secret
import com.koubeiradar.app.ui.UiText
import com.koubeiradar.core.model.Criteria

/** What the screen may know about a stored credential: that it exists and how it ends, never the key itself. */
data class KeyStatus(val isSet: Boolean, val tail: String) {
    companion object {
        val NOT_SET = KeyStatus(isSet = false, tail = "")

        fun of(secret: Secret) = KeyStatus(secret.isSet, secret.tail)
    }
}

data class SettingsUiState(
    val loaded: Boolean = false,
    val poiProvider: PoiProvider = PoiProvider.AMAP,
    val amapKey: KeyStatus = KeyStatus.NOT_SET,
    val baiduKey: KeyStatus = KeyStatus.NOT_SET,
    val useDemoData: Boolean = false,
    val provider: LlmProvider = LlmProvider.ANTHROPIC,
    val anthropicKey: KeyStatus = KeyStatus.NOT_SET,
    val anthropicModel: String = "",
    val anthropicBaseUrl: String = "",
    val anthropicEffort: String = "",
    val openAiKey: KeyStatus = KeyStatus.NOT_SET,
    val openAiBaseUrl: String = "",
    val openAiModel: String = "",
    val openAiVisionModel: String = "",
    /** The preset whose URL is currently entered; `null` = a custom endpoint. */
    val openAiPreset: ProviderPreset? = null,
    val scoringPreset: ScoringPreset = ScoringPreset.REPUTATION_FIRST,
    val criteria: Criteria = Criteria(),
    val meals: MealNotificationSettings = MealNotificationSettings(),
    /** Whether the system lets the app post notifications right now; false makes an enabled meal worthless. */
    val notificationsAllowed: Boolean = true,
    val savedPlace: SavedPlace? = null,
    val sendingTest: Boolean = false,
)

sealed interface SettingsEvent {
    data class Message(val text: UiText) : SettingsEvent

    /** Turning [meal] on needs the notification permission: the screen explains why, then asks the system. */
    data class RequestNotificationPermission(val meal: Meal) : SettingsEvent

    /** Notifications cannot be shown (permission denied, or switched off in the system settings). */
    data object NotificationsBlocked : SettingsEvent
}
