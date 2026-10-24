package com.koubeiradar.data.demo

import com.koubeiradar.core.model.Platform
import com.koubeiradar.core.model.Review
import com.koubeiradar.core.model.Shop
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.random.Random

/** A sample shop and where it sits relative to whichever point the user searches from. */
internal class DemoEntry(val shop: Shop, val offsetMeters: Int, val bearingDegrees: Int)

/**
 * The sample data: nine hand-written showcase shops (one per behaviour of the scorer, `docs/DESIGN.md` §3.2) followed by
 * shops generated from [CUISINES] with a fixed seed. Everything is a pure function of [SEED]; all names are fictional.
 */
internal object DemoCatalog {
    const val SOURCE_ID = "demo"
    private const val SEED = 20240611

    val entries: List<DemoEntry> by lazy { Random(SEED).let { rng -> showcase(rng) + generated(rng) } }

    private class Cuisine(val category: String, val dishes: List<String>, val brands: List<String>, val pricePerHead: IntRange)

    private val CUISINES = listOf(
        Cuisine("川菜", listOf("水煮鱼", "回锅肉", "麻婆豆腐", "宫保鸡丁"), listOf("蜀味轩", "川香小厨", "辣妹子川菜馆"), 35..75),
        Cuisine("湘菜", listOf("剁椒鱼头", "小炒黄牛肉", "农家小炒肉"), listOf("湘里人家", "湘味小炒坊", "辣椒王湘菜馆"), 32..68),
        Cuisine("粤菜", listOf("烧鹅饭", "叉烧饭", "虾饺", "肠粉"), listOf("粤香阁茶餐厅", "港式叉烧王", "老广煲仔饭"), 28..66),
        Cuisine("东北菜", listOf("锅包肉", "地三鲜", "酸菜白肉"), listOf("东北大妈家常菜", "老北屯铁锅炖", "关东小馆"), 30..70),
        Cuisine("面食", listOf("牛肉面", "刀削面", "油泼面", "炸酱面"), listOf("西北面馆", "晋味刀削面", "老陕油泼面"), 15..32),
        Cuisine("快餐简餐", listOf("黄焖鸡米饭", "卤肉饭", "盖浇饭"), listOf("大碗黄焖鸡", "好味道盖浇饭", "快乐卤肉饭"), 14..36),
        Cuisine("麻辣烫", listOf("麻辣烫", "麻辣拌", "冒菜"), listOf("辣上瘾麻辣烫", "小胖冒菜", "一串一碗麻辣烫"), 18..42),
        Cuisine("烧烤", listOf("烤串", "烤鱼", "烤茄子"), listOf("夜宵烧烤坊", "炭火烤肉串", "老刘烧烤"), 40..95),
        Cuisine("火锅", listOf("毛肚", "肥牛", "鸭血"), listOf("红汤小火锅", "串串香大排档", "山城小火锅"), 45..110),
        Cuisine("奶茶", listOf("杨枝甘露", "珍珠奶茶", "芝士奶盖"), listOf("茶小满奶茶", "一杯鲜果茶", "半糖主义"), 12..28),
        Cuisine("轻食", listOf("鸡胸沙拉", "三明治", "美式咖啡"), listOf("街角咖啡轻食", "O2轻食沙拉", "早安三明治"), 22..50),
        Cuisine("粥铺", listOf("砂锅粥", "小笼包", "生煎"), listOf("晨光粥铺", "老上海生煎", "家常包子铺"), 15..38),
        Cuisine("日韩料理", listOf("寿司", "石锅拌饭", "炸鸡"), listOf("樱花寿司屋", "首尔炸鸡", "石锅拌饭小馆"), 38..120),
    )
    private const val SHOPS_PER_CUISINE = 3

    private val BRANCHES = listOf("商业街", "地铁站", "大学城", "科技园", "东门", "西街", "南路", "北苑")
    private val STREETS = listOf("建设路", "人民路", "文化路", "新华街", "解放路", "和平大道", "中山路", "长江路")
    private val MIN_ORDERS = listOf(0.0, 10.0, 15.0, 20.0, 25.0, 30.0)

