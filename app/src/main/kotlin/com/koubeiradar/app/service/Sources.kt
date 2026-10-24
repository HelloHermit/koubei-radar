package com.koubeiradar.app.service

import com.koubeiradar.app.settings.AppSettings
import com.koubeiradar.app.settings.ScoringPreset
import com.koubeiradar.core.model.GeoPoint
import com.koubeiradar.core.scoring.ReputationScorer
import com.koubeiradar.core.selection.ShopRanker
import com.koubeiradar.core.selection.ShopSelector
import com.koubeiradar.data.Geocoder
import com.koubeiradar.data.ShopSource
import com.koubeiradar.data.amap.AmapGeocoder
import com.koubeiradar.data.amap.AmapShopSource
import com.koubeiradar.data.demo.DemoShopSource
import okhttp3.OkHttpClient
import java.util.concurrent.ConcurrentHashMap

/** Id of the offline demo source, as documented on `Shop.source`. */
object ShopSourceIds {
    const val DEMO = "demo"
}

/** Where the offline demo data is centred when the user has not been located (central Beijing, GCJ-02). */
val DEMO_CENTER = GeoPoint(lat = 39.9087, lng = 116.3975)

/** Builds the shop source and geocoder that match the current settings. */
interface DataSources {
    /** Amap when a key is configured and the demo switch is off, the offline demo data otherwise. */
    fun sourceFor(settings: AppSettings): ShopSource

    /** Address lookup needs the Amap key and is off while the demo data is forced; `null` then. */
    fun geocoderFor(settings: AppSettings): Geocoder?
}

class DefaultDataSources(private val http: OkHttpClient) : DataSources {
    private val demo = DemoShopSource()

    override fun sourceFor(settings: AppSettings): ShopSource =
        if (settings.usesAmap) AmapShopSource(settings.amapKey.value, http) else demo

    override fun geocoderFor(settings: AppSettings): Geocoder? =
        if (settings.usesAmap) AmapGeocoder(settings.amapKey.value, http) else null
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
