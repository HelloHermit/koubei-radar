package com.koubeiradar.app

import com.koubeiradar.app.service.DefaultDataSources
import com.koubeiradar.app.settings.AppSettings
import com.koubeiradar.app.settings.PoiProvider
import com.koubeiradar.app.settings.Secret
import com.koubeiradar.app.ui.addressNeedsKeyRes
import com.koubeiradar.app.ui.messageRes
import com.koubeiradar.data.ShopSourceException.Kind
import com.koubeiradar.data.CachingReverseGeocoder
import com.koubeiradar.data.amap.AmapGeocoder
import com.koubeiradar.data.amap.AmapShopSource
import com.koubeiradar.data.baidu.BaiduGeocoder
import com.koubeiradar.data.baidu.BaiduShopSource
import com.koubeiradar.data.demo.DemoShopSource
import okhttp3.OkHttpClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame

/** The real [DefaultDataSources] choice and the provider-specific texts, without any network. */
class PoiProviderTest {
    private val sources = DefaultDataSources(OkHttpClient())
    private val amap = AppSettings(amapKey = Secret("amap-key"))
    private val baidu = AppSettings(baiduKey = Secret("baidu-ak"), poiProvider = PoiProvider.BAIDU)

    @Test
    fun `the selected provider with its key picks the real source and geocoder`() {
        assertIs<AmapShopSource>(sources.sourceFor(amap))
        assertIs<AmapGeocoder>(sources.geocoderFor(amap))

        val source = sources.sourceFor(baidu)
        assertIs<BaiduShopSource>(source)
        assertEquals("baidu", source.id)
        assertEquals("百度地图", source.displayName)
        assertIs<BaiduGeocoder>(sources.geocoderFor(baidu))
    }

    @Test
    fun `no key for the selected provider or the demo switch means demo data and no geocoder`() {
        val unsuitable = listOf(
            AppSettings(),
            AppSettings(poiProvider = PoiProvider.BAIDU),
            amap.copy(poiProvider = PoiProvider.BAIDU),
            baidu.copy(poiProvider = PoiProvider.AMAP),
            amap.copy(useDemoData = true),
            baidu.copy(useDemoData = true),
        )
        for (settings in unsuitable) {
            assertIs<DemoShopSource>(sources.sourceFor(settings), settings.toString())
            assertNull(sources.geocoderFor(settings), settings.toString())
            assertNull(sources.reverseGeocoderFor(settings), "demo data never sends a coordinate: $settings")
        }
    }

    @Test
    fun `the reverse geocoder is cached, shared across calls and rebuilt when the key or provider changes`() {
        val first = sources.reverseGeocoderFor(amap)
        assertIs<CachingReverseGeocoder>(first)
        assertSame(first, sources.reverseGeocoderFor(amap.copy(useDemoData = false)), "same provider and key: the cache survives")

        val rekeyed = sources.reverseGeocoderFor(amap.copy(amapKey = Secret("other-key")))
        assertIs<CachingReverseGeocoder>(rekeyed)
        assertNotSame(first, rekeyed)
        val onBaidu = sources.reverseGeocoderFor(baidu)
        assertIs<CachingReverseGeocoder>(onBaidu)
        assertNotSame(rekeyed, onBaidu)
        assertSame(onBaidu, sources.reverseGeocoderFor(baidu))
    }

    @Test
    fun `source errors name the provider that failed`() {
        assertEquals(R.string.error_source_auth, Kind.AUTH.messageRes())
        assertEquals(R.string.error_source_auth, Kind.AUTH.messageRes(PoiProvider.AMAP))
        assertEquals(R.string.error_source_auth_baidu, Kind.AUTH.messageRes(PoiProvider.BAIDU))
        assertEquals(R.string.error_source_quota, Kind.QUOTA.messageRes(PoiProvider.AMAP))
        assertEquals(R.string.error_source_quota_baidu, Kind.QUOTA.messageRes(PoiProvider.BAIDU))
        assertEquals(R.string.error_source_network, Kind.NETWORK.messageRes(PoiProvider.BAIDU))
        assertEquals(R.string.error_source_parse, Kind.PARSE.messageRes(PoiProvider.BAIDU))
    }

    @Test
    fun `the address hint names the selected provider`() {
        assertEquals(R.string.home_address_needs_key, PoiProvider.AMAP.addressNeedsKeyRes())
        assertEquals(R.string.home_address_needs_key_baidu, PoiProvider.BAIDU.addressNeedsKeyRes())
    }
}
