package com.koubeiradar.app.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.koubeiradar.agent.llm.LlmProvider
import com.koubeiradar.core.model.Criteria
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.IOException
import java.time.LocalTime

interface SettingsRepository {
    val settings: Flow<AppSettings>

    /**
     * Atomically applies [transform] to the stored settings.
     * @throws SecretStorageException when a changed credential cannot be encrypted (nothing is written then).
     */
    @Throws(SecretStorageException::class)
    suspend fun update(transform: (AppSettings) -> AppSettings)
}

/** Settings in a Preferences DataStore; credentials are sealed with [SecretBox], everything else is stored as is. */
class DataStoreSettingsRepository(
    private val dataStore: DataStore<Preferences>,
    private val secretBox: SecretBox,
    decodeDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : SettingsRepository {

    override val settings: Flow<AppSettings> = dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map(::decode)
        .flowOn(decodeDispatcher)
        .distinctUntilChanged()

    override suspend fun update(transform: (AppSettings) -> AppSettings) {
        // A change the user confirmed is finished even if the screen (and its view model) goes away meanwhile.
        withContext(NonCancellable) {
            dataStore.edit { prefs ->
                val before = decode(prefs)
                write(prefs, before, transform(before))
            }
        }
    }

    private fun decode(p: Preferences): AppSettings {
        val d = AppSettings()
        return AppSettings(
            amapKey = p.secret(Keys.AMAP_KEY),
            useDemoData = p[Keys.USE_DEMO_DATA] ?: d.useDemoData,
            provider = p[Keys.PROVIDER]?.let { stored -> LlmProvider.entries.firstOrNull { it.name == stored } } ?: d.provider,
            anthropic = AnthropicSettings(
                apiKey = p.secret(Keys.ANTHROPIC_KEY),
                model = p[Keys.ANTHROPIC_MODEL] ?: d.anthropic.model,
                baseUrl = p[Keys.ANTHROPIC_BASE_URL] ?: d.anthropic.baseUrl,
                effort = p[Keys.ANTHROPIC_EFFORT] ?: d.anthropic.effort,
            ),
            openAi = OpenAiSettings(
                apiKey = p.secret(Keys.OPENAI_KEY),
                baseUrl = p[Keys.OPENAI_BASE_URL] ?: d.openAi.baseUrl,
                model = p[Keys.OPENAI_MODEL] ?: d.openAi.model,
                visionModel = p[Keys.OPENAI_VISION_MODEL] ?: d.openAi.visionModel,
            ),
            criteria = p[Keys.CRITERIA]?.let { decodeJson<Criteria>(it) } ?: d.criteria,
            preset = p[Keys.PRESET]?.let { stored -> ScoringPreset.entries.firstOrNull { it.name == stored } } ?: d.preset,
            demoBannerDismissed = p[Keys.DEMO_BANNER_DISMISSED] ?: d.demoBannerDismissed,
            savedPlace = p[Keys.SAVED_PLACE]?.let { decodeJson<SavedPlace>(it) },
            meals = MealNotificationSettings(
                lunchEnabled = p[Keys.MEAL_LUNCH_ENABLED] ?: d.meals.lunchEnabled,
                lunchTime = p.mealTime(Keys.MEAL_LUNCH_MINUTE) ?: d.meals.lunchTime,
                dinnerEnabled = p[Keys.MEAL_DINNER_ENABLED] ?: d.meals.dinnerEnabled,
                dinnerTime = p.mealTime(Keys.MEAL_DINNER_MINUTE) ?: d.meals.dinnerTime,
                weekdaysOnly = p[Keys.MEAL_WEEKDAYS_ONLY] ?: d.meals.weekdaysOnly,
            ),
        )
    }

    /** Writes only what changed, so unrelated updates never re-encrypt credentials. */
    private fun write(p: MutablePreferences, old: AppSettings, new: AppSettings) {
        if (old.amapKey != new.amapKey) p.putSecret(Keys.AMAP_KEY, new.amapKey)
        if (old.useDemoData != new.useDemoData) p[Keys.USE_DEMO_DATA] = new.useDemoData
        if (old.provider != new.provider) p[Keys.PROVIDER] = new.provider.name
        if (old.anthropic.apiKey != new.anthropic.apiKey) p.putSecret(Keys.ANTHROPIC_KEY, new.anthropic.apiKey)
        if (old.anthropic.model != new.anthropic.model) p[Keys.ANTHROPIC_MODEL] = new.anthropic.model
        if (old.anthropic.baseUrl != new.anthropic.baseUrl) p[Keys.ANTHROPIC_BASE_URL] = new.anthropic.baseUrl
        if (old.anthropic.effort != new.anthropic.effort) p[Keys.ANTHROPIC_EFFORT] = new.anthropic.effort
        if (old.openAi.apiKey != new.openAi.apiKey) p.putSecret(Keys.OPENAI_KEY, new.openAi.apiKey)
        if (old.openAi.baseUrl != new.openAi.baseUrl) p[Keys.OPENAI_BASE_URL] = new.openAi.baseUrl
        if (old.openAi.model != new.openAi.model) p[Keys.OPENAI_MODEL] = new.openAi.model
        if (old.openAi.visionModel != new.openAi.visionModel) p[Keys.OPENAI_VISION_MODEL] = new.openAi.visionModel
        if (old.criteria != new.criteria) p[Keys.CRITERIA] = JSON.encodeToString(Criteria.serializer(), new.criteria)
        if (old.preset != new.preset) p[Keys.PRESET] = new.preset.name
        if (old.demoBannerDismissed != new.demoBannerDismissed) p[Keys.DEMO_BANNER_DISMISSED] = new.demoBannerDismissed
        if (old.savedPlace != new.savedPlace) {
            val place = new.savedPlace
            if (place == null) p.remove(Keys.SAVED_PLACE) else p[Keys.SAVED_PLACE] = JSON.encodeToString(SavedPlace.serializer(), place)
        }
        val meals = new.meals
        if (old.meals.lunchEnabled != meals.lunchEnabled) p[Keys.MEAL_LUNCH_ENABLED] = meals.lunchEnabled
        if (old.meals.lunchTime != meals.lunchTime) p[Keys.MEAL_LUNCH_MINUTE] = MealNotificationSettings.minuteOfDay(meals.lunchTime)
        if (old.meals.dinnerEnabled != meals.dinnerEnabled) p[Keys.MEAL_DINNER_ENABLED] = meals.dinnerEnabled
        if (old.meals.dinnerTime != meals.dinnerTime) p[Keys.MEAL_DINNER_MINUTE] = MealNotificationSettings.minuteOfDay(meals.dinnerTime)
        if (old.meals.weekdaysOnly != meals.weekdaysOnly) p[Keys.MEAL_WEEKDAYS_ONLY] = meals.weekdaysOnly
    }

    private fun Preferences.mealTime(key: Preferences.Key<Int>): LocalTime? = this[key]?.let(MealNotificationSettings::timeOfMinute)

    private fun Preferences.secret(key: Preferences.Key<String>): Secret =
        this[key]?.let(secretBox::decrypt)?.let(::Secret) ?: Secret.EMPTY

    private fun MutablePreferences.putSecret(key: Preferences.Key<String>, secret: Secret) {
        if (secret.isSet) this[key] = secretBox.encrypt(secret.value) else remove(key)
    }

    private inline fun <reified T> decodeJson(text: String): T? = try {
        JSON.decodeFromString<T>(text)
    } catch (e: SerializationException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    }

    private object Keys {
        val AMAP_KEY = stringPreferencesKey("amap_key_sealed")
        val USE_DEMO_DATA = booleanPreferencesKey("use_demo_data")
        val PROVIDER = stringPreferencesKey("llm_provider")
        val ANTHROPIC_KEY = stringPreferencesKey("anthropic_key_sealed")
        val ANTHROPIC_MODEL = stringPreferencesKey("anthropic_model")
        val ANTHROPIC_BASE_URL = stringPreferencesKey("anthropic_base_url")
        val ANTHROPIC_EFFORT = stringPreferencesKey("anthropic_effort")
        val OPENAI_KEY = stringPreferencesKey("openai_key_sealed")
        val OPENAI_BASE_URL = stringPreferencesKey("openai_base_url")
        val OPENAI_MODEL = stringPreferencesKey("openai_model")
        val OPENAI_VISION_MODEL = stringPreferencesKey("openai_vision_model")
        val CRITERIA = stringPreferencesKey("criteria_json")
        val PRESET = stringPreferencesKey("scoring_preset")
        val DEMO_BANNER_DISMISSED = booleanPreferencesKey("demo_banner_dismissed")
        val SAVED_PLACE = stringPreferencesKey("saved_place_json")
        val MEAL_LUNCH_ENABLED = booleanPreferencesKey("meal_lunch_enabled")
        val MEAL_LUNCH_MINUTE = intPreferencesKey("meal_lunch_minute")
        val MEAL_DINNER_ENABLED = booleanPreferencesKey("meal_dinner_enabled")
        val MEAL_DINNER_MINUTE = intPreferencesKey("meal_dinner_minute")
        val MEAL_WEEKDAYS_ONLY = booleanPreferencesKey("meal_weekdays_only")
    }

    private companion object {
        val JSON = Json { ignoreUnknownKeys = true }
    }
}