    private val POSITIVE_REVIEWS = listOf(
        "{dish}真的好吃，分量足，吃完很满足，已经回购两次了",
        "第二次点了，{dish}味道很稳定，推荐给大家",
        "送得很准时，包装好，打开还是热的，{dish}不错",
        "食材新鲜，{dish}味道很正，价格也实惠",
        "{dish}有惊喜，比想象中好吃很多，下次还点",
        "{dish}分量足，米饭也给得多，性价比高，推荐",
        "店家很用心，餐盒干净，{dish}好吃，五星好评",
        "骑手很快，{dish}还是热乎的，味道没得说",
        "老顾客了，{dish}一直这个水准，回购无数次",
        "{dish}口味地道，料给得足，实惠又好吃",
    )
    private val NEUTRAL_REVIEWS = listOf(
        "{dish}味道一般，中规中矩，能吃饱",
        "还行吧，{dish}没有想象中那么惊艳，价格正常",
        "送餐速度正常，{dish}口味偏淡，凑合吃",
        "{dish}普普通通，量不大不小",
        "味道还可以，就是等的时间有点长，{dish}没什么特别",
    )
    private val NEGATIVE_REVIEWS = listOf(
        "{dish}太咸了，吃了几口就吃不下，有点后悔",
        "送太慢了，到手都凉了，{dish}口感大打折扣",
        "少送了一份小菜，联系商家态度差，踩雷了",
        "汤洒了一半，包装太差，{dish}味道也一般，避雷",
        "分量缩水了，比以前少很多，{dish}不值这个价",
        "{dish}有点难吃，油太大，不会再点了",
        "漏送了米饭，打电话也没人接，差评",
    )
    private val POSITIVE_STARS = listOf(5.0, 5.0, 4.5, 4.0)
    private val NEUTRAL_STARS = listOf(3.0, 3.5, 4.0)
    private val NEGATIVE_STARS = listOf(1.0, 1.5, 2.0)

