package com.koubeiradar.agent.importer

import com.koubeiradar.agent.util.arrayOrNull
import com.koubeiradar.agent.util.finiteDoubleOrNull
import com.koubeiradar.agent.util.isNullOrJsonNull
import com.koubeiradar.agent.util.objectOrNull
import com.koubeiradar.agent.util.stringOrNull
import com.koubeiradar.agent.util.toCleanText
import com.koubeiradar.core.model.Platform
import com.koubeiradar.core.model.Review
import com.koubeiradar.core.model.Shop
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Turns what the model reported into [Shop]s. The model is not trusted either: every number is range-checked,
 * texts are cleaned and length-limited, and the same shop reported twice becomes one.
 */
internal object ImportSanitizer {
    private const val SOURCE = "import"
    private const val MAX_REVIEW_CHARS = 300
    private const val MAX_REVIEWS = 30
    private const val ID_PREFIX = "import:"
    private const val ID_HEX_CHARS = 12
    private const val MAX_NAME_CHARS = 80
    private const val MAX_CATEGORY_CHARS = 20
    private const val MAX_TAGS = 8
    private const val MAX_TAG_CHARS = 20

    // Plausibility limits: a number beyond them is a misread (or an injected figure) and is left empty.
    private const val MAX_COUNT = 10_000_000
    private const val MAX_DISTANCE_M = 200_000
    private const val MAX_MINUTES = 600
    private const val MAX_MONEY = 10_000.0

    /** Counters for the notes the extractor shows the user. */
    class Stats {
        var nameless = 0
        var invalidNumbers = 0
        var merged = 0
    }

    /** `import:` + the first 12 hex digits of SHA-256 of the normalised name: the same shop always gets the same id. */
    fun idFor(name: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(normalizeName(name).toByteArray(Charsets.UTF_8))
        return ID_PREFIX + digest.joinToString("") { "%02x".format(it) }.take(ID_HEX_CHARS)
    }

    /**
     * Lower case, full/half-width forms unified (NFKC), every kind of bracket written as `(` `)`, and everything that is
     * not a letter or digit (whitespace, punctuation, symbols) removed.
     */
    fun normalizeName(name: String): String {
        val text = Normalizer.normalize(name, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            i += Character.charCount(cp)
            val bmp = cp <= Char.MAX_VALUE.code
            when {
                bmp && OPENING_BRACKETS.indexOf(cp.toChar()) >= 0 -> out.append('(')
                bmp && CLOSING_BRACKETS.indexOf(cp.toChar()) >= 0 -> out.append(')')
                Character.isLetterOrDigit(cp) -> out.appendCodePoint(cp)
            }
        }
        return out.toString()
    }

    /** Converts the `shops` array of one `report_shops` call. Entries that are not objects or have no usable name are skipped. */
    fun convert(shops: List<JsonElement>, stats: Stats): List<Shop> = shops.mapNotNull { element ->
        val obj = element.objectOrNull()
        if (obj == null) {
            stats.nameless++
            null
        } else {
            toShop(obj, stats)
        }
    }

    private fun toShop(obj: JsonObject, stats: Stats): Shop? {
        val name = obj["name"].stringOrNull()?.toCleanText()?.take(MAX_NAME_CHARS)?.trim().orEmpty()
        if (normalizeName(name).isEmpty()) {
            stats.nameless++
            return null
        }
        return Shop(
            id = idFor(name),
            name = name,
            source = SOURCE,
            category = obj["category"].stringOrNull()?.toCleanText()?.take(MAX_CATEGORY_CHARS)?.takeIf { it.isNotEmpty() },
            rating = decimal(obj, "rating", stats) { it > 0.0 && it <= 5.0 },
            ratingCount = count(obj, "rating_count", stats, MAX_COUNT),
            monthlySales = count(obj, "monthly_sales", stats, MAX_COUNT),
            avgPrice = decimal(obj, "avg_price", stats) { it > 0.0 && it <= MAX_MONEY },
            distanceMeters = count(obj, "distance_m", stats, MAX_DISTANCE_M),
            deliveryMinutes = count(obj, "delivery_minutes", stats, MAX_MINUTES)?.takeIf { it > 0 },
            deliveryFee = decimal(obj, "delivery_fee", stats) { it >= 0.0 && it <= MAX_MONEY },
            minOrder = decimal(obj, "min_order", stats) { it >= 0.0 && it <= MAX_MONEY },
            tags = obj["tags"].arrayOrNull().orEmpty()
                .mapNotNull { it.stringOrNull()?.toCleanText()?.take(MAX_TAG_CHARS)?.takeIf { tag -> tag.isNotEmpty() } }
                .distinct().take(MAX_TAGS),
            reviews = reviews(obj["reviews"]),
            platforms = platform(obj["platform"].stringOrNull()),
        )
    }

