package com.koubeiradar.data.demo

import com.koubeiradar.core.model.GeoPoint
import com.koubeiradar.core.model.Platform
import com.koubeiradar.core.model.Shop
import com.koubeiradar.data.NearbyQuery
import kotlinx.coroutines.test.runTest
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DemoShopSourceTest {
    private val source = DemoShopSource()
    private val center = GeoPoint(lat = 39.9087, lng = 116.3975)
    private val everything = NearbyQuery(center, radiusMeters = 50_000, limit = 1000)

    private suspend fun all(at: GeoPoint = center): List<Shop> = source.nearby(everything.copy(center = at))

    private suspend fun showcase(id: String): Shop = assertNotNull(all().find { it.id == id }, "no showcase shop $id")

    private val hygieneTerms = listOf("拉肚子", "腹泻", "食物中毒", "异物", "头发", "苍蝇", "蟑螂", "变质", "馊", "发霉", "过期", "不新鲜", "不干净")

    private fun Shop.redFlagReviews(terms: List<String> = listOf("拉肚子", "头发", "异物")) =
        reviews.count { review -> terms.any { it in review.text } }

    @Test
    fun `identifies itself`() {
        assertEquals("demo", source.id)
        assertTrue(source.displayName.isNotBlank())
    }

    @Test
    fun `offers at least thirty shops within the default radius`() = runTest {
        val shops = source.nearby(NearbyQuery(center))

        assertTrue(shops.size >= 30, "only ${shops.size} shops")
    }

    @Test
    fun `every shop is a well formed demo shop`() = runTest {
        for (shop in all()) {
            assertEquals("demo", shop.source, shop.id)
            assertTrue(shop.id.startsWith("demo:"), shop.id)
            assertTrue(shop.name.isNotBlank(), shop.id)
            assertNotNull(shop.category, shop.id)
            assertNotNull(shop.location, shop.id)
            assertNotNull(shop.distanceMeters, shop.id)
            shop.rating?.let { assertTrue(it in 0.0..5.0, "${shop.id} rating $it") }
            shop.ratingCount?.let { count -> assertTrue(count >= shop.reviews.size, "${shop.id} has more review texts than ratings") }
        }
    }

    @Test
    fun `ids and names are unique`() = runTest {
        val shops = all()

        assertEquals(shops.size, shops.map { it.id }.toSet().size)
        assertEquals(shops.size, shops.map { it.name }.toSet().size)
    }

    @Test
    fun `contains the old established big shop`() = runTest {
        val shop = showcase(DemoShopIds.LAOZIHAO)

        assertEquals(4.7, shop.rating)
        assertTrue(checkNotNull(shop.ratingCount) >= 3200)
        assertEquals(4200, shop.monthlySales)
    }

    @Test
    fun `contains the new shop with a perfect rating and almost no ratings`() = runTest {
        val shop = showcase(DemoShopIds.NEW_SHOP_PERFECT)

        assertEquals(5.0, shop.rating)
        assertEquals(6, shop.ratingCount)
        assertTrue(shop.reviews.size in 1..5, "a new shop has few reviews")
    }

    @Test
    fun `contains the shop with hygiene complaints`() = runTest {
        val shop = showcase(DemoShopIds.HYGIENE_COMPLAINTS)

        assertEquals(4.5, shop.rating)
        assertTrue(shop.redFlagReviews() >= 3, "only ${shop.redFlagReviews()} complaints")
    }

    @Test
    fun `contains the shop that only has a rating`() = runTest {
        val shop = showcase(DemoShopIds.RATING_ONLY)

        assertEquals(4.8, shop.rating)
        assertNull(shop.ratingCount)
        assertNull(shop.monthlySales)
        assertTrue(shop.reviews.isEmpty())
    }

    @Test
    fun `contains the shop that is far away and slow`() = runTest {
        val shop = showcase(DemoShopIds.FAR_AND_SLOW)

        assertEquals(4.6, shop.rating)
        assertTrue(checkNotNull(shop.distanceMeters) in 4_500..4_700, "${shop.distanceMeters} m")
        assertEquals(65, shop.deliveryMinutes)
    }

    @Test
    fun `contains the cheap treasure`() = runTest {
        val shop = showcase(DemoShopIds.BARGAIN)

        assertEquals(18.0, shop.avgPrice)
        assertEquals(4.6, shop.rating)
        assertEquals(800, shop.ratingCount)
    }

    @Test
    fun `contains the closed shop`() = runTest {
        assertEquals(false, showcase(DemoShopIds.RESTING).openNow)
    }

    @Test
    fun `contains the unrated shop`() = runTest {
        assertNull(showcase(DemoShopIds.UNRATED).rating)
    }

    @Test
    fun `contains the few ratings but high score shop`() = runTest {
        val shop = showcase(DemoShopIds.FEW_HIGH)

        assertEquals(4.9, shop.rating)
        assertEquals(25, shop.ratingCount)
    }

    @Test
    fun `showcase traits belong to the showcase shops alone`() = runTest {
        val shops = all()

        assertEquals(listOf(DemoShopIds.RESTING), shops.filter { it.openNow == false }.map { it.id })
        assertEquals(listOf(DemoShopIds.UNRATED), shops.filter { it.rating == null }.map { it.id })
        assertEquals(listOf(DemoShopIds.NEW_SHOP_PERFECT), shops.filter { it.rating == 5.0 }.map { it.id })
        assertEquals(listOf(DemoShopIds.RATING_ONLY), shops.filter { it.rating != null && it.ratingCount == null }.map { it.id })
        assertEquals(listOf(DemoShopIds.FAR_AND_SLOW), shops.filter { it.deliveryMinutes == 65 }.map { it.id })
        assertEquals(listOf(DemoShopIds.FEW_HIGH), shops.filter { it.rating == 4.9 && (it.ratingCount ?: 0) < 100 }.map { it.id })
        assertEquals(listOf(DemoShopIds.HYGIENE_COMPLAINTS), shops.filter { it.redFlagReviews(hygieneTerms) > 0 }.map { it.id })
        assertEquals(DemoShopIds.SHOWCASE.toSet(), DemoShopIds.SHOWCASE.map { showcase(it).id }.toSet())
    }

    @Test
    fun `other shops follow the documented ranges`() = runTest {
        val others = all().filter { it.id !in DemoShopIds.SHOWCASE }

        assertTrue(others.size >= 21, "only ${others.size} generated shops")
        for (shop in others) {
            assertTrue(checkNotNull(shop.rating) in 3.8..4.9, "${shop.id} rating ${shop.rating}")
            assertTrue(checkNotNull(shop.ratingCount) in 20..5000, "${shop.id} ratings ${shop.ratingCount}")
            assertTrue(checkNotNull(shop.avgPrice) in 12.0..120.0, "${shop.id} price ${shop.avgPrice}")
            assertTrue(checkNotNull(shop.deliveryMinutes) in 20..60, "${shop.id} minutes ${shop.deliveryMinutes}")
            assertNotNull(shop.monthlySales, shop.id)
            assertTrue(shop.platforms.isNotEmpty(), shop.id)
        }
    }

    @Test
    fun `distances are haversine distances from the center between 150 m and 5 km`() = runTest {
        val centers = listOf(center, GeoPoint(31.2304, 121.4737), GeoPoint(-33.8688, 151.2093), GeoPoint(39.9, 179.9995), GeoPoint(0.0, 0.0))
        for (at in centers) {
            for (shop in all(at)) {
                val computed = GeoMath.distanceMeters(at, checkNotNull(shop.location))
                assertTrue(abs(computed - checkNotNull(shop.distanceMeters)) <= 1.0, "${shop.id} at $at: $computed vs ${shop.distanceMeters}")
                assertTrue(checkNotNull(shop.distanceMeters) in 150..5000, "${shop.id} at $at: ${shop.distanceMeters} m")
            }
        }
    }

    @Test
    fun `the same shops move with the center`() = runTest {
        val here = all()
        val there = all(GeoPoint(30.5728, 104.0668))

        assertEquals(here.map { it.id }, there.map { it.id })
        assertEquals(here.map { it.distanceMeters }, there.map { it.distanceMeters })
        assertEquals(here.map { it.copy(location = null) }, there.map { it.copy(location = null) })
        assertTrue(here.zip(there).all { (a, b) -> a.location != b.location })
    }

    @Test
    fun `is sorted nearest first`() = runTest {
        val distances = all().map { checkNotNull(it.distanceMeters) }

        assertEquals(distances.sorted(), distances)
    }

    @Test
    fun `is deterministic across calls and instances`() = runTest {
        val first = source.nearby(everything)

        assertEquals(first, source.nearby(everything))
        assertEquals(first, DemoShopSource().nearby(everything))
    }

    @Test
    fun `honours the radius`() = runTest {
        val everyShop = all()
        val within1km = source.nearby(everything.copy(radiusMeters = 1000))

        assertTrue(within1km.isNotEmpty() && within1km.size < everyShop.size)
        assertTrue(within1km.all { checkNotNull(it.distanceMeters) <= 1000 })
        assertEquals(everyShop.filter { checkNotNull(it.distanceMeters) <= 1000 }, within1km)
        assertEquals(emptyList(), source.nearby(everything.copy(radiusMeters = 100)))
        assertTrue(source.nearby(everything.copy(radiusMeters = 3000)).none { it.id == DemoShopIds.FAR_AND_SLOW })
        assertTrue(source.nearby(everything.copy(radiusMeters = 5000)).any { it.id == DemoShopIds.FAR_AND_SLOW })
    }

    @Test
    fun `honours the limit by keeping the nearest`() = runTest {
        val everyShop = all()

        assertEquals(everyShop.take(5), source.nearby(everything.copy(limit = 5)))
        assertEquals(emptyList(), source.nearby(everything.copy(limit = 0)))
        assertEquals(emptyList(), source.nearby(everything.copy(limit = -1)))
        assertEquals(everyShop.size, source.nearby(everything.copy(limit = everyShop.size + 10)).size)
    }

    @Test
    fun `filters by keyword on name category and tags ignoring case`() = runTest {
        val everyShop = all()
        for (keyword in listOf("川菜", "奶茶", "烤鸭", "面", "o2", "O2")) {
            val expected = everyShop.filter { shop ->
                (listOfNotNull(shop.name, shop.category) + shop.tags).any { it.contains(keyword, ignoreCase = true) }
            }
            assertTrue(expected.isNotEmpty(), "test data should contain '$keyword'")
            assertEquals(expected, source.nearby(everything.copy(keyword = keyword)), keyword)
        }
        assertEquals(emptyList(), source.nearby(everything.copy(keyword = "不存在的菜系")))
        assertTrue(source.nearby(everything.copy(keyword = "o2")).any { it.name.startsWith("O2") }, "matching ignores case")
    }

    @Test
    fun `a blank keyword filters nothing`() = runTest {
        assertEquals(all(), source.nearby(everything.copy(keyword = "  ")))
    }

    @Test
    fun `keyword radius and limit combine`() = runTest {
        val sichuan = source.nearby(everything.copy(keyword = "川菜", radiusMeters = 3000, limit = 2))

        assertEquals(2, sichuan.size)
        assertTrue(sichuan.all { checkNotNull(it.distanceMeters) <= 3000 && "川菜" in (it.name + it.category + it.tags) })
    }

    @Test
    fun `about half of the shops carry realistic reviews`() = runTest {
        val shops = all()
        val withReviews = shops.filter { it.reviews.isNotEmpty() }

        assertTrue(withReviews.size in (shops.size * 40 / 100)..(shops.size * 65 / 100), "${withReviews.size} of ${shops.size}")
        for (shop in withReviews) {
            if (shop.id != DemoShopIds.NEW_SHOP_PERFECT) assertTrue(shop.reviews.size in 6..15, "${shop.id} has ${shop.reviews.size} reviews")
            for (review in shop.reviews) {
                assertTrue(review.text.length >= 8 && review.text.any { it in '一'..'鿿' }, "${shop.id}: '${review.text}'")
                review.rating?.let { assertTrue(it in 1.0..5.0, "${shop.id}: rating $it") }
                assertTrue("{" !in review.text, "unfilled template: ${review.text}")
            }
        }
    }

    @Test
    fun `reviews mix praise neutral remarks and complaints`() = runTest {
        val texts = all().flatMap { it.reviews }.map { it.text }

        assertTrue(texts.any { "好吃" in it || "推荐" in it }, "praise")
        assertTrue(texts.any { "一般" in it || "还行" in it }, "neutral")
        assertTrue(texts.any { "太咸" in it || "送太慢" in it || "少送" in it }, "complaints")
        assertTrue(all().flatMap { it.reviews }.mapNotNull { it.rating }.let { stars -> stars.any { it <= 2.0 } && stars.any { it >= 4.5 } })
    }

    @Test
    fun `platforms vary between meituan eleme and both`() = runTest {
        val platformSets = all().map { it.platforms }.toSet()

        assertTrue(setOf(Platform.MEITUAN) in platformSets)
        assertTrue(setOf(Platform.ELEME) in platformSets)
        assertTrue(setOf(Platform.MEITUAN, Platform.ELEME) in platformSets)
    }
}