    private fun showcase(rng: Random): List<DemoEntry> {
        val bothPlatforms = setOf(Platform.MEITUAN, Platform.ELEME)
        return listOf(
            DemoEntry(
                Shop(
                    id = DemoShopIds.LAOZIHAO, name = "鸿运楼老字号烤鸭(总店)", source = SOURCE_ID, category = "北京菜",
                    rating = 4.7, ratingCount = 3280, monthlySales = 4200, avgPrice = 86.0,
                    deliveryMinutes = 38, deliveryFee = 3.0, minOrder = 30.0, address = "和平大道16号",
                    tags = listOf("烤鸭", "炸酱面", "老字号"), openNow = true, platforms = bothPlatforms,
                    reviews = reviews(rng, 4.7, 12, listOf("烤鸭", "炸酱面")),
                ),
                offsetMeters = 1_250, bearingDegrees = 40,
            ),
            DemoEntry(
                Shop(
                    id = DemoShopIds.NEW_SHOP_PERFECT, name = "新开张·小满轻食", source = SOURCE_ID, category = "轻食",
                    rating = 5.0, ratingCount = 6, monthlySales = 18, avgPrice = 32.0,
                    deliveryMinutes = 28, deliveryFee = 2.0, minOrder = 20.0, address = "文化路52号",
                    tags = listOf("鸡胸沙拉", "三明治"), openNow = true, platforms = setOf(Platform.MEITUAN),
                    reviews = listOf(
                        Review("第一次点，超级好吃！！五星好评！", 5.0),
                        Review("新店开业支持一下，味道真的有惊喜，推荐", 5.0),
                        Review("老板人很好，分量足，下次还会回购", 5.0),
                        Review("好吃好吃好吃，强烈推荐！", 5.0),
                    ),
                ),
                offsetMeters = 620, bearingDegrees = 200,
            ),
            DemoEntry(
                Shop(
                    id = DemoShopIds.HYGIENE_COMPLAINTS, name = "路边小炒王", source = SOURCE_ID, category = "快餐简餐",
                    rating = 4.5, ratingCount = 1260, monthlySales = 980, avgPrice = 28.0,
                    deliveryMinutes = 30, deliveryFee = 2.0, minOrder = 20.0, address = "解放路7号",
                    tags = listOf("炒饭", "宫保鸡丁", "盖浇饭"), openNow = true, platforms = bothPlatforms,
                    reviews = listOf(
                        Review("吃完回家就拉肚子了，跑了好几趟厕所，再也不点了", 1.0),
                        Review("米饭里吃出一根头发，找商家理论，态度差还不肯退款", 1.0),
                        Review("菜里有异物，像是塑料碎片，吓了一跳，避雷", 1.0),
                        Review("同事吃了也拉肚子，怀疑后厨卫生有问题", 2.0),
                        Review("味道还行，分量足，就是总担心卫生", 3.0),
                        Review("送得挺快，味道不错，宫保鸡丁推荐", 4.5),
                        Review("价格实惠，量大，回购过两次", 4.5),
                        Review("炒饭好吃，量很大", 5.0),
                        Review("味道正常，中规中矩", 3.0),
                        Review("第一次点，味道可以", 4.0),
                    ),
                ),
                offsetMeters = 480, bearingDegrees = 110,
            ),
            DemoEntry(
                Shop(
                    id = DemoShopIds.RATING_ONLY, name = "老街口家常菜馆", source = SOURCE_ID, category = "家常菜",
                    rating = 4.8, avgPrice = 48.0, address = "人民路23号",
                    tags = listOf("家常菜"), openNow = true,
                ),
                offsetMeters = 900, bearingDegrees = 275,
            ),
            DemoEntry(
                Shop(
                    id = DemoShopIds.FAR_AND_SLOW, name = "城郊老灶砂锅菜", source = SOURCE_ID, category = "砂锅菜",
                    rating = 4.6, ratingCount = 540, monthlySales = 260, avgPrice = 52.0,
                    deliveryMinutes = 65, deliveryFee = 8.0, minOrder = 40.0, address = "长江路301号",
                    tags = listOf("砂锅菜", "煲仔饭"), openNow = true, platforms = setOf(Platform.ELEME),
                    reviews = listOf(
                        Review("砂锅菜味道不错，分量足，就是送太慢了，等了一个多小时", 4.0),
                        Review("离得远，送到的时候已经凉了，口感差了不少", 2.0),
                        Review("味道正宗，老板实在，送太慢是唯一的缺点", 4.0),
                        Review("等了快70分钟，饿得不行，味道也就一般", 2.0),
                        Review("砂锅煲仔饭好吃，回购过，配送真的慢", 4.0),
                        Review("分量足，价格实惠，就是送餐时间太长", 4.0),
                        Review("外卖送到汤洒了一点，包装需要改进", 3.0),
                        Review("味道可以，下次打算自己去店里吃", 4.0),
                    ),
                ),
                offsetMeters = 4_600, bearingDegrees = 300,
            ),
            DemoEntry(
                Shop(
                    id = DemoShopIds.BARGAIN, name = "巷尾一碗面", source = SOURCE_ID, category = "面食",
                    rating = 4.6, ratingCount = 800, monthlySales = 1500, avgPrice = 18.0,
                    deliveryMinutes = 25, deliveryFee = 1.0, minOrder = 12.0, address = "新华街9号",
                    tags = listOf("牛肉面", "炸酱面"), openNow = true, platforms = bothPlatforms,
                    reviews = reviews(rng, 4.6, 9, listOf("牛肉面", "炸酱面")),
                ),
                offsetMeters = 380, bearingDegrees = 75,
            ),
            DemoEntry(
                Shop(
                    id = DemoShopIds.RESTING, name = "晚风夜宵烧烤", source = SOURCE_ID, category = "烧烤",
                    rating = 4.4, ratingCount = 980, monthlySales = 600, avgPrice = 65.0,
                    deliveryMinutes = 40, deliveryFee = 3.0, minOrder = 30.0, address = "中山路128号",
                    tags = listOf("烤串", "烤鱼"), openNow = false, platforms = setOf(Platform.ELEME),
                ),
                offsetMeters = 1_400, bearingDegrees = 250,
            ),
            DemoEntry(
                Shop(
                    id = DemoShopIds.UNRATED, name = "初来乍到麻辣烫", source = SOURCE_ID, category = "麻辣烫",
                    avgPrice = 22.0, deliveryMinutes = 30, deliveryFee = 2.0, minOrder = 15.0, address = "建设路66号",
                    tags = listOf("麻辣烫", "麻辣拌"), openNow = true, platforms = setOf(Platform.MEITUAN),
                ),
                offsetMeters = 700, bearingDegrees = 330,
            ),
            DemoEntry(
                Shop(
                    id = DemoShopIds.FEW_HIGH, name = "阿芳手作甜品", source = SOURCE_ID, category = "甜品",
                    rating = 4.9, ratingCount = 25, monthlySales = 40, avgPrice = 35.0,
                    deliveryMinutes = 33, deliveryFee = 3.0, minOrder = 25.0, address = "文化路88号",
                    tags = listOf("杨枝甘露", "双皮奶"), openNow = true, platforms = setOf(Platform.MEITUAN),
                    reviews = reviews(rng, 4.9, 6, listOf("杨枝甘露", "双皮奶")),
                ),
                offsetMeters = 1_750, bearingDegrees = 150,
            ),
        )
    }

