package com.koubeiradar.app

import android.content.Context
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStoreFile
import com.koubeiradar.app.platform.AndroidDeliveryLauncher
import com.koubeiradar.app.platform.AndroidImageLoader
import com.koubeiradar.app.platform.AndroidLocationService
import com.koubeiradar.app.platform.DeliveryLauncher
import com.koubeiradar.app.platform.ImageLoader
import com.koubeiradar.app.platform.LocationService
import com.koubeiradar.app.platform.ShareInbox
import com.koubeiradar.app.service.AdvisorService
import com.koubeiradar.app.service.CachingRankerProvider
import com.koubeiradar.app.service.DataSources
import com.koubeiradar.app.service.DefaultDataSources
import com.koubeiradar.app.service.ImportService
import com.koubeiradar.app.service.LlmAdvisorService
import com.koubeiradar.app.service.LlmImportService
import com.koubeiradar.app.service.PlaceStore
import com.koubeiradar.app.service.RankerProvider
import com.koubeiradar.app.settings.DataStoreSettingsRepository
import com.koubeiradar.app.settings.SecretBox
import com.koubeiradar.app.settings.SettingsRepository
import com.koubeiradar.app.settings.androidKeyStoreKeyProvider
import com.koubeiradar.app.store.FileImportedShopStore
import com.koubeiradar.app.store.PersistingShopStore
import com.koubeiradar.app.store.RepositoryShopStore
import com.koubeiradar.app.store.ShopStore
import com.koubeiradar.data.repo.ShopRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.time.Clock
import java.util.concurrent.TimeUnit
import javax.crypto.SecretKey

/**
 * Manual dependency injection: one instance per process, created by [KoubeiApplication].
 * [secretKeyProvider] supplies the key that seals stored credentials; production always uses the AndroidKeyStore one,
 * only tests (where that provider does not exist) pass another.
 */
class AppContainer(context: Context, secretKeyProvider: () -> SecretKey = androidKeyStoreKeyProvider()) {
    private val appContext = context.applicationContext
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    /** Model calls answer only when generation is complete, so they need a much longer read timeout. */
    private val llmHttp = http.newBuilder().readTimeout(240, TimeUnit.SECONDS).build()

    private val shopRepository = ShopRepository()

    val settings: SettingsRepository = DataStoreSettingsRepository(
        dataStore = PreferenceDataStoreFactory.create(
            corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            produceFile = { appContext.preferencesDataStoreFile("settings") },
        ),
        secretBox = SecretBox(secretKeyProvider),
    )

    private val persistingStore = PersistingShopStore(
        delegate = RepositoryShopStore(shopRepository),
        persistence = FileImportedShopStore(appContext.filesDir),
        scope = applicationScope,
    )
    val shopStore: ShopStore = persistingStore

    val rankers: RankerProvider = CachingRankerProvider()
    val dataSources: DataSources = DefaultDataSources(http)
    val places = PlaceStore()
    val shareInbox = ShareInbox()
    val clock: Clock = Clock.systemDefaultZone()

    val locationService: LocationService = AndroidLocationService(appContext)
    val deliveryLauncher: DeliveryLauncher = AndroidDeliveryLauncher(appContext)
    val imageLoader: ImageLoader = AndroidImageLoader(appContext)

    val advisorService: AdvisorService = LlmAdvisorService(settings, shopRepository, rankers, llmHttp)
    val importService: ImportService = LlmImportService(settings, llmHttp)

    init {
        applicationScope.launch { persistingStore.restore() }
    }
}
