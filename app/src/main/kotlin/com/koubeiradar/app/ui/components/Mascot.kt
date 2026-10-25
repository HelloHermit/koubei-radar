package com.koubeiradar.app.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.koubeiradar.app.ui.theme.BrandRamp as Ramp
import com.koubeiradar.app.ui.theme.KoubeiTheme

/** How the mascot looks: one face per kind of moment the screens have to put into a picture. */
enum class MascotMood {
    /** Greeting, or something is being prepared. */
    HAPPY,

    /** Nothing to go on yet: no position, no match, an unknown shop. */
    CONFUSED,

    /** Quiet: nothing around. */
    SLEEPY,

    /** Something went wrong. */
    WORRIED,
}

/**
 * The app's mascot, a small whale balancing a hot takeout pail on its head. Decorative: the text next to it says what is
 * going on, so it carries no description. Original artwork drawn for this app, as paths in the brand colour ramp; it looks the
 * same in the light and the dark theme.
 */
@Composable
fun Mascot(mood: MascotMood, modifier: Modifier = Modifier, width: Dp = 128.dp) {
    Image(
        imageVector = mascotVector(mood),
        contentDescription = null,
        modifier = modifier.size(width = width, height = width * (VIEWPORT_HEIGHT / VIEWPORT_WIDTH)).testTag(mascotTag(mood)),
    )
}

/** The test tag of the mascot with this mood. */
fun mascotTag(mood: MascotMood): String = "mascot:" + mood.name.lowercase()

private const val VIEWPORT_WIDTH = 231f
private const val VIEWPORT_HEIGHT = 190f

private val vectors = MascotMood.entries.associateWith { mood -> lazy { build(mood) } }

private fun mascotVector(mood: MascotMood): ImageVector = vectors.getValue(mood).value

private fun build(mood: MascotMood): ImageVector =
    ImageVector.Builder("Mascot" + mood.name, VIEWPORT_WIDTH.dp, VIEWPORT_HEIGHT.dp, VIEWPORT_WIDTH, VIEWPORT_HEIGHT).apply {
        shared()
        when (mood) {
            MascotMood.HAPPY -> happyFace()
            MascotMood.CONFUSED -> confusedFace()
            MascotMood.SLEEPY -> sleepyFace()
            MascotMood.WORRIED -> worriedFace()
        }
    }.build()

/** One path: filled, stroked with round caps and joins, or both. [alpha] applies to whichever is drawn. */
private fun ImageVector.Builder.layer(d: String, fill: Color? = null, stroke: Color? = null, width: Float = 0f, alpha: Float = 1f) {
    addPath(
        pathData = PathParser().parsePathString(d).toNodes(),
        fill = fill?.let(::SolidColor),
        fillAlpha = alpha,
        stroke = stroke?.let(::SolidColor),
        strokeAlpha = alpha,
        strokeLineWidth = width,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
    )
}