    /**
     * Showcase traits stay unique to the showcase shops: generated shops always have a rating, a rating count and sales,
     * are never closed, never take 65 minutes, and rate at most 4.8 when they have fewer than 100 ratings.
     */
    private fun generated(rng: Random): List<DemoEntry> = List(CUISINES.size * SHOPS_PER_CUISINE) { index ->
        val cuisine = CUISINES[index % CUISINES.size]
        val brand = cuisine.brands[index / CUISINES.size]
        val name = if (rng.nextInt(3) == 0) "$brand(${BRANCHES.random(rng)}店)" else brand
        val offset = if (index % 7 == 6) rng.nextInt(3_000, 4_300) else rng.nextInt(160, 3_000)
        val ratingCount = logUniform(rng, 20, 5_000)
        val rating = roundToTenth(rng.nextDouble(3.8, if (ratingCount < 100) 4.8 else 4.9))
        val dishes = cuisine.dishes.shuffled(rng).take(2 + rng.nextInt(2))
        DemoEntry(
            Shop(
                id = "$SOURCE_ID:${index + 1}", name = name, source = SOURCE_ID, category = cuisine.category,
                rating = rating, ratingCount = ratingCount,
                monthlySales = max(10, (ratingCount * rng.nextDouble(0.1, 0.6) / 10).roundToInt() * 10),
                avgPrice = rng.nextInt(cuisine.pricePerHead.first, cuisine.pricePerHead.last + 1).toDouble(),
                deliveryMinutes = (18 + offset / 130 + rng.nextInt(10)).coerceIn(20, 60),
                deliveryFee = (offset / 1_000 + rng.nextInt(4)).toDouble(),
                minOrder = MIN_ORDERS.random(rng),
                address = "${STREETS.random(rng)}${rng.nextInt(1, 200)}号",
                tags = dishes,
                reviews = if (index % 2 == 0) reviews(rng, rating, 6 + rng.nextInt(10), dishes) else emptyList(),
                openNow = if (rng.nextInt(10) == 0) null else true,
                platforms = when (rng.nextInt(100)) {
                    in 0..34 -> setOf(Platform.MEITUAN)
                    in 35..59 -> setOf(Platform.ELEME)
                    else -> setOf(Platform.MEITUAN, Platform.ELEME)
                },
            ),
            offsetMeters = offset, bearingDegrees = rng.nextInt(360),
        )
    }

    /**
     * Review texts whose tone follows [rating]: better shops draw fewer complaints. The complaint pool deliberately
     * contains no hygiene red-flag words - only the hygiene showcase shop has those.
     */
    private fun reviews(rng: Random, rating: Double, count: Int, dishes: List<String>): List<Review> {
        val complaintShare = ((4.9 - rating) * 0.28).coerceIn(0.04, 0.35)
        val neutralShare = 0.18
        return List(count) { index ->
            val roll = rng.nextDouble()
            val (texts, stars) = when {
                roll < complaintShare -> NEGATIVE_REVIEWS to NEGATIVE_STARS
                roll < complaintShare + neutralShare -> NEUTRAL_REVIEWS to NEUTRAL_STARS
                else -> POSITIVE_REVIEWS to POSITIVE_STARS
            }
            Review(
                text = texts.random(rng).replace("{dish}", dishes.random(rng)),
                rating = stars.random(rng).takeUnless { index % 5 == 4 },
                hasPhotos = rng.nextInt(4) == 0,
            )
        }
    }

    private fun logUniform(rng: Random, min: Int, max: Int): Int =
        exp(ln(min.toDouble()) + rng.nextDouble() * (ln(max.toDouble()) - ln(min.toDouble()))).roundToInt()

    private fun roundToTenth(value: Double): Double = (value * 10).roundToInt() / 10.0
}
