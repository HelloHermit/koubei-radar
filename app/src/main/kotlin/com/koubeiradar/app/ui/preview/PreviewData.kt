package com.koubeiradar.app.ui.preview

import com.koubeiradar.core.model.Platform
import com.koubeiradar.core.model.ScoreBreakdown
import com.koubeiradar.core.model.ScoredShop
import com.koubeiradar.core.model.Shop
import com.koubeiradar.core.model.Verdict

/** Sample shops for `@Preview`s. */
internal object PreviewData {
    val recommended = ScoredShop(
        shop = Shop(
            id = "demo:1", name = "老张川菜馆（望京店）", source = "demo", category = "川菜",
            rating = 4.7, ratingCount = 3200, monthlySales = 4200, avgPrice = 32.0,
            distanceMeters = 600, deliveryMinutes = 28, platforms = setOf(Platform.MEITUAN, Platform.ELEME),
        ),
        score = 87.0,
        verdict = Verdict.RECOMMENDED,
        breakdown = ScoreBreakdown(adjustedRating = 4.69, ratingScore = 90.0, confidenceScore = 95.0, convenienceScore = 82.0),
        reasons = listOf("评分 4.7（共 3200 条评价，修正后 4.69）", "距离 600m，约 28 分钟送达"),
    )

    val risky = ScoredShop(
        shop = Shop(
            id = "demo:2", name = "一家名字特别特别长的新开业网红轻食沙拉能量碗专门店", source = "demo", category = "轻食",
            rating = 5.0, ratingCount = 6, avgPrice = 45.0, distanceMeters = 1800, deliveryMinutes = 40,
        ),
        score = 52.0,
        verdict = Verdict.OKAY,
        breakdown = ScoreBreakdown(adjustedRating = 4.5, penalty = 13.0),
        reasons = listOf("人均 ¥45，高于同类中位数 ¥38"),
        warnings = listOf("满分但评价很少，疑似新店/刷评，建议观望", "评价数过少，口碑可信度低"),
    )
}
