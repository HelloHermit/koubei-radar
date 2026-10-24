package com.koubeiradar.app.ui.detail

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.koubeiradar.app.R
import com.koubeiradar.app.ui.components.LoadingPanel
import com.koubeiradar.app.ui.components.MessagePanel
import com.koubeiradar.app.ui.components.OrderButton
import com.koubeiradar.app.ui.components.ReasonLine
import com.koubeiradar.app.ui.components.ScoreRing
import com.koubeiradar.app.ui.components.SectionTitle
import com.koubeiradar.app.ui.components.ShopFacts
import com.koubeiradar.app.ui.components.TagPill
import com.koubeiradar.app.ui.components.VerdictBadge
import com.koubeiradar.app.ui.components.WarningLine
import com.koubeiradar.app.ui.format.Formatters
import com.koubeiradar.app.ui.preview.PreviewData
import com.koubeiradar.app.ui.theme.KoubeiTheme
import com.koubeiradar.core.model.Review
import com.koubeiradar.core.model.ScoredShop
import com.koubeiradar.core.model.Shop

private const val MAX_REVIEWS_SHOWN = 10
private const val MAX_REVIEW_LINES = 6

/** 详情: the score and its breakdown, the reasons, the shop's facts and (if the import had any) review texts. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(viewModel: DetailViewModel, onBack: () -> Unit, onOrder: (Shop) -> Unit) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.detail_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back)) }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding)) {
            when (val current = state) {
                DetailUiState.Loading -> LoadingPanel(stringResource(R.string.detail_loading))
                DetailUiState.NotFound -> MessagePanel(
                    icon = Icons.Default.Search,
                    title = stringResource(R.string.detail_not_found_title),
                    body = stringResource(R.string.detail_not_found_body),
                ) {
                    Button(onClick = onBack) { Text(stringResource(R.string.cd_back)) }
                }

                is DetailUiState.Found -> DetailContent(current.scored, onOrder = { onOrder(current.scored.shop) })
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DetailContent(scored: ScoredShop, onOrder: () -> Unit) {
    val shop = scored.shop
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item {
            Card(modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(shop.name, style = MaterialTheme.typography.titleLarge, maxLines = 3, overflow = TextOverflow.Ellipsis)
                            VerdictBadge(scored.verdict)
                        }
                        ScoreRing(scored.score, scored.verdict, size = 80.dp)
                    }
                    ShopFacts(shop)
                    OrderButton(onOrder, Modifier.align(Alignment.End))
                }
            }
        }
        item { Breakdown(scored) }
        if (scored.reasons.isNotEmpty() || scored.warnings.isNotEmpty()) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionTitle(stringResource(R.string.detail_section_why))
                    scored.reasons.forEach { ReasonLine(it) }
                    scored.warnings.forEach { WarningLine(it) }
                }
            }
        }
        item { Info(shop) }
        if (shop.tags.isNotEmpty()) {
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    shop.tags.forEach { TagPill(it) }
                }
            }
        }
        if (shop.reviews.isNotEmpty()) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionTitle(stringResource(R.string.detail_section_reviews))
                    Text(stringResource(R.string.detail_reviews_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    shop.reviews.take(MAX_REVIEWS_SHOWN).forEach { ReviewItem(it) }
                    if (shop.reviews.size > MAX_REVIEWS_SHOWN) {
                        Text(stringResource(R.string.detail_reviews_more, shop.reviews.size, MAX_REVIEWS_SHOWN), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

/** One bar per score component that could be computed, plus the penalty. */
@Composable
private fun Breakdown(scored: ScoredShop) {
    val b = scored.breakdown
    val rows = listOfNotNull(
        b.ratingScore?.let { R.string.detail_score_rating to it },
        b.confidenceScore?.let { R.string.detail_score_confidence to it },
        b.sentimentScore?.let { R.string.detail_score_sentiment to it },
        b.valueScore?.let { R.string.detail_score_value to it },
        b.convenienceScore?.let { R.string.detail_score_convenience to it },
    )
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle(stringResource(R.string.detail_section_breakdown))
        rows.forEach { (label, value) -> ScoreBar(stringResource(label), value, MaterialTheme.colorScheme.primary) }
        if (b.penalty > 0) ScoreBar(stringResource(R.string.detail_score_penalty), b.penalty, MaterialTheme.colorScheme.error, prefix = "−")
        if (rows.isEmpty() && b.penalty <= 0) Text(stringResource(R.string.detail_breakdown_empty), style = MaterialTheme.typography.bodyMedium)
        b.adjustedRating?.let { adjusted ->
            val count = b.effectiveRatingCount
            val note = when {
                count == null -> stringResource(R.string.detail_adjusted_rating, Formatters.rating(adjusted))
                b.ratingCountIsEstimated -> stringResource(R.string.detail_adjusted_rating_estimated, Formatters.rating(adjusted), count)
                else -> stringResource(R.string.detail_adjusted_rating_count, Formatters.rating(adjusted), count)
            }
            Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ScoreBar(label: String, value: Double, color: Color, prefix: String = "") {
    val shown = Formatters.score(value)
    val description = stringResource(R.string.cd_score_bar, label, shown)
    Row(
        modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.widthIn(min = 88.dp, max = 140.dp))
        LinearProgressIndicator(
            progress = { (value / 100.0).toFloat().coerceIn(0f, 1f) },
            modifier = Modifier.weight(1f),
            color = color,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
        )
        Text(prefix + shown, style = MaterialTheme.typography.labelLarge, maxLines = 1, softWrap = false, modifier = Modifier.widthIn(min = 36.dp))
    }
}

@Composable
private fun Info(shop: Shop) {
    val context = LocalContext.current
    val unknown = stringResource(R.string.detail_unknown)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SectionTitle(stringResource(R.string.detail_section_info))
        shop.category?.let { InfoRow(stringResource(R.string.detail_info_category), it) }
        InfoRow(stringResource(R.string.detail_info_delivery_fee), shop.deliveryFee?.let(Formatters::price) ?: unknown)
        InfoRow(stringResource(R.string.detail_info_min_order), shop.minOrder?.let(Formatters::price) ?: unknown)
        InfoRow(
            stringResource(R.string.detail_info_open),
            when (shop.openNow) {
                true -> stringResource(R.string.detail_open_yes)
                false -> stringResource(R.string.detail_open_no)
                null -> unknown
            },
        )
        shop.address?.let { InfoRow(stringResource(R.string.detail_info_address), it) }
        shop.phone?.let { phone ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                InfoRow(stringResource(R.string.detail_info_phone), phone, Modifier.weight(1f))
                TextButton(onClick = {
                    try {
                        context.startActivity(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", phone, null)))
                    } catch (e: ActivityNotFoundException) {
                        // No dialer on this device; the number stays visible above.
                    }
                }) { Text(stringResource(R.string.detail_action_call)) }
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.widthIn(min = 72.dp, max = 140.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}

/** Review text is somebody else's content: shown as plain text, bounded in length. */
@Composable
private fun ReviewItem(review: Review) {
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            review.rating?.let {
                Text(stringResource(R.string.detail_review_rating, Formatters.rating(it)), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(review.text, style = MaterialTheme.typography.bodyMedium, maxLines = MAX_REVIEW_LINES, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun DetailPreview() {
    KoubeiTheme(dynamicColor = false) {
        DetailContent(PreviewData.risky, onOrder = {})
    }
}