/** Tail, body, belly, flipper, the pail with its steam: everything but the face. */
private fun ImageVector.Builder.shared() {
    // tail outline
    layer("M142 123C168 123 182 107 182 83C188 85 194 87 200 89C202 127 178 159 146 165Z", fill = Ramp.line900, stroke = Ramp.line900, width = 7f)
    // tail outline
    layer("M196.84 90.19C193.51 94.94 182.38 92.89 171.98 85.6C161.57 78.32 155.83 68.56 159.16 63.81C162.49 59.06 173.62 61.11 184.02 68.4C194.43 75.68 200.17 85.44 196.84 90.19Z", fill = Ramp.line900, stroke = Ramp.line900, width = 7f)
    // tail outline
    layer("M224.84 65.81C228.17 70.56 222.43 80.32 212.02 87.6C201.62 94.89 190.49 96.94 187.16 92.19C183.83 87.44 189.57 77.68 199.98 70.4C210.38 63.11 221.51 61.06 224.84 65.81Z", fill = Ramp.line900, stroke = Ramp.line900, width = 7f)
    // tail fill
    layer("M142 123C168 123 182 107 182 83C188 85 194 87 200 89C202 127 178 159 146 165Z", fill = Ramp.blue600)
    // tail fill
    layer("M196.84 90.19C193.51 94.94 182.38 92.89 171.98 85.6C161.57 78.32 155.83 68.56 159.16 63.81C162.49 59.06 173.62 61.11 184.02 68.4C194.43 75.68 200.17 85.44 196.84 90.19Z", fill = Ramp.blue600)
    // tail fill
    layer("M224.84 65.81C228.17 70.56 222.43 80.32 212.02 87.6C201.62 94.89 190.49 96.94 187.16 92.19C183.83 87.44 189.57 77.68 199.98 70.4C210.38 63.11 221.51 61.06 224.84 65.81Z", fill = Ramp.blue600)
    // body
    layer("M16 139C12 105 40 85 84 85C128 85 158 107 162 139C162 167 128 185 90 185C48 185 18 169 16 139Z", fill = Ramp.blue600)
    // belly
    layer("M20 147C46 163 100 167 158 145C152 171 124 183 90 183C52 183 26 169 20 147Z", fill = Ramp.cyan100)
    // shine
    layer("M38 105C40 97 52 89 64 89", stroke = Ramp.cyan300, width = 4f, alpha = 0.55f)
    // body line
    layer("M16 139C12 105 40 85 84 85C128 85 158 107 162 139C162 167 128 185 90 185C48 185 18 169 16 139Z", stroke = Ramp.line900, width = 4f)
    // flipper
    layer("M82 145C98 145 106 159 98 171C86 171 76 161 82 145Z", fill = Ramp.blue800, stroke = Ramp.line900, width = 3f)
    // pail
    layer("M70 61C80.67 61 91.33 61 102 61C100.67 69 99.33 77 98 85C97.5 88 96.5 88 94 88C88.67 88 83.33 88 78 88C75.5 88 74.5 88 74 85Z", fill = Ramp.white, stroke = Ramp.line900, width = 3f)
    // band
    layer("M71.4 71C81.13 71 90.87 71 100.6 71C100.2 73 99.8 75 99.4 77C90.47 77 81.53 77 72.6 77Z", fill = Ramp.gold300)
    // pail line
    layer("M70 61C80.67 61 91.33 61 102 61C100.67 69 99.33 77 98 85C97.5 88 96.5 88 94 88C88.67 88 83.33 88 78 88C75.5 88 74.5 88 74 85Z", stroke = Ramp.line900, width = 3f)
    // lid
    layer("M64 55C78.67 55 93.33 55 108 55C111 55 111 61 108 61C93.33 61 78.67 61 64 61C61 61 61 55 64 55Z", fill = Ramp.cyan100, stroke = Ramp.line900, width = 3f)
    // handle outline
    layer("M71 55C76 37 96 37 101 55", stroke = Ramp.line900, width = 5.2f)
    // handle
    layer("M71 55C76 37 96 37 101 55", stroke = Ramp.cyan100, width = 2f)
    // steam
    layer("M79 31C74 25 74 21 79 17C84 13 84 9 79 5", stroke = Ramp.cyan300, width = 3.4f, alpha = 0.9f)
    // steam
    layer("M89 33C84 27 84 23 89 19C94 15 94 11 89 7", stroke = Ramp.cyan300, width = 3.4f, alpha = 0.9f)
    // steam
    layer("M99 31C94 25 94 21 99 17C104 13 104 9 99 5", stroke = Ramp.cyan300, width = 3.4f, alpha = 0.9f)
}

private fun ImageVector.Builder.happyFace() {
    // eye
    layer("M53.2 119C53.2 123.86 49.98 127.8 46 127.8C42.02 127.8 38.8 123.86 38.8 119C38.8 114.14 42.02 110.2 46 110.2C49.98 110.2 53.2 114.14 53.2 119Z", fill = Ramp.ink900)
    // glint
    layer("M51.7 115.4C51.7 116.89 50.49 118.1 49 118.1C47.51 118.1 46.3 116.89 46.3 115.4C46.3 113.91 47.51 112.7 49 112.7C50.49 112.7 51.7 113.91 51.7 115.4Z", fill = Ramp.white)
    // glint2
    layer("M44.3 123C44.3 123.72 43.72 124.3 43 124.3C42.28 124.3 41.7 123.72 41.7 123C41.7 122.28 42.28 121.7 43 121.7C43.72 121.7 44.3 122.28 44.3 123Z", fill = Ramp.white)
    // cheek
    layer("M71.5 135C71.5 138.2 67.25 140.8 62 140.8C56.75 140.8 52.5 138.2 52.5 135C52.5 131.8 56.75 129.2 62 129.2C67.25 129.2 71.5 131.8 71.5 135Z", fill = Ramp.blush200, alpha = 0.9f)
    // smile
    layer("M28 141C34 149 42 151 49 142", stroke = Ramp.line900, width = 3.2f)
}

