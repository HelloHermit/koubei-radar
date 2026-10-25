package com.koubeiradar.app.settings

import com.koubeiradar.agent.llm.LlmConfig
import com.koubeiradar.agent.llm.LlmProvider
import com.koubeiradar.agent.llm.ProviderPresets
import com.koubeiradar.core.model.Criteria
import com.koubeiradar.core.model.GeoPoint
import com.koubeiradar.core.model.ScoringConfig
import kotlinx.serialization.Serializable

/** A credential that never reveals itself through `toString`, so it cannot leak into logs by accident. */
@JvmInline
value class Secret(val value: String) {
    val isSet: Boolean get() = value.isNotEmpty()

    /** Last characters for "已保存（尾号 …）" hints; short secrets reveal nothing. */
    val tail: String get() = if (value.length >= MIN_LENGTH_FOR_TAIL) value.takeLast(TAIL_LENGTH) else ""

    override fun toString(): String = "Secret(***)"

    companion object {
        val EMPTY = Secret("")
        private const val TAIL_LENGTH = 4
        private const val MIN_LENGTH_FOR_TAIL = 12
    }
}

/** Where nearby restaurants come from when real data is used. Each provider has its own key. */
enum class PoiProvider { AMAP, BAIDU }

enum class ScoringPreset(val config: ScoringConfig) {
    REPUTATION_FIRST(ScoringConfig.REPUTATION_FIRST),
    VALUE_FIRST(ScoringConfig.VALUE_FIRST),
    FAST_FIRST(ScoringConfig.FAST_FIRST),
}

/**
 * The last place the user established in the app, remembered so a cold start does not ask again and so the daily
 * recommendation knows where to look (it never reads the location itself). GCJ-02. A typed address keeps its text;
 * a device position is [fromDevice] and has a blank address until its reverse-geocoded label is known (and always, if that
 * lookup is unavailable or fails, as in everything stored before the label existed).
 */
@Serializable
data class SavedPlace(val address: String, val point: GeoPoint, val fromDevice: Boolean = false)

data class AnthropicSettings(
    val apiKey: Secret = Secret.EMPTY,
    val model: String = LlmConfig.DEFAULT_CLAUDE_MODEL,
    /** Optional gateway / proxy; blank = the official endpoint. */
    val baseUrl: String = "",
    /** One of [EFFORT_LEVELS], or blank for the model's default. */
    val effort: String = "",
) {
    companion object {
        val EFFORT_LEVELS = listOf("low", "medium", "high", "xhigh", "max")
    }
}

data class OpenAiSettings(
    val apiKey: Secret = Secret.EMPTY,
    val baseUrl: String = DEFAULT_PRESET?.baseUrl.orEmpty(),
    val model: String = DEFAULT_PRESET?.suggestedModel.orEmpty(),
    val visionModel: String = DEFAULT_PRESET?.suggestedVisionModel.orEmpty(),
) {
    private companion object {
        val DEFAULT_PRESET = ProviderPresets.openAiCompatible.firstOrNull()
    }
}

data class AppSettings(
    val amapKey: Secret = Secret.EMPTY,
    val baiduKey: Secret = Secret.EMPTY,
    val poiProvider: PoiProvider = PoiProvider.AMAP,
    /** Forces the offline demo data even when a key for the selected [poiProvider] is present. */
    val useDemoData: Boolean = false,
    val provider: LlmProvider = LlmProvider.ANTHROPIC,
    val anthropic: AnthropicSettings = AnthropicSettings(),
    val openAi: OpenAiSettings = OpenAiSettings(),
    /** The user's default filter: shown on the home screen and handed to the agent as its starting point. */
    val criteria: Criteria = Criteria(),
    val preset: ScoringPreset = ScoringPreset.REPUTATION_FIRST,
    val demoBannerDismissed: Boolean = false,
    val savedPlace: SavedPlace? = null,
    val meals: MealNotificationSettings = MealNotificationSettings(),
) {
    /** The key of the selected [poiProvider]. */
    val poiKey: Secret get() = when (poiProvider) {
        PoiProvider.AMAP -> amapKey
        PoiProvider.BAIDU -> baiduKey
    }

    /** Real data is used only with a key for the selected provider and while the demo switch is off. */
    val usesRealData: Boolean get() = poiKey.isSet && !useDemoData

    /** The provider whose data is shown right now; `null` while the offline demo data is. */
    val activePoi: PoiProvider? get() = poiProvider.takeIf { usesRealData }

    /** The LLM configuration to use, or `null` while the selected provider is not fully set up. */
    fun llmConfig(): LlmConfig? = when (provider) {
        LlmProvider.ANTHROPIC -> anthropic.takeIf { it.apiKey.isSet }?.let {
            LlmConfig(
                provider = LlmProvider.ANTHROPIC,
                apiKey = it.apiKey.value,
                model = it.model.ifBlank { LlmConfig.DEFAULT_CLAUDE_MODEL },
                baseUrl = it.baseUrl.ifBlank { null },
                effort = it.effort.ifBlank { null },
            )
        }

        LlmProvider.OPENAI_COMPATIBLE -> openAi
            .takeIf { it.apiKey.isSet && it.baseUrl.isNotBlank() && it.model.isNotBlank() }
            ?.let {
                LlmConfig(
                    provider = LlmProvider.OPENAI_COMPATIBLE,
                    apiKey = it.apiKey.value,
                    model = it.model,
                    baseUrl = it.baseUrl,
                    visionModel = it.visionModel.ifBlank { null },
                    maxTokens = OPENAI_COMPATIBLE_MAX_TOKENS,
                )
            }
    }

    private companion object {
        // GLM rejects max_tokens above 4095 and DeepSeek/Qwen above 8192; the Claude default (16000) would fail on all of them.
        const val OPENAI_COMPATIBLE_MAX_TOKENS = 4000
    }
}
