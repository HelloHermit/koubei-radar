package com.koubeiradar.app

import com.koubeiradar.agent.advisor.AdvisorContext
import com.koubeiradar.agent.advisor.AdvisorResult
import com.koubeiradar.agent.advisor.AgentEvent
import com.koubeiradar.agent.advisor.ConversationTurn
import com.koubeiradar.agent.importer.ImageInput
import com.koubeiradar.agent.importer.ImportResult
import com.koubeiradar.app.platform.ImageLoadResult
import com.koubeiradar.app.platform.ImageLoader
import com.koubeiradar.app.platform.LocationResult
import com.koubeiradar.app.platform.LocationService
import com.koubeiradar.app.service.AdvisorService
import com.koubeiradar.app.service.DataSources
import com.koubeiradar.app.service.ImportService
import com.koubeiradar.app.service.LlmNotConfiguredException
import com.koubeiradar.app.service.RankerProvider
import com.koubeiradar.app.settings.AppSettings
import com.koubeiradar.app.settings.ScoringPreset
import com.koubeiradar.app.settings.SecretStorageException
import com.koubeiradar.app.settings.SettingsRepository
import com.koubeiradar.app.store.ShopStore
import com.koubeiradar.core.model.Criteria
import com.koubeiradar.core.model.GeoPoint
import com.koubeiradar.core.model.Platform
import com.koubeiradar.core.model.ScoreBreakdown
import com.koubeiradar.core.model.ScoredShop
import com.koubeiradar.core.model.Shop
import com.koubeiradar.core.model.Verdict
import com.koubeiradar.core.selection.ShopRanker
import com.koubeiradar.data.Geocoder
import com.koubeiradar.data.NearbyQuery
import com.koubeiradar.data.ShopSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/** Makes `viewModelScope` (Dispatchers.Main) run on a test dispatcher. */
@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule(val dispatcher: TestDispatcher = UnconfinedTestDispatcher()) : TestWatcher() {
    override fun starting(description: Description) = Dispatchers.setMain(dispatcher)

    override fun finished(description: Description) = Dispatchers.resetMain()
}

fun shop(
    id: String,
    name: String = "店$id",
    rating: Double? = 4.5,
    price: Double? = 30.0,
    distance: Int? = 800,
    platforms: Set<Platform> = emptySet(),
) = Shop(id = id, name = name, source = "demo", rating = rating, avgPrice = price, distanceMeters = distance, platforms = platforms)

fun scored(shop: Shop, score: Double = (shop.rating ?: 0.0) * 20, verdict: Verdict = Verdict.RECOMMENDED) =
    ScoredShop(shop, score, verdict, ScoreBreakdown())

class FakeSettings(initial: AppSettings = AppSettings()) : SettingsRepository {
    val state = MutableStateFlow(initial)
    var failure: Throwable? = null
    override val settings: Flow<AppSettings> get() = state

    override suspend fun update(transform: (AppSettings) -> AppSettings) {
        failure?.let { throw it }
        state.update(transform)
    }

    val current: AppSettings get() = state.value
}

val secureStorageFailure = SecretStorageException(IllegalStateException("keystore"))

/** Ranks by rating (or by price for VALUE_FIRST) after applying the rating / distance limits; records every call. */
class FakeRankers : RankerProvider {
    val calls = mutableListOf<Pair<ScoringPreset, Criteria>>()

    override fun forPreset(preset: ScoringPreset): ShopRanker = object : ShopRanker {
        override fun select(shops: List<Shop>, criteria: Criteria): List<ScoredShop> {
            calls += preset to criteria
            return shops
                .filter { (criteria.minRating ?: 0.0) <= (it.rating ?: 0.0) }
                .filter { (criteria.maxDistanceMeters ?: Int.MAX_VALUE) >= (it.distanceMeters ?: 0) }
                .map { scored(it) }
                .let { list -> if (preset == ScoringPreset.VALUE_FIRST) list.sortedBy { it.shop.avgPrice } else list.sortedByDescending { it.score } }
                .take(criteria.limit)
        }
    }
}

