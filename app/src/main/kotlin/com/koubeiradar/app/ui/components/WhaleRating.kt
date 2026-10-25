package com.koubeiradar.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.koubeiradar.app.ui.theme.KoubeiTheme
import com.koubeiradar.app.ui.theme.koubeiColors

/**
 * A rating on the 0..5 scale as five whales, one whale per point; a fraction fills the last whale partly, from the head end.
 * Purely decorative: the screen reader gets the number from the surrounding row, so the whales carry no description.
 * [iconWidth] is the width of one whale; its height follows the drawing's proportions.
 */
@Composable
fun WhaleRating(rating: Double, modifier: Modifier = Modifier, iconWidth: Dp = 16.dp) {
    val filled = MaterialTheme.koubeiColors.ratingFill
    val empty = MaterialTheme.colorScheme.outlineVariant
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        repeat(WHALE_COUNT) { index ->
            val fill = whaleFill(rating, index)
            Box(Modifier.size(width = iconWidth, height = iconWidth * (WHALE_VIEWPORT_HEIGHT / WHALE_VIEWPORT_WIDTH))) {
                Icon(Whale, contentDescription = null, tint = empty, modifier = Modifier.fillMaxSize())
                if (fill > 0f) {
                    Icon(Whale, contentDescription = null, tint = filled, modifier = Modifier.fillMaxSize().clipToFill(fill))
                }
            }
        }
    }
}

/** How much of the whale at [index] (0-based) a [rating] fills, 0..1. Out-of-range ratings saturate instead of overflowing. */
internal fun whaleFill(rating: Double, index: Int): Float =
    if (rating.isNaN()) 0f else (rating - index).coerceIn(0.0, 1.0).toFloat()

/**
 * Where the clip edge sits (as a share of the icon width) for a whale filled by [fill]: the drawing does not span the whole
 * viewport, so a plain `fill * width` would show an "80 %" whale as nearly full.
 */
internal fun clipEdge(fill: Float): Float = WHALE_LEFT + (WHALE_RIGHT - WHALE_LEFT) * fill

private fun Modifier.clipToFill(fill: Float): Modifier = drawWithContent {
    clipRect(right = size.width * clipEdge(fill)) { this@drawWithContent.drawContent() }
}

private const val WHALE_COUNT = 5

private const val WHALE_VIEWPORT_WIDTH = 24f
private const val WHALE_VIEWPORT_HEIGHT = 16.8f

// The drawing runs from x = 1 to x = 23 of the 24-unit viewport.
private const val WHALE_LEFT = 1f / WHALE_VIEWPORT_WIDTH
private const val WHALE_RIGHT = 23f / WHALE_VIEWPORT_WIDTH

/**
 * A whale in profile, head left, tail raised on the right, with a two-stroke spout. The body, the stalk and the two flukes
 * overlap and are filled as one shape (non-zero winding); the eye is wound the other way round, which cuts it out so the
 * card colour shows through in both themes. Drawn for this app.
 */
private val Whale: ImageVector = ImageVector.Builder(
    name = "Whale",
    defaultWidth = WHALE_VIEWPORT_WIDTH.dp,
    defaultHeight = WHALE_VIEWPORT_HEIGHT.dp,
    viewportWidth = WHALE_VIEWPORT_WIDTH,
    viewportHeight = WHALE_VIEWPORT_HEIGHT,
).apply {
    addPath(
        pathData = PathParser().parsePathString(
            // body
            "M15.43 10.89C15.43 13.71 12.2 16 8.21 16C4.23 16 1 13.71 1 10.89C1 8.06 4.23 5.77 8.21 5.77" +
                "C12.2 5.77 15.43 8.06 15.43 10.89Z" +
                // tail stalk
                "M12.58 8.03C16.27 8.2 17.95 6.52 17.95 3.5C18.56 3.62 19.18 3.73 19.79 3.84" +
                "C19.96 8.03 17.95 12.4 13.42 13.91Z" +
                // left and right fluke
                "M19.53 4.38C19.12 4.97 17.74 4.72 16.45 3.81C15.16 2.91 14.44 1.7 14.86 1.11" +
                "C15.27 0.52 16.65 0.78 17.94 1.68C19.23 2.59 19.94 3.8 19.53 4.38Z" +
                "M22.88 1.11C23.3 1.7 22.58 2.91 21.29 3.81C20 4.72 18.62 4.97 18.21 4.38" +
                "C17.8 3.8 18.51 2.59 19.8 1.68C21.09 0.78 22.47 0.52 22.88 1.11Z" +
                // eye (opposite winding)
                "M5.24 9.71C5.24 9.23 4.84 8.83 4.36 8.83C3.87 8.83 3.47 9.23 3.47 9.71C3.47 10.2 3.87 10.59 4.36 10.59" +
                "C4.84 10.59 5.24 10.2 5.24 9.71Z",
        ).toNodes(),
        fill = SolidColor(Color.Black),
    )
    for (spout in listOf("M5.53 6.19C5.53 3.84 4.36 2.66 2.68 2.16", "M5.53 6.19C5.53 3.84 6.7 2.66 8.38 2.16")) {
        addPath(
            pathData = PathParser().parsePathString(spout).toNodes(),
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.35f,
            strokeLineCap = StrokeCap.Round,
        )
    }
}.build()

@Preview(showBackground = true)
@Composable
private fun WhaleRatingPreview() {
    KoubeiTheme(dynamicColor = false) {
        WhaleRating(4.8, iconWidth = 24.dp)
    }
}
