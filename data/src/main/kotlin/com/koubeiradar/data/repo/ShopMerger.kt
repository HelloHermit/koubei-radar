package com.koubeiradar.data.repo

import com.koubeiradar.core.model.Shop
import java.text.Normalizer
import java.util.Locale

/** Matches imported shops to nearby ones by name and combines the two (`docs/DESIGN.md` §3.3). */
internal object ShopMerger {
    private val TRAILING_BRANCH = Regex("""\([^()]*\)\s*$""")

    /**
     * A shop name reduced to its letters and digits, lower-cased, with full-width forms folded to half-width.
     * [full] keeps a branch suffix's text ("老王川菜馆(中关村店)" -> "老王川菜馆中关村店"); [base] drops a trailing
     * bracketed suffix ("老王川菜馆"). Without a suffix both are equal.
     */
    class NameKey(val full: String, val base: String, val hasBranchSuffix: Boolean)

    fun nameKey(name: String): NameKey {
        val unified = unifyBrackets(Normalizer.normalize(name, Normalizer.Form.NFKC).lowercase(Locale.ROOT))
        val full = unified.letters()
        val base = TRAILING_BRANCH.find(unified)?.let { unified.removeRange(it.range).letters() }
        return if (base.isNullOrEmpty() || base == full) NameKey(full, full, hasBranchSuffix = false) else NameKey(full, base, hasBranchSuffix = true)
    }

    /**
     * The pool as the UI sees it: every [nearby] shop - replaced in place by the imported shop it matched - followed by the
     * imported shops that matched nothing. An imported shop matches the nearby shop with the same [NameKey.full]; failing
     * that, the single nearby shop with the same [NameKey.base] (when both names carry a branch suffix they are different
     * branches, not a match). A nearby shop is claimed by at most one imported shop.
     */
    fun merge(nearby: List<Shop>, imported: List<Shop>): List<Shop> {
        if (imported.isEmpty()) return nearby
        val index = NearbyIndex(nearby)
        val combined = HashMap<Int, Shop>()
        val standalone = ArrayList<Shop>()
        for (shop in imported) {
            val match = index.match(nameKey(shop.name), claimed = combined.keys)
            if (match == null) standalone += shop else combined[match] = combine(shop, nearby[match])
        }
        return nearby.mapIndexed { position, shop -> combined[position] ?: shop } + standalone
    }

    /**
     * Takeout-specific data comes from the [imported] shop (id, name and source too); anything it leaves unknown is filled
     * from the [nearby] shop, which is the only one that knows where the shop is.
     */
    fun combine(imported: Shop, nearby: Shop): Shop = imported.copy(
        category = imported.category ?: nearby.category,
        rating = imported.rating ?: nearby.rating,
        ratingCount = imported.ratingCount ?: nearby.ratingCount,
        monthlySales = imported.monthlySales ?: nearby.monthlySales,
        avgPrice = imported.avgPrice ?: nearby.avgPrice,
        distanceMeters = imported.distanceMeters ?: nearby.distanceMeters,
        deliveryMinutes = imported.deliveryMinutes ?: nearby.deliveryMinutes,
        deliveryFee = imported.deliveryFee ?: nearby.deliveryFee,
        minOrder = imported.minOrder ?: nearby.minOrder,
        address = imported.address ?: nearby.address,
        location = imported.location ?: nearby.location,
        tags = (imported.tags + nearby.tags).distinct(),
        reviews = imported.reviews.ifEmpty { nearby.reviews },
        openNow = imported.openNow ?: nearby.openNow,
        platforms = imported.platforms.ifEmpty { nearby.platforms },
        phone = imported.phone ?: nearby.phone,
    )

    private class NearbyIndex(shops: List<Shop>) {
        private val keys = shops.map { nameKey(it.name) }
        private val byFull = keys.indices.groupBy { keys[it].full }
        private val byBase = keys.indices.groupBy { keys[it].base }

        /** Position of the nearby shop [key] refers to; `null` when there is none, it is taken, or the name is ambiguous. */
        fun match(key: NameKey, claimed: Set<Int>): Int? {
            byFull[key.full]?.let { exact -> return exact.firstOrNull { it !in claimed } }
            val candidates = byBase[key.base].orEmpty().filterNot { key.hasBranchSuffix && keys[it].hasBranchSuffix }
            return candidates.singleOrNull()?.takeIf { it !in claimed }
        }
    }

    private fun unifyBrackets(text: String): String = buildString(text.length) {
        for (c in text) {
            append(
                when (c) {
                    '[', '{', '【', '〔', '「', '『' -> '('
                    ']', '}', '】', '〕', '」', '』' -> ')'
                    else -> c
                },
            )
        }
    }

    private fun String.letters(): String = filter { it.isLetterOrDigit() }
}
