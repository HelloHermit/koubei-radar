package com.koubeiradar.app.service

import com.koubeiradar.app.settings.AppSettings
import com.koubeiradar.app.settings.PoiProvider
import com.koubeiradar.app.settings.ScoringPreset
import com.koubeiradar.core.model.GeoPoint
import com.koubeiradar.core.scoring.ReputationScorer
import com.koubeiradar.core.selection.ShopRanker
import com.koubeiradar.core.selection.ShopSelector
import com.koubeiradar.data.CachingReverseGeocoder
import com.koubeiradar.data.Geocoder
import com.koubeiradar.data.ReverseGeocoder
import com.koubeiradar.data.ShopSource
import com.koubeiradar.data.amap.AmapGeocoder
import com.koubeiradar.data.amap.AmapReverseGeocoder
import com.koubeiradar.data.amap.AmapShopSource
import com.koubeiradar.data.baidu.BaiduGeocoder
import com.koubeiradar.data.baidu.BaiduReverseGeocoder
import com.koubeiradar.data.baidu.BaiduShopSource
import com.koubeiradar.data.demo.DemoShopSource
import okhttp3.OkHttpClient
import java.util.concurrent.ConcurrentHashMap

/** Id of the offline demo source, as documented on `Shop.source`. */
object ShopSourceIds {
    const val DEMO = "demo"
}

/** Where the offline demo data is centred when the user has not been located (central Beijing, GCJ-02). */
val DEMO_CENTER = GeoPoint(lat = 39.9087, lng = 116.3975)

/** Builds the shop source and geocoders that match the current settings. */
interface DataSources {
    /** The selected provider (Amap or Baidu) when its key is configured and the demo switch is off, the offline demo data otherwise. */
    fun sourceFor(settings: AppSettings): ShopSource

    /** Address lookup needs the selected provider's key and is off while the demo data is forced; `null` then. */
    fun geocoderFor(settings: AppSettings): Geocoder?

    /**
     * Turns a device position into a short address for display. Like [geocoderFor] it needs the selected provider's key and
     * is `null` while the demo data is forced, so the demo mode never sends a coordinate anywhere. The instance is shared
     * (it caches), so a caller may ask again for every fix.
     */
    fun reverseGeocoderFor(settings: AppSettings): ReverseGeocoder?
}

class DefaultDataSources(private val http: OkHttpClient) : DataSources {
    private val demo = DemoShopSource()

    override fun sourceFor(settings: AppSettings): ShopSource = when (settings.activePoi) {
        PoiProvider.AMAP -> AmapShopSource(settings.amapKey.value, http)
        PoiProvider.BAIDU -> BaiduShopSource(settings.baiduKey.value, http)
        null -> demo
    }

    override fun geocoderFor(settings: AppSettings): Geocoder? = when (settings.activePoi) {
        PoiProvider.AMAP -> AmapGeocoder(settings.amapKey.value, http)
        PoiProvider.BAIDU -> BaiduGeocoder(settings.baiduKey.value, http)
        null -> null
    }

    private val regeoLock = Any()
    private var regeo: Pair<Pair<PoiProvider, String>, ReverseGeocoder>? = null

    override fun reverseGeocoderFor(settings: AppSettings): ReverseGeocoder? {
        val provider = settings.activePoi ?: return null
        val identity = provider to settings.poiKey.value
        return synchronized(regeoLock) {
            regeo?.takeIf { it.first == identity }?.second ?: run {
                val plain = when (provider) {
                    PoiProvider.AMAP -> AmapReverseGeocoder(identity.second, http)
                    PoiProvider.BAIDU -> BaiduReverseGeocoder(identity.second, http)
                }
                CachingReverseGeocoder(plain).also { regeo = identity to it }
            }
        }
    }
}

/** The ranker for a scoring preset. Scorer and selector are rebuilt only when the preset changes. */
fun interface RankerProvider {
    fun forPreset(preset: ScoringPreset): ShopRanker
}

class CachingRankerProvider : RankerProvider {
    private val rankers = ConcurrentHashMap<ScoringPreset, ShopRanker>()

    override fun forPreset(preset: ScoringPreset): ShopRanker =
        rankers.getOrPut(preset) { ShopSelector(ReputationScorer(preset.config)) }
}
