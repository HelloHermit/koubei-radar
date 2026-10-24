package com.koubeiradar.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.koubeiradar.app.R
import com.koubeiradar.app.ui.format.Formatters
import com.koubeiradar.app.ui.theme.KoubeiTheme
import com.koubeiradar.app.ui.theme.koubeiColors
import com.koubeiradar.core.model.Verdict

/** How a verdict looks. The icon and the label always accompany the colour, so it is never the only carrier. */
@Immutable
class VerdictStyle(val container: Color, val onContainer: Color, val accent: Color, val icon: ImageVector)

@Composable
fun Verdict.style(): VerdictStyle {
    val scheme = MaterialTheme.colorScheme
    val extra = MaterialTheme.koubeiColors
    return when (this) {
        Verdict.RECOMMENDED -> VerdictStyle(extra.successContainer, extra.onSuccessContainer, extra.success, Icons.Default.CheckCircle)
        Verdict.OKAY -> VerdictStyle(extra.warningContainer, extra.onWarningContainer, extra.warning, Icons.Default.Info)
        Verdict.AVOID -> VerdictStyle(scheme.errorContainer, scheme.onErrorContainer, scheme.error, Icons.Default.Warning)
        Verdict.INSUFFICIENT_DATA -> VerdictStyle(scheme.surfaceVariant, scheme.onSurfaceVariant, scheme.outline, Icons.Default.Search)
    }
}

@Composable
fun VerdictBadge(verdict: Verdict, modifier: Modifier = Modifier) {
    val style = verdict.style()
    Surface(modifier = modifier, shape = RoundedCornerShape(50), color = style.container, contentColor = style.onContainer) {
        Row(
            modifier = Modifier.padding(start = 8.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(style.icon, contentDescription = null, modifier = Modifier.size(16.dp))
            Text(verdict.label, style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** The composite score as a ring filled in proportion to the score, with the number in the middle. */
@Composable
fun ScoreRing(score: Double, verdict: Verdict, modifier: Modifier = Modifier, size: Dp = 56.dp) {
    val accent = verdict.style().accent
    val track = MaterialTheme.colorScheme.surfaceVariant
    val known = verdict != Verdict.INSUFFICIENT_DATA
    val shown = Formatters.score(score)
    val description = if (known) stringResource(R.string.cd_score, shown) else stringResource(R.string.cd_score_unknown)
    Box(
        modifier = modifier.size(size).semantics(mergeDescendants = true) { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = Stroke(width = this.size.width * 0.1f, cap = StrokeCap.Round)
            val arcTopLeft = Offset(stroke.width / 2, stroke.width / 2)
            val arcSize = Size(this.size.width - stroke.width, this.size.height - stroke.width)
            drawArc(track, 0f, 360f, useCenter = false, topLeft = arcTopLeft, size = arcSize, style = stroke)
            if (known && shown > 0) {
                drawArc(accent, -90f, 360f * shown / 100f, useCenter = false, topLeft = arcTopLeft, size = arcSize, style = stroke)
            }
        }
        // Sized from the ring, not from the user's font scale, so the digits always fit inside it.
        val fontSize = with(LocalDensity.current) { (size * 0.32f).toSp() }
        Text(
            text = if (known) shown.toString() else "—",
            fontSize = fontSize,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun VerdictPreview() {
    KoubeiTheme(dynamicColor = false) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Verdict.entries.forEachIndexed { index, verdict ->
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    ScoreRing(score = listOf(88.0, 64.0, 31.0, 0.0)[index], verdict = verdict)
                    VerdictBadge(verdict)
                }
            }
        }
    }
}
