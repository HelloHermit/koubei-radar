package com.koubeiradar.app

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.koubeiradar.agent.llm.LlmConfig
import com.koubeiradar.agent.llm.LlmProvider
import com.koubeiradar.app.settings.AppSettings
import com.koubeiradar.app.settings.Checked
import com.koubeiradar.app.settings.DataStoreSettingsRepository
import com.koubeiradar.app.settings.InputProblem
import com.koubeiradar.app.settings.InputRules
import com.koubeiradar.app.settings.MealNotificationSettings
import com.koubeiradar.app.settings.PoiProvider
import com.koubeiradar.app.settings.SavedPlace
import com.koubeiradar.app.settings.ScoringPreset
import com.koubeiradar.app.settings.Secret
import com.koubeiradar.app.settings.SecretBox
import com.koubeiradar.app.settings.SettingField
import com.koubeiradar.app.settings.checkSetting
import com.koubeiradar.app.settings.with
import com.koubeiradar.core.model.Criteria
import com.koubeiradar.core.model.GeoPoint
import com.koubeiradar.core.model.Platform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.LocalTime
import java.util.Base64
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun newKey(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

class SecretBoxTest {
    private val box = SecretBox(newKey().let { key -> { key } })

    @Test
    fun `round trips text including non ASCII`() {
        listOf("sk-test-0123456789", "密钥 with spaces", "").forEach { assertEquals(it, box.decrypt(box.encrypt(it))) }
    }

    @Test
    fun `ciphertext hides the text and differs on every call`() {
        val first = box.encrypt("sk-test-0123456789")
        assertFalse("sk-test" in first)
        assertNotEquals(first, box.encrypt("sk-test-0123456789"))
    }

    @Test
    fun `tampered, truncated, foreign and malformed input decrypts to null`() {
        val sealed = box.encrypt("secret")
        val bytes = Base64.getDecoder().decode(sealed)
        bytes[bytes.size - 1] = (bytes.last().toInt() xor 1).toByte()
        assertNull(box.decrypt(Base64.getEncoder().encodeToString(bytes)))
        assertNull(box.decrypt(sealed.take(10)))
        assertNull(box.decrypt("not base64 !!"))
        assertNull(box.decrypt(""))
        assertNull(SecretBox(newKey().let { key -> { key } }).decrypt(sealed), "sealed with another key")
    }
}

class SecretTest {
    @Test
    fun `never prints itself and only short tails are shown`() {
        val secret = Secret("sk-ant-api03-abcdefghijkl")
        assertFalse("abcdef" in secret.toString())
        assertFalse("abcdef" in AppSettings(amapKey = secret).toString())
        assertFalse("abcdef" in AppSettings(baiduKey = secret, poiProvider = PoiProvider.BAIDU).toString())
        assertEquals("ijkl", secret.tail)
        assertEquals("", Secret("short-key").tail)
        assertFalse(Secret.EMPTY.isSet)
    }
}

class InputRulesTest {
    private fun ok(result: Checked) = (result as? Checked.Ok)?.value
    private fun problem(result: Checked) = (result as? Checked.Rejected)?.problem

    @Test
    fun `credentials are trimmed and must be plain visible ASCII`() {
        assertEquals("sk-abc123", ok(InputRules.credential("  sk-abc123 \n")))
        assertEquals(InputProblem.EMPTY, problem(InputRules.credential("   ")))
        assertEquals(InputProblem.CONTAINS_WHITESPACE, problem(InputRules.credential("sk abc")))
        assertEquals(InputProblem.NOT_PLAIN_ASCII, problem(InputRules.credential("sk-密钥")))
        assertEquals(InputProblem.NOT_PLAIN_ASCII, problem(InputRules.credential("sk-abc​")))
        assertEquals(InputProblem.TOO_LONG, problem(InputRules.credential("a".repeat(600))))
    }

    @Test
    fun `base url must be https`() {
        assertEquals(InputProblem.NOT_HTTPS, problem(InputRules.baseUrl("http://api.example.com/v1", required = true)))
        assertEquals(InputProblem.NOT_HTTPS, problem(InputRules.baseUrl("HTTP://api.example.com", required = true)))
        assertEquals(InputProblem.INVALID_URL, problem(InputRules.baseUrl("https://", required = true)))
        assertEquals(InputProblem.INVALID_URL, problem(InputRules.baseUrl("https://api.example.com/v1?x=1", required = true)))
        assertEquals(InputProblem.URL_HAS_CREDENTIALS, problem(InputRules.baseUrl("https://user:pw@api.example.com", required = true)))
        assertEquals(InputProblem.CONTAINS_WHITESPACE, problem(InputRules.baseUrl("https://api.example.com/a b", required = true)))
    }

    @Test
    fun `base url is trimmed and normalised`() {
        assertEquals("https://api.deepseek.com/v1", ok(InputRules.baseUrl("  https://api.deepseek.com/v1/  ", required = true)))
        assertEquals("https://api.deepseek.com/v1", ok(InputRules.baseUrl("https://api.deepseek.com/v1/chat/completions", required = true)))
        assertEquals("https://api.deepseek.com", ok(InputRules.baseUrl("api.deepseek.com", required = true)))
        assertEquals("https://gateway.example.com", ok(InputRules.baseUrl("HTTPS://Gateway.Example.com/", required = true)))
    }

    @Test
    fun `blank url or model is an error only when required`() {
        assertEquals(InputProblem.EMPTY, problem(InputRules.baseUrl(" ", required = true)))
        assertEquals("", ok(InputRules.baseUrl(" ", required = false)))
        assertEquals(InputProblem.EMPTY, problem(InputRules.modelName("", required = true)))
        assertEquals("", ok(InputRules.modelName("  ", required = false)))
    }

    @Test
    fun `model names are trimmed single tokens`() {
        assertEquals("deepseek-chat", ok(InputRules.modelName(" deepseek-chat ", required = true)))
        assertEquals("anthropic/claude-3.5:beta", ok(InputRules.modelName("anthropic/claude-3.5:beta", required = true)))
        assertEquals(InputProblem.CONTAINS_WHITESPACE, problem(InputRules.modelName("gpt 4", required = true)))
        assertEquals(InputProblem.NOT_PLAIN_ASCII, problem(InputRules.modelName("模型", required = true)))
    }

    @Test
    fun `every field has a rule`() {
        assertEquals(InputProblem.NOT_HTTPS, problem(checkSetting(SettingField.OPENAI_BASE_URL, "http://x.com")))
        assertEquals("", ok(checkSetting(SettingField.ANTHROPIC_BASE_URL, "")))
        assertEquals("", ok(checkSetting(SettingField.OPENAI_VISION_MODEL, "")))
        assertEquals(InputProblem.EMPTY, problem(checkSetting(SettingField.ANTHROPIC_MODEL, "")))
        assertEquals("k1", ok(checkSetting(SettingField.AMAP_KEY, " k1 ")))
        assertEquals("ak1", ok(checkSetting(SettingField.BAIDU_KEY, " ak1 \n")))
        assertEquals(InputProblem.EMPTY, problem(checkSetting(SettingField.BAIDU_KEY, "  ")))
        assertEquals(InputProblem.CONTAINS_WHITESPACE, problem(checkSetting(SettingField.BAIDU_KEY, "a b")))
        assertEquals(InputProblem.NOT_PLAIN_ASCII, problem(checkSetting(SettingField.BAIDU_KEY, "ａｋ")))
    }
}

class AppSettingsTest {
    @Test
    fun `real data is used only with a key of the selected provider and while demo data is not forced`() {
        assertNull(AppSettings().activePoi)
        assertEquals(PoiProvider.AMAP, AppSettings(amapKey = Secret("k")).activePoi)
        assertNull(AppSettings(amapKey = Secret("k"), useDemoData = true).activePoi)
        assertFalse(AppSettings(amapKey = Secret("k"), useDemoData = true).usesRealData)

        val baidu = AppSettings(baiduKey = Secret("ak"), poiProvider = PoiProvider.BAIDU)
        assertEquals(PoiProvider.BAIDU, baidu.activePoi)
        assertEquals(Secret("ak"), baidu.poiKey)
        assertNull(baidu.copy(useDemoData = true).activePoi)
    }

    @Test
    fun `a key of the other provider does not count`() {
        assertNull(AppSettings(amapKey = Secret("k"), poiProvider = PoiProvider.BAIDU).activePoi)
        assertNull(AppSettings(baiduKey = Secret("ak"), poiProvider = PoiProvider.AMAP).activePoi)
        assertEquals(PoiProvider.AMAP, AppSettings().poiProvider, "amap stays the default")
    }

    @Test
    fun `llm config requires the provider to be fully set up`() {
        assertNull(AppSettings().llmConfig())
        val claude = AppSettings().with(SettingField.ANTHROPIC_KEY, "sk-ant").llmConfig()
        assertEquals(LlmProvider.ANTHROPIC, claude?.provider)
        assertEquals(LlmConfig.DEFAULT_CLAUDE_MODEL, claude?.model)
        assertNull(claude?.baseUrl)
        assertNull(claude?.effort)

        val compat = AppSettings(provider = LlmProvider.OPENAI_COMPATIBLE)
        assertNull(compat.llmConfig(), "key still missing")
        val configured = compat.with(SettingField.OPENAI_KEY, "sk-x").with(SettingField.OPENAI_VISION_MODEL, "vl-model").llmConfig()
        assertEquals("sk-x", configured?.apiKey)
        assertEquals("vl-model", configured?.forVision()?.model)
        assertTrue((configured?.maxTokens ?: Int.MAX_VALUE) <= 4095, "must fit the smallest provider limit")
        assertNull(compat.with(SettingField.OPENAI_KEY, "sk-x").with(SettingField.OPENAI_BASE_URL, "").llmConfig())
    }

    @Test
    fun `fields are written to the right place`() {
        val settings = AppSettings()
            .with(SettingField.AMAP_KEY, "amap")
            .with(SettingField.BAIDU_KEY, "baidu")
            .with(SettingField.ANTHROPIC_MODEL, "claude-sonnet-5-5")
            .with(SettingField.OPENAI_BASE_URL, "https://x.example.com")
        assertEquals("amap", settings.amapKey.value)
        assertEquals("baidu", settings.baiduKey.value)
        assertEquals("claude-sonnet-5-5", settings.anthropic.model)
        assertEquals("https://x.example.com", settings.openAi.baseUrl)
    }
}

class DataStoreSettingsRepositoryTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun repository(file: File, key: SecretKey): DataStoreSettingsRepository = DataStoreSettingsRepository(
        dataStore = PreferenceDataStoreFactory.create(scope = scope) { file },
        secretBox = SecretBox { key },
        decodeDispatcher = Dispatchers.Unconfined,
    )

    @Test
    fun `settings round trip and credentials never reach the file in clear`() = runBlocking<Unit> {
        val file = File(folder.root, "settings.preferences_pb")
        val key = newKey()
        val repository = repository(file, key)
        val saved = AppSettings(
            amapKey = Secret("amap-key-0123456789"),
            baiduKey = Secret("baidu-ak-0123456789"),
            poiProvider = PoiProvider.BAIDU,
            useDemoData = true,
            provider = LlmProvider.OPENAI_COMPATIBLE,
            criteria = Criteria(minRating = 4.3, includeKeywords = setOf("川菜"), platforms = setOf(Platform.ELEME), onlyOpen = true),
            preset = ScoringPreset.FAST_FIRST,
            demoBannerDismissed = true,
            savedPlace = SavedPlace("望京SOHO", GeoPoint(39.99, 116.47)),
        ).with(SettingField.OPENAI_KEY, "sk-openai-secret-value").with(SettingField.OPENAI_MODEL, "qwen-plus")

        repository.update { saved }

        assertEquals(saved, repository.settings.first())
        val onDisk = file.readBytes().toString(Charsets.ISO_8859_1)
        assertFalse("amap-key-0123456789" in onDisk)
        assertFalse("baidu-ak-0123456789" in onDisk)
        assertFalse("sk-openai-secret-value" in onDisk)
    }

    @Test
    fun `an unreadable credential reads as empty and the other settings survive`() = runBlocking<Unit> {
        val file = File(folder.root, "settings.preferences_pb")
        repository(file, newKey()).update { it.with(SettingField.AMAP_KEY, "amap-key").copy(useDemoData = true) }
        scope.cancel()

        val reopened = DataStoreSettingsRepository(
            dataStore = PreferenceDataStoreFactory.create(scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)) { file },
            secretBox = SecretBox(newKey().let { other -> { other } }),
            decodeDispatcher = Dispatchers.Unconfined,
        )
        val settings = reopened.settings.first()
        assertEquals(Secret.EMPTY, settings.amapKey)
        assertTrue(settings.useDemoData)
    }

    @Test
    fun `meal notification settings and a remembered device position round trip`() = runBlocking<Unit> {
        val repository = repository(File(folder.root, "meals.preferences_pb"), newKey())
        val saved = AppSettings(
            savedPlace = SavedPlace("", GeoPoint(30.25, 120.17), fromDevice = true),
            meals = MealNotificationSettings(
                lunchEnabled = true,
                lunchTime = LocalTime.of(12, 15),
                dinnerEnabled = true,
                dinnerTime = LocalTime.of(19, 5),
                weekdaysOnly = true,
            ),
        )

        repository.update { saved }

        assertEquals(saved, repository.settings.first())
        repository.update { it.copy(meals = it.meals.copy(lunchEnabled = false)) }
        assertEquals(saved.meals.copy(lunchEnabled = false), repository.settings.first().meals, "one change leaves the rest alone")
    }

    @Test
    fun `settings stored before the baidu provider existed read as amap with no baidu key`() = runBlocking<Unit> {
        val key = newKey()
        val dataStore = PreferenceDataStoreFactory.create(scope = scope) { File(folder.root, "pre-baidu.preferences_pb") }
        dataStore.edit {
            it[stringPreferencesKey("amap_key_sealed")] = SecretBox { key }.encrypt("amap-key-0123456789")
            it[booleanPreferencesKey("demo_banner_dismissed")] = true
        }
        val repository = DataStoreSettingsRepository(dataStore, SecretBox { key }, Dispatchers.Unconfined)

        val settings = repository.settings.first()

        assertEquals(PoiProvider.AMAP, settings.poiProvider)
        assertEquals(Secret.EMPTY, settings.baiduKey)
        assertEquals(PoiProvider.AMAP, settings.activePoi, "an existing Amap key keeps working")
        assertEquals("amap-key-0123456789", settings.amapKey.value)
        assertTrue(settings.demoBannerDismissed)
    }

    @Test
    fun `an unknown stored provider falls back to amap and each key survives a provider switch`() = runBlocking<Unit> {
        val key = newKey()
        val dataStore = PreferenceDataStoreFactory.create(scope = scope) { File(folder.root, "provider.preferences_pb") }
        dataStore.edit { it[stringPreferencesKey("poi_provider")] = "TENCENT" }
        val repository = DataStoreSettingsRepository(dataStore, SecretBox { key }, Dispatchers.Unconfined)
        assertEquals(PoiProvider.AMAP, repository.settings.first().poiProvider)

        repository.update { it.with(SettingField.AMAP_KEY, "amap-key").with(SettingField.BAIDU_KEY, "baidu-ak") }
        repository.update { it.copy(poiProvider = PoiProvider.BAIDU) }
        assertEquals(PoiProvider.BAIDU, repository.settings.first().poiProvider)
        repository.update { it.copy(poiProvider = PoiProvider.AMAP) }

        val settings = repository.settings.first()
        assertEquals(PoiProvider.AMAP, settings.poiProvider)
        assertEquals("amap-key", settings.amapKey.value)
        assertEquals("baidu-ak", settings.baiduKey.value)
    }

    @Test
    fun `settings stored before the meal notifications existed read with them off and keep everything else`() = runBlocking<Unit> {
        val dataStore = PreferenceDataStoreFactory.create(scope = scope) { File(folder.root, "old.preferences_pb") }
        dataStore.edit {
            it[booleanPreferencesKey("use_demo_data")] = true
            it[stringPreferencesKey("scoring_preset")] = ScoringPreset.FAST_FIRST.name
            it[stringPreferencesKey("saved_place_json")] = """{"address":"望京SOHO","point":{"lat":39.99,"lng":116.47}}"""
        }
        val repository = DataStoreSettingsRepository(dataStore, SecretBox { newKey() }, Dispatchers.Unconfined)

        val settings = repository.settings.first()

        assertEquals(MealNotificationSettings(), settings.meals)
        assertEquals(SavedPlace("望京SOHO", GeoPoint(39.99, 116.47), fromDevice = false), settings.savedPlace)
        assertTrue(settings.useDemoData)
        assertEquals(ScoringPreset.FAST_FIRST, settings.preset)
    }

    @Test
    fun `a damaged stored time falls back to the default of that meal only`() = runBlocking<Unit> {
        val dataStore = PreferenceDataStoreFactory.create(scope = scope) { File(folder.root, "damaged.preferences_pb") }
        dataStore.edit {
            it[booleanPreferencesKey("meal_lunch_enabled")] = true
            it[intPreferencesKey("meal_lunch_minute")] = -5
            it[booleanPreferencesKey("meal_dinner_enabled")] = true
            it[intPreferencesKey("meal_dinner_minute")] = 18 * 60
        }
        val repository = DataStoreSettingsRepository(dataStore, SecretBox { newKey() }, Dispatchers.Unconfined)

        val meals = repository.settings.first().meals

        assertTrue(meals.lunchEnabled)
        assertEquals(MealNotificationSettings.DEFAULT_LUNCH_TIME, meals.lunchTime)
        assertEquals(LocalTime.of(18, 0), meals.dinnerTime)
    }

    @Test
    fun `a stored time beyond the end of the day is also rejected`() = runBlocking<Unit> {
        val dataStore = PreferenceDataStoreFactory.create(scope = scope) { File(folder.root, "beyond.preferences_pb") }
        dataStore.edit { it[intPreferencesKey("meal_dinner_minute")] = 24 * 60 }
        val repository = DataStoreSettingsRepository(dataStore, SecretBox { newKey() }, Dispatchers.Unconfined)

        assertEquals(MealNotificationSettings.DEFAULT_DINNER_TIME, repository.settings.first().meals.dinnerTime)
    }

    @Test
    fun `a damaged remembered place reads as no place`() = runBlocking<Unit> {
        val dataStore = PreferenceDataStoreFactory.create(scope = scope) { File(folder.root, "place.preferences_pb") }
        dataStore.edit { it[stringPreferencesKey("saved_place_json")] = "{not json" }
        val repository = DataStoreSettingsRepository(dataStore, SecretBox { newKey() }, Dispatchers.Unconfined)

        assertNull(repository.settings.first().savedPlace)
    }

    @Test
    fun `defaults apply to an empty store`() = runBlocking<Unit> {
        val settings = repository(File(folder.root, "empty.preferences_pb"), newKey()).settings.first()
        assertEquals(AppSettings(), settings)
        assertNotNull(settings.openAi.baseUrl.takeIf { it.startsWith("https://") }, "the OpenAI-compatible default is a https preset")
    }
}