private fun ImageVector.Builder.confusedFace() {
    // eye
    layer("M52.2 116C52.2 120.86 48.98 124.8 45 124.8C41.02 124.8 37.8 120.86 37.8 116C37.8 111.14 41.02 107.2 45 107.2C48.98 107.2 52.2 111.14 52.2 116Z", fill = Ramp.ink900)
    // glint
    layer("M50.7 112.4C50.7 113.89 49.49 115.1 48 115.1C46.51 115.1 45.3 113.89 45.3 112.4C45.3 110.91 46.51 109.7 48 109.7C49.49 109.7 50.7 110.91 50.7 112.4Z", fill = Ramp.white)
    // glint2
    layer("M43.3 120C43.3 120.72 42.72 121.3 42 121.3C41.28 121.3 40.7 120.72 40.7 120C40.7 119.28 41.28 118.7 42 118.7C42.72 118.7 43.3 119.28 43.3 120Z", fill = Ramp.white)
    // cheek
    layer("M71.5 135C71.5 138.2 67.25 140.8 62 140.8C56.75 140.8 52.5 138.2 52.5 135C52.5 131.8 56.75 129.2 62 129.2C67.25 129.2 71.5 131.8 71.5 135Z", fill = Ramp.blush200, alpha = 0.9f)
    // brow
    layer("M34 101C40 95 48 95 56 99", stroke = Ramp.line900, width = 3.2f)
    // wavy
    layer("M28 145C32 139 38 139 42 145C46 150 51 150 54 144", stroke = Ramp.line900, width = 3.2f)
    // bubble
    layer("M35 77C35 85.28 28.28 92 20 92C11.72 92 5 85.28 5 77C5 68.72 11.72 62 20 62C28.28 62 35 68.72 35 77Z", fill = Ramp.white, stroke = Ramp.line900, width = 3f)
    // ?
    layer("M14 73C14 65 28 65 28 71C28 76 22 77 22 82", stroke = Ramp.line900, width = 3.2f)
    // ?dot
    layer("M23.9 88C23.9 89.05 23.05 89.9 22 89.9C20.95 89.9 20.1 89.05 20.1 88C20.1 86.95 20.95 86.1 22 86.1C23.05 86.1 23.9 86.95 23.9 88Z", fill = Ramp.line900)
}

private fun ImageVector.Builder.sleepyFace() {
    // closed eye
    layer("M36 117C40 127 52 127 56 117", stroke = Ramp.line900, width = 3.6f)
    // cheek
    layer("M71.5 135C71.5 138.2 67.25 140.8 62 140.8C56.75 140.8 52.5 138.2 52.5 135C52.5 131.8 56.75 129.2 62 129.2C67.25 129.2 71.5 131.8 71.5 135Z", fill = Ramp.blush200, alpha = 0.9f)
    // mouth
    layer("M41.6 143C41.6 144.55 39.99 145.8 38 145.8C36.01 145.8 34.4 144.55 34.4 143C34.4 141.45 36.01 140.2 38 140.2C39.99 140.2 41.6 141.45 41.6 143Z", fill = Ramp.line900)
    // Z
    layer("M6 91C10 91 14 91 18 91C14 95.67 10 100.33 6 105C10 105 14 105 18 105", stroke = Ramp.cyan300, width = 3.4f)
    // z
    layer("M22 73C25 73 28 73 31 73C28 76.67 25 80.33 22 84C25 84 28 84 31 84", stroke = Ramp.cyan300, width = 3f)
}

private fun ImageVector.Builder.worriedFace() {
    // eye
    layer("M53.2 120C53.2 124.86 49.98 128.8 46 128.8C42.02 128.8 38.8 124.86 38.8 120C38.8 115.14 42.02 111.2 46 111.2C49.98 111.2 53.2 115.14 53.2 120Z", fill = Ramp.ink900)
    // glint
    layer("M51.7 116.4C51.7 117.89 50.49 119.1 49 119.1C47.51 119.1 46.3 117.89 46.3 116.4C46.3 114.91 47.51 113.7 49 113.7C50.49 113.7 51.7 114.91 51.7 116.4Z", fill = Ramp.white)
    // glint2
    layer("M44.3 124C44.3 124.72 43.72 125.3 43 125.3C42.28 125.3 41.7 124.72 41.7 124C41.7 123.28 42.28 122.7 43 122.7C43.72 122.7 44.3 123.28 44.3 124Z", fill = Ramp.white)
    // cheek
    layer("M71.5 135C71.5 138.2 67.25 140.8 62 140.8C56.75 140.8 52.5 138.2 52.5 135C52.5 131.8 56.75 129.2 62 129.2C67.25 129.2 71.5 131.8 71.5 135Z", fill = Ramp.blush200, alpha = 0.9f)
    // brow
    layer("M34 105C40 105 48 103 56 95", stroke = Ramp.line900, width = 3.2f)
    // frown
    layer("M28 147C32 141 42 141 49 147", stroke = Ramp.line900, width = 3.2f)
    // sweat
    layer("M66 97C71 105 71 109 66 109C61 109 61 105 66 97Z", fill = Ramp.cyan300, stroke = Ramp.line900, width = 2f)
}

@Preview(showBackground = true)
@Composable
private fun MascotPreview() {
    KoubeiTheme(dynamicColor = false) {
        Row {
            MascotMood.entries.forEach { Mascot(it, width = 80.dp) }
        }
    }
}
