package com.koubeiradar.data.demo

/** Stable ids of the showcase shops of [DemoShopSource] (`docs/DESIGN.md` §3.2), for demos and integration tests. */
object DemoShopIds {
    /** 老字号大店: rating 4.7, 3200+ ratings, 4200 monthly sales. */
    const val LAOZIHAO = "demo:laozihao"

    /** 新店满分: rating 5.0 from only 6 ratings. */
    const val NEW_SHOP_PERFECT = "demo:newshop"

    /** 卫生投诉店: rating 4.5, but several reviews mention diarrhoea / hair / foreign objects. */
    const val HYGIENE_COMPLAINTS = "demo:hygiene"

    /** 仅有评分（高德式）: rating 4.8 with neither rating count nor monthly sales. */
    const val RATING_ONLY = "demo:ratingonly"

    /** 又远又慢: rating 4.6, 4.6 km away, 65 minutes delivery. */
    const val FAR_AND_SLOW = "demo:farslow"

    /** 平价宝藏: ¥18 per person, rating 4.6, 800 ratings. */
    const val BARGAIN = "demo:bargain"

    /** 休息中: `openNow == false`. */
    const val RESTING = "demo:resting"

    /** 无评分: `rating == null`. */
    const val UNRATED = "demo:unrated"

    /** 少量高分: rating 4.9 from 25 ratings. */
    const val FEW_HIGH = "demo:fewhigh"

    val SHOWCASE: List<String> = listOf(
        LAOZIHAO, NEW_SHOP_PERFECT, HYGIENE_COMPLAINTS, RATING_ONLY, FAR_AND_SLOW, BARGAIN, RESTING, UNRATED, FEW_HIGH,
    )
}
