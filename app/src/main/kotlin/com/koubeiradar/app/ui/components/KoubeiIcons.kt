package com.koubeiradar.app.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** The few Material icons outside the core icon set that the screens need, as 24dp vectors. */
object KoubeiIcons {
    /** Filter sliders. */
    val Tune: ImageVector by lazy {
        icon("Tune", "M3,17v2h6v-2L3,17zM3,5v2h10L13,5L3,5zM13,21v-2h8v-2h-8v-2h-2v6h2zM7,9v2L3,11v2h4v2h2L9,9L7,9zM21,13v-2L11,11v2h10zM15,9h2L17,7h4L21,5h-4L17,3h-2v6z")
    }

    /** Sparkles: the AI agent. */
    val Sparkles: ImageVector by lazy {
        icon(
            "Sparkles",
            "M19,9l1.25,-2.75L23,5l-2.75,-1.25L19,1l-1.25,2.75L15,5l2.75,1.25L19,9zM19,15l-1.25,2.75L15,19l2.75,1.25L19,23l1.25,-2.75L23,19l-2.75,-1.25L19,15zM11.5,9.5L9,4L6.5,9.5L1,12l5.5,2.5L9,20l2.5,-5.5L17,12L11.5,9.5zM9.99,12.99L9,15.17l-0.99,-2.18L5.83,12l2.18,-0.99L9,8.83l0.99,2.18L12.17,12L9.99,12.99z",
        )
    }

    /** Arrow into a tray: import. */
    val Download: ImageVector by lazy { icon("Download", "M19,9h-4V3H9v6H5l7,7 7,-7zM5,18v2h14v-2H5z") }

    private fun icon(name: String, pathData: String): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
            .addPath(addPathNodes(pathData), fill = SolidColor(Color.Black))
            .build()
}
