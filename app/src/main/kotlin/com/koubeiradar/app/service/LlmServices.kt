package com.koubeiradar.app.service

import com.koubeiradar.agent.advisor.AdvisorContext
import com.koubeiradar.agent.advisor.AdvisorResult
import com.koubeiradar.agent.advisor.AgentEvent
import com.koubeiradar.agent.advisor.ConversationTurn
import com.koubeiradar.agent.advisor.ShopAdvisorAgent
import com.koubeiradar.agent.importer.ImageInput
import com.koubeiradar.agent.importer.ImportResult
import com.koubeiradar.agent.importer.ShopImportExtractor
import com.koubeiradar.agent.llm.LlmClients
import com.koubeiradar.app.settings.AppSettings
import com.koubeiradar.app.settings.SettingsRepository
import com.koubeiradar.data.repo.ShopPool
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

/** The selected LLM provider is not fully configured (no key, or for OpenAI-compatible providers no URL / model). */
class LlmNotConfiguredException : Exception("LLM provider is not configured")

/** The shop advisor agent as the UI sees it: the model client is built from the current settings on every call. */
interface AdvisorService {
    val isConfigured: Flow<Boolean>

    /** @throws LlmNotConfiguredException when no provider is set up; `LlmException` for provider failures. */
    suspend fun advise(
        request: String,
        context: AdvisorContext,
        previousTurns: List<ConversationTurn>,
        onEvent: (AgentEvent) -> Unit,
    ): AdvisorResult
}

/** Screenshot / text import as the UI sees it. */
interface ImportService {
    val isConfigured: Flow<Boolean>

    /** @throws LlmNotConfiguredException when no provider is set up; `LlmException` for provider failures. */
    suspend fun fromImages(images: List<ImageInput>): ImportResult

    suspend fun fromText(text: String): ImportResult
}

private fun SettingsRepository.configured(): Flow<Boolean> =
    settings.map { it.llmConfig() != null }.distinctUntilChanged()

class LlmAdvisorService(
    private val settings: SettingsRepository,
    private val pool: ShopPool,
    private val rankers: RankerProvider,
    private val http: OkHttpClient,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : AdvisorService {
    override val isConfigured: Flow<Boolean> = settings.configured()

    override suspend fun advise(
        request: String,
        context: AdvisorContext,
        previousTurns: List<ConversationTurn>,
        onEvent: (AgentEvent) -> Unit,
    ): AdvisorResult = withContext(dispatcher) {
        val current: AppSettings = settings.settings.first()
        val config = current.llmConfig() ?: throw LlmNotConfiguredException()
        val agent = ShopAdvisorAgent(LlmClients.create(config, http), pool, rankers.forPreset(current.preset))
        agent.advise(request, context, previousTurns, onEvent)
    }
}

class LlmImportService(
    private val settings: SettingsRepository,
    private val http: OkHttpClient,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ImportService {
    override val isConfigured: Flow<Boolean> = settings.configured()

    override suspend fun fromImages(images: List<ImageInput>): ImportResult = withContext(dispatcher) {
        val config = settings.settings.first().llmConfig() ?: throw LlmNotConfiguredException()
        ShopImportExtractor(LlmClients.create(config.forVision(), http)).fromImages(images)
    }

    override suspend fun fromText(text: String): ImportResult = withContext(dispatcher) {
        val config = settings.settings.first().llmConfig() ?: throw LlmNotConfiguredException()
        ShopImportExtractor(LlmClients.create(config, http)).fromText(text)
    }
}
