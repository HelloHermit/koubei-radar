package com.koubeiradar.app.settings

/** The free-text settings, each edited on its own. */
enum class SettingField {
    AMAP_KEY,
    ANTHROPIC_KEY,
    ANTHROPIC_MODEL,
    ANTHROPIC_BASE_URL,
    OPENAI_KEY,
    OPENAI_BASE_URL,
    OPENAI_MODEL,
    OPENAI_VISION_MODEL,
    ;

    val isCredential: Boolean get() = this == AMAP_KEY || this == ANTHROPIC_KEY || this == OPENAI_KEY
}

/** Validates [raw] for [field]; the accepted value is already trimmed and normalised. */
fun checkSetting(field: SettingField, raw: String): Checked = when (field) {
    SettingField.AMAP_KEY, SettingField.ANTHROPIC_KEY, SettingField.OPENAI_KEY -> InputRules.credential(raw)
    SettingField.ANTHROPIC_MODEL, SettingField.OPENAI_MODEL -> InputRules.modelName(raw, required = true)
    SettingField.OPENAI_VISION_MODEL -> InputRules.modelName(raw, required = false)
    SettingField.ANTHROPIC_BASE_URL -> InputRules.baseUrl(raw, required = false)
    SettingField.OPENAI_BASE_URL -> InputRules.baseUrl(raw, required = true)
}

fun AppSettings.with(field: SettingField, value: String): AppSettings = when (field) {
    SettingField.AMAP_KEY -> copy(amapKey = Secret(value))
    SettingField.ANTHROPIC_KEY -> copy(anthropic = anthropic.copy(apiKey = Secret(value)))
    SettingField.ANTHROPIC_MODEL -> copy(anthropic = anthropic.copy(model = value))
    SettingField.ANTHROPIC_BASE_URL -> copy(anthropic = anthropic.copy(baseUrl = value))
    SettingField.OPENAI_KEY -> copy(openAi = openAi.copy(apiKey = Secret(value)))
    SettingField.OPENAI_BASE_URL -> copy(openAi = openAi.copy(baseUrl = value))
    SettingField.OPENAI_MODEL -> copy(openAi = openAi.copy(model = value))
    SettingField.OPENAI_VISION_MODEL -> copy(openAi = openAi.copy(visionModel = value))
}