    /** A number that satisfies [valid]; a value that is present but unusable counts as an invalid number. */
    private fun decimal(obj: JsonObject, key: String, stats: Stats, valid: (Double) -> Boolean): Double? {
        val element = obj[key]
        if (element.isNullOrJsonNull()) return null
        val value = element.finiteDoubleOrNull()?.takeIf(valid)
        if (value == null) stats.invalidNumbers++
        return value
    }

    /** A non-negative whole number ("1000+" and other strings that are not plain numbers are rejected). */
    private fun count(obj: JsonObject, key: String, stats: Stats, max: Int): Int? {
        val element = obj[key]
        if (element.isNullOrJsonNull()) return null
        val value = element.finiteDoubleOrNull()?.takeIf { it >= 0.0 && it <= max }?.roundToInt()
        if (value == null) stats.invalidNumbers++
        return value
    }

    private fun reviews(element: JsonElement?): List<Review> =
        element.arrayOrNull().orEmpty().mapNotNull { item ->
            val text = (item.objectOrNull()?.get("text") ?: item).stringOrNull()?.toCleanText()?.take(MAX_REVIEW_CHARS)
            if (text.isNullOrEmpty()) {
                null
            } else {
                val rating = item.objectOrNull()?.get("rating").finiteDoubleOrNull()?.takeIf { it in 1.0..5.0 }
                Review(text = text, rating = rating)
            }
        }.distinctBy { it.text }.take(MAX_REVIEWS)

    private fun platform(value: String?): Set<Platform> {
        val text = value?.trim()?.lowercase(Locale.ROOT).orEmpty()
        return when {
            "meituan" in text || "美团" in text -> setOf(Platform.MEITUAN)
            "eleme" in text || "ele.me" in text || "饿了么" in text -> setOf(Platform.ELEME)
            else -> emptySet()
        }
    }

    /**
     * Merges shops with the same id (same normalised name): the more complete record is kept and its gaps are filled
     * from the others. Order of first appearance is preserved.
     */
    fun mergeDuplicates(shops: List<Shop>, stats: Stats): List<Shop> {
        val groups = shops.groupBy { it.id }
        stats.merged += shops.size - groups.size
        return groups.values.map { group ->
            val best = group.maxBy(::completeness)
            group.filter { it !== best }.fold(best, ::fill)
        }
    }

    private fun completeness(shop: Shop): Int =
        listOf(shop.rating, shop.ratingCount, shop.monthlySales, shop.avgPrice, shop.distanceMeters, shop.deliveryMinutes, shop.deliveryFee, shop.minOrder, shop.category)
            .count { it != null } + minOf(shop.tags.size, 3) + minOf(shop.reviews.size, 5) + shop.platforms.size

    private fun fill(base: Shop, other: Shop): Shop = base.copy(
        category = base.category ?: other.category,
        rating = base.rating ?: other.rating,
        ratingCount = base.ratingCount ?: other.ratingCount,
        monthlySales = base.monthlySales ?: other.monthlySales,
        avgPrice = base.avgPrice ?: other.avgPrice,
        distanceMeters = base.distanceMeters ?: other.distanceMeters,
        deliveryMinutes = base.deliveryMinutes ?: other.deliveryMinutes,
        deliveryFee = base.deliveryFee ?: other.deliveryFee,
        minOrder = base.minOrder ?: other.minOrder,
        tags = (base.tags + other.tags).distinct().take(MAX_TAGS),
        reviews = (base.reviews + other.reviews).distinctBy { it.text }.take(MAX_REVIEWS),
        platforms = base.platforms + other.platforms,
    )

    private const val OPENING_BRACKETS = "([{【〔「『《〈［｛（"
    private const val CLOSING_BRACKETS = ")]}】〕」』》〉］｝）"
}
