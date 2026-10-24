package com.koubeiradar.data

import com.koubeiradar.core.model.Platform

/**
 * Delivery apps the UI can hand a shop over to. We deliberately do NOT rely on private deep-link schemes
 * (they are undocumented and change); the app copies the shop name to the clipboard and launches the app
 * by package name so the user pastes it into the app's search box.
 */
data class DeliveryApp(
    val platform: Platform,
    val label: String,
    /** Candidate package names, first installed one wins. Verify on device. */
    val packageNames: List<String>,
)

object DeliveryApps {
    val all: List<DeliveryApp> = listOf(
        DeliveryApp(Platform.MEITUAN, "美团外卖", listOf("com.sankuai.meituan.takeoutnew", "com.sankuai.meituan")),
        DeliveryApp(Platform.ELEME, "饿了么", listOf("me.ele")),
    )

    fun of(platform: Platform): DeliveryApp = all.first { it.platform == platform }
}