class FakeShopStore : ShopStore {
    val pool = MutableStateFlow<List<Shop>>(emptyList())
    override val shops: StateFlow<List<Shop>> get() = pool

    class Refresh(val source: ShopSource, val query: NearbyQuery, val force: Boolean)

    val refreshes = mutableListOf<Refresh>()
    var nearby: List<Shop> = emptyList()
    var failure: Exception? = null
    var gate: CompletableDeferred<Unit>? = null
    val imported = mutableListOf<Shop>()
    var clearedImported = 0
    var clearedAll = 0

    override suspend fun refreshNearby(source: ShopSource, query: NearbyQuery, force: Boolean): List<Shop> {
        refreshes += Refresh(source, query, force)
        gate?.await()
        failure?.let { throw it }
        pool.value = nearby + imported
        return nearby
    }

    override fun addImported(shops: List<Shop>) {
        imported += shops
        pool.value = pool.value + shops
    }

    override fun clearImported() {
        clearedImported++
        imported.clear()
    }

    override fun clearAll() {
        clearedAll++
        imported.clear()
        pool.value = emptyList()
    }
}

class FakeSource(override val id: String) : ShopSource {
    override val displayName: String = id

    override suspend fun nearby(query: NearbyQuery): List<Shop> = emptyList()
}

class FakeGeocoder(var result: GeoPoint? = null, var failure: Exception? = null) : Geocoder {
    val requests = mutableListOf<String>()

    override suspend fun geocode(address: String, city: String?): GeoPoint? {
        requests += address
        failure?.let { throw it }
        return result
    }
}

class FakeDataSources(var geocoder: FakeGeocoder? = null) : DataSources {
    override fun sourceFor(settings: AppSettings): ShopSource = FakeSource(settings.activePoi?.name?.lowercase() ?: "demo")

    override fun geocoderFor(settings: AppSettings): Geocoder? = geocoder.takeIf { settings.usesRealData }
}

class FakeLocation(var permission: Boolean = false, var result: LocationResult = LocationResult.Unavailable) : LocationService {
    var lookups = 0

    override fun hasPermission() = permission

    override suspend fun currentLocation(): LocationResult {
        lookups++
        return result
    }
}

class FakeAdvisor : AdvisorService {
    val configured = MutableStateFlow(true)
    override val isConfigured: Flow<Boolean> get() = configured

    class Call(val request: String, val context: AdvisorContext, val previousTurns: List<ConversationTurn>)

    val calls = mutableListOf<Call>()
    var handler: suspend (Call, (AgentEvent) -> Unit) -> AdvisorResult = { _, _ -> error("no handler") }

    override suspend fun advise(
        request: String,
        context: AdvisorContext,
        previousTurns: List<ConversationTurn>,
        onEvent: (AgentEvent) -> Unit,
    ): AdvisorResult {
        val call = Call(request, context, previousTurns)
        calls += call
        if (!configured.value) throw LlmNotConfiguredException()
        return handler(call, onEvent)
    }
}

class FakeImportService : ImportService {
    val configured = MutableStateFlow(true)
    override val isConfigured: Flow<Boolean> get() = configured

    val imageBatches = mutableListOf<List<ImageInput>>()
    val texts = mutableListOf<String>()
    var imageResult = ImportResult(emptyList(), emptyList())
    var textResult = ImportResult(emptyList(), emptyList())
    var failure: Exception? = null

    override suspend fun fromImages(images: List<ImageInput>): ImportResult {
        imageBatches += images
        failure?.let { throw it }
        return imageResult
    }

    override suspend fun fromText(text: String): ImportResult {
        texts += text
        failure?.let { throw it }
        return textResult
    }
}

/** Refs containing "big" or "bad" fail like oversized / unreadable files; everything else loads. */
class FakeImageLoader : ImageLoader {
    override suspend fun load(ref: String): ImageLoadResult = when {
        "big" in ref -> ImageLoadResult.TooLarge
        "bad" in ref -> ImageLoadResult.Unreadable
        else -> ImageLoadResult.Loaded(ImageInput("image/jpeg", ref.toByteArray()))
    }

    override suspend fun thumbnail(ref: String, maxEdgePx: Int) = null
}
