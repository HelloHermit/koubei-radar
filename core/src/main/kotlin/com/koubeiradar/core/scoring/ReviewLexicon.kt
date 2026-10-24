package com.koubeiradar.core.scoring

/**
 * Offline vocabulary for the review analysis (Simplified Chinese, takeout context), see DESIGN.md §4.5.
 *
 * Terms are matched as plain substrings, longest match first, without overlaps, so the lists are curated to avoid
 * words that are common inside harmless phrases: there is no bare 咸 (咸蛋黄), 虫 (虫草), 脏 (内脏) or 无味 (无味精).
 * The four lists must stay disjoint; every term is lower case and trimmed.
 */
internal object ReviewLexicon {

    val positive: List<String> = listOf(
        // taste
        "好吃", "美味", "味道好", "味道棒", "鲜美", "鲜香", "鲜嫩", "入味", "酥脆", "软糯", "爽口", "下饭", "地道", "正宗", "解馋",
        // portion and value
        "分量足", "份量足", "量大", "料足", "够吃", "实惠", "划算", "超值", "物美价廉", "性价比高", "良心",
        // freshness and hygiene
        "新鲜", "干净", "整洁",
        // packaging and temperature
        "包装好", "包装严实", "密封好", "保温", "热乎", "热腾腾",
        // delivery
        "准时", "按时", "及时", "很快", "超快", "送得快", "配送快", "速度快", "提前送达",
        // service
        "态度好", "服务好", "周到", "热情", "贴心",
        // overall
        "推荐", "回购", "复购", "回头客", "好评", "满意", "惊喜", "喜欢", "必点", "爱了", "绝了", "太棒", "不错",
        "没话说", "没得说", "无可挑剔", "宝藏", "种草", "值得", "yyds",
    )

    val negative: List<String> = listOf(
        // taste
        "难吃", "不好吃", "不太好吃", "不怎么好吃", "不是很好吃", "不是特别好吃", "不算好吃", "不够好吃", "没那么好吃", "没怎么好吃", "味道不好", "味道不行", "味道差", "口味差", "太咸", "偏咸", "很咸", "齁咸", "齁甜", "太油", "油腻", "太腻", "太甜",
        "太辣", "太淡", "寡淡", "没味道", "怪味", "难以下咽", "想吐", "不正宗",
        // temperature and texture
        "凉了", "凉透", "冷了", "冷掉", "不热", "太硬", "发硬", "太柴", "太干了", "干巴巴", "没熟", "夹生",
        // portion and value
        "分量少", "份量少", "分量小", "份量小", "太少", "缩水", "吃不饱", "缺斤少两", "太贵", "性价比低", "性价比不高", "不划算",
        "智商税", "宰客",
        // delivery
        "送太慢", "送得慢", "配送慢", "送餐慢", "太慢", "超时", "迟到", "等太久", "等了很久", "等了半天", "晚点了", "送晚了",
        "漏送", "少送", "漏了", "漏汤", "洒了", "洒漏", "打翻", "包装差", "包装破", "破损",
        // service
        "态度差", "态度不好", "态度恶劣", "服务差", "没礼貌", "不耐烦", "爱答不理", "骂人",
        // overall
        "踩雷", "避雷", "避坑", "后悔", "失望", "不推荐", "不满意", "不喜欢", "不值", "不会再点", "不会再来", "不会回购",
        "再也不", "差评", "坑人", "被坑", "恶心",
    )

    /** Hygiene and food-safety terms; any hit is far more serious than an ordinary complaint. */
    val redFlags: List<String> = listOf(
        // illness
        "拉肚子", "拉稀", "腹泻", "肚子疼", "肚子痛", "上吐下泻", "呕吐", "食物中毒", "吃坏肚子", "肠胃炎",
        // foreign objects and pests
        "异物", "头发", "毛发", "苍蝇", "蟑螂", "老鼠", "蜘蛛", "虫子", "小虫", "飞虫", "有虫", "蛆虫", "有蛆", "虫卵",
        "石子", "钢丝", "铁丝", "玻璃渣", "烟头", "创可贴", "塑料片",
        // spoilage
        "变质", "发馊", "馊了", "馊味", "发霉", "霉变", "霉味", "长毛", "过期", "隔夜", "地沟油", "臭了", "发臭", "异味",
        "腐烂", "变味", "不新鲜", "不太新鲜", "不是很新鲜", "不怎么新鲜", "不够新鲜", "不太干净", "不是很干净", "不怎么干净", "不够干净",
        // cleanliness
        "不干净", "不卫生", "卫生差", "卫生问题", "卫生堪忧", "太脏", "很脏", "脏乱",
    )

    /** Harmless phrases that contain a real term; they are consumed without scoring. */
    val neutral: List<String> = listOf(
        "优惠券过期", "代金券过期", "红包过期", "券过期", "券已过期", "会员过期", "活动过期", "过期不候", "天冷了", "天凉了", "天气冷了", "天气凉了", "有虫草", "虫草",
    )

    private val order: Map<String, Int> =
        (positive + negative + redFlags + neutral).withIndex().associate { (index, term) -> term to index }

    /** Position of [term] in the combined list; used as a deterministic tie-breaker. */
    fun indexOf(term: String): Int = order[term] ?: Int.MAX_VALUE
}
