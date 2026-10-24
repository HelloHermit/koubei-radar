package com.koubeiradar.data.repo

import com.koubeiradar.core.model.GeoPoint
import com.koubeiradar.core.model.Platform
import com.koubeiradar.core.model.Review
import com.koubeiradar.core.model.Shop
import com.koubeiradar.data.repo.ShopMerger.nameKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ShopMergerTest {
    private fun nearby(id: String, name: String) = Shop(id = "amap:$id", name = name, source = "amap")
    private fun imported(id: String, name: String) = Shop(id = "import:$id", name = name, source = "import")

    private fun merge(nearby: List<Shop>, imported: List<Shop>) = ShopMerger.merge(nearby, imported)

    // ---- name normalisation ----

    @Test
    fun `names are compared ignoring case whitespace punctuation and width`() {
        val same = listOf(
            "老王川菜馆", "老王 川菜馆", "老王·川菜馆", "老王川菜馆！", "　老王川菜馆　", "老王-川菜馆",
        )
        assertEquals(1, same.map { nameKey(it).full }.toSet().size)
        assertEquals(nameKey("KFC Express").full, nameKey("ｋｆｃ　ｅｘｐｒｅｓｓ").full)
        assertEquals(nameKey("kfcexpress").full, nameKey("KFC Express").full)
        assertTrue(nameKey("老王川菜馆").full != nameKey("老李川菜馆").full)
    }

    @Test
    fun `all bracket styles are treated alike`() {
        val keys = listOf("老王川菜馆(中关村店)", "老王川菜馆（中关村店）", "老王川菜馆【中关村店】", "老王川菜馆[中关村店]", "老王川菜馆 ( 中关村店 )")
            .map { nameKey(it) }

        assertEquals(1, keys.map { it.full }.toSet().size)
        assertEquals(setOf("老王川菜馆"), keys.map { it.base }.toSet())
        assertTrue(keys.all { it.hasBranchSuffix })
    }

    @Test
    fun `only a trailing bracketed group is a branch suffix`() {
        val plain = nameKey("老王川菜馆")
        assertEquals("老王川菜馆", plain.base)
        assertFalse(plain.hasBranchSuffix)

        val middle = nameKey("老王(新)川菜馆")
        assertEquals("老王新川菜馆", middle.full)
        assertEquals("老王新川菜馆", middle.base)
        assertFalse(middle.hasBranchSuffix)

        val onlyBrackets = nameKey("(总店)")
        assertEquals("总店", onlyBrackets.full)
        assertEquals("总店", onlyBrackets.base)
        assertFalse(onlyBrackets.hasBranchSuffix)
    }

    // ---- matching ----

    @Test
    fun `an imported shop merges into the nearby shop with the same name and keeps its own id`() {
        val merged = merge(listOf(nearby("1", "老王川菜馆")), listOf(imported("a", "老王川菜馆")))

        assertEquals(listOf("import:a"), merged.map { it.id })
    }

    @Test
    fun `full width and half width brackets still match`() {
        val merged = merge(listOf(nearby("1", "老王川菜馆(中关村店)")), listOf(imported("a", "老王川菜馆（中关村店）")))

        assertEquals(listOf("import:a"), merged.map { it.id })
    }

    @Test
    fun `an import without the branch suffix matches the one nearby branch`() {
        val merged = merge(listOf(nearby("1", "老王川菜馆(中关村店)")), listOf(imported("a", "老王川菜馆")))

        assertEquals(listOf("import:a"), merged.map { it.id })
    }

    @Test
    fun `an import with a branch suffix matches the one nearby shop without it`() {
        val merged = merge(listOf(nearby("1", "老王川菜馆")), listOf(imported("a", "老王川菜馆(中关村店)")))

        assertEquals(listOf("import:a"), merged.map { it.id })
    }

    @Test
    fun `an ambiguous branch match merges nothing`() {
        val nearbyShops = listOf(nearby("1", "老王川菜馆(中关村店)"), nearby("2", "老王川菜馆(五道口店)"))

        val merged = merge(nearbyShops, listOf(imported("a", "老王川菜馆")))

        assertEquals(listOf("amap:1", "amap:2", "import:a"), merged.map { it.id })
    }

    @Test
    fun `different branches of the same brand are different shops`() {
        val merged = merge(listOf(nearby("1", "老王川菜馆(中关村店)")), listOf(imported("a", "老王川菜馆(五道口店)")))

        assertEquals(listOf("amap:1", "import:a"), merged.map { it.id })
    }

    @Test
    fun `an exact match is preferred over a branch match`() {
        val nearbyShops = listOf(nearby("1", "老王川菜馆"), nearby("2", "老王川菜馆(中关村店)"))

        val merged = merge(nearbyShops, listOf(imported("a", "老王川菜馆(中关村店)")))

        assertEquals(listOf("amap:1", "import:a"), merged.map { it.id })
    }

    @Test
    fun `two imports cannot claim the same nearby shop`() {
        val merged = merge(
            listOf(nearby("1", "老王川菜馆")),
            listOf(imported("a", "老王川菜馆"), imported("b", "老王川菜馆")),
        )

        assertEquals(listOf("import:a", "import:b"), merged.map { it.id })
    }

    @Test
    fun `a claimed exact match does not fall back to a looser match`() {
        val nearbyShops = listOf(nearby("1", "老王川菜馆(中关村店)"), nearby("2", "老王川菜馆"))

        val merged = merge(nearbyShops, listOf(imported("a", "老王川菜馆(中关村店)"), imported("b", "老王川菜馆(中关村店)")))

        assertEquals(listOf("import:a", "amap:2", "import:b"), merged.map { it.id })
    }

    @Test
    fun `different names never merge`() {
        val merged = merge(listOf(nearby("1", "老王川菜馆")), listOf(imported("a", "老李川菜馆")))

        assertEquals(listOf("amap:1", "import:a"), merged.map { it.id })
    }

    @Test
    fun `without imports the nearby list is returned as is`() {
        val nearbyShops = listOf(nearby("1", "甲"), nearby("2", "乙"))

        assertSame(nearbyShops, merge(nearbyShops, emptyList()))
    }

    @Test
    fun `keeps nearby order with merged shops in place and unmatched imports last`() {
        val nearbyShops = listOf(nearby("1", "甲"), nearby("2", "乙"), nearby("3", "丙"))

        val merged = merge(nearbyShops, listOf(imported("x", "新店"), imported("b", "丙"), imported("a", "甲")))

        assertEquals(listOf("import:a", "amap:2", "import:b", "import:x"), merged.map { it.id })
    }

    // ---- combining ----

    @Test
    fun `imported takeout fields win over nearby ones`() {
        val nearbyShop = Shop(
            id = "amap:1", name = "老王川菜馆(中关村店)", source = "amap", category = "川菜馆", rating = 4.4, ratingCount = 10,
            monthlySales = 20, avgPrice = 60.0, distanceMeters = 300, deliveryMinutes = 50, deliveryFee = 9.0, minOrder = 40.0,
            reviews = listOf(Review("附近评价")), platforms = setOf(Platform.ELEME), openNow = false,
        )
        val importedShop = Shop(
            id = "import:a", name = "老王川菜馆", source = "import", category = "四川菜", rating = 4.8, ratingCount = 3200,
            monthlySales = 4200, avgPrice = 58.0, distanceMeters = 350, deliveryMinutes = 30, deliveryFee = 2.0, minOrder = 20.0,
            reviews = listOf(Review("导入评价", 5.0)), platforms = setOf(Platform.MEITUAN), openNow = true,
        )

        val merged = ShopMerger.combine(importedShop, nearbyShop)

        assertEquals(importedShop, merged)
    }

    @Test
    fun `nearby data fills what the import lacks`() {
        val nearbyShop = Shop(
            id = "amap:1", name = "老王川菜馆(中关村店)", source = "amap", category = "川菜馆", rating = 4.4, avgPrice = 60.0,
            distanceMeters = 300, address = "中关村大街1号", location = GeoPoint(39.99, 116.31), tags = listOf("水煮鱼", "川菜馆"),
            reviews = listOf(Review("附近评价")), openNow = true, platforms = setOf(Platform.ELEME), phone = "010-12345678",
        )
        val importedShop = Shop(id = "import:a", name = "老王川菜馆", source = "import", ratingCount = 3200, monthlySales = 4200, tags = listOf("招牌菜", "水煮鱼"))

        val merged = ShopMerger.combine(importedShop, nearbyShop)

        assertEquals("import:a", merged.id)
        assertEquals("老王川菜馆", merged.name)
        assertEquals("import", merged.source)
        assertEquals("川菜馆", merged.category)
        assertEquals("中关村大街1号", merged.address)
        assertEquals(GeoPoint(39.99, 116.31), merged.location)
        assertEquals("010-12345678", merged.phone)
        assertEquals(4.4, merged.rating)
        assertEquals(60.0, merged.avgPrice)
        assertEquals(300, merged.distanceMeters)
        assertEquals(true, merged.openNow)
        assertEquals(listOf(Review("附近评价")), merged.reviews)
        assertEquals(setOf(Platform.ELEME), merged.platforms)
        assertEquals(3200, merged.ratingCount)
        assertEquals(4200, merged.monthlySales)
        assertEquals(listOf("招牌菜", "水煮鱼", "川菜馆"), merged.tags)
    }
}
