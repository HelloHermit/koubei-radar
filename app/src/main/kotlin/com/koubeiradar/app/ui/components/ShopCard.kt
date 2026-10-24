package com.koubeiradar.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.koubeiradar.app.R
import com.koubeiradar.app.ui.format.Formatters
import com.koubeiradar.app.ui.preview.PreviewData
import com.koubeiradar.app.ui.theme.KoubeiTheme
import com.koubeiradar.app.ui.theme.koubeiColors
import com.koubeiradar.core.model.ScoredShop
import com.koubeiradar.core.model.Shop

/** A shop in a list: name, verdict, score ring, key facts, up to two reasons, every warning, and the two actions. */
@Composable
fun ShopCard(scored: ScoredShop, onDetail: () -> Unit, onOrder: () -> Unit, modifier: Modifier = Modifier) {
    val shop = scored.shop
    Card(
        onClick = onDetail,
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(shop.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    VerdictBadge(scored.verdict)
                }
                ScoreRing(scored.score, scored.verdict)
            }
            ShopFacts(shop)
            scored.reasons.take(MAX_REASONS).forEach { ReasonLine(it) }
            scored.warnings.forEach { WarningLine(it) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onDetail) { Text(stringResource(R.string.action_details)) }
                Spacer(Modifier.size(4.dp))
                OrderButton(onOrder)
            }
        }
    }
}

@Composable
fun OrderButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    FilledTonalButton(onClick = onClick, modifier = modifier, contentPadding = ButtonDefaults.ButtonWithIconContentPadding) {
        Icon(Icons.Default.ShoppingCart, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
        Spacer(Modifier.size(ButtonDefaults.IconSpacing))
        Text(stringResource(R.string.action_order))
    }
}

/** Rating, sales, average price, distance and delivery time; unknown values are simply left out. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ShopFacts(shop: Shop, modifier: Modifier = Modifier) {
    FlowRow(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        RatingFact(shop.rating, shop.ratingCount)
        shop.monthlySales?.let { Fact(stringResource(R.string.shop_sales_format, Formatters.count(it))) }
        shop.avgPrice?.let { Fact(stringResource(R.string.shop_price_format, Formatters.price(it))) }
        shop.distanceMeters?.let { Fact(stringResource(R.string.shop_distance_format, Formatters.distance(it))) }
        shop.deliveryMinutes?.let { Fact(stringResource(R.string.shop_delivery_minutes_format, it)) }
    }
}

@Composable
private fun Fact(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun RatingFact(rating: Double?, count: Int?) {
    if (rating == null) {
        Fact(stringResource(R.string.shop_unrated))
        return
    }
    val description = if (count != null) {
        stringResource(R.string.cd_rating_with_count, Formatters.rating(rating), Formatters.count(count))
    } else {
        stringResource(R.string.cd_rating, Formatters.rating(rating))
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = description },
    ) {
        Icon(Icons.Default.Star, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.koubeiColors.warning)
        Spacer(Modifier.size(2.dp))
        Text(Formatters.rating(rating), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        if (count != null) {
            Spacer(Modifier.size(4.dp))
            Text(
                stringResource(R.string.shop_rating_count_format, Formatters.count(count)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun ReasonLine(text: String, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp).padding(top = 3.dp), tint = MaterialTheme.koubeiColors.success)
        Text(text, style = MaterialTheme.typography.bodySmall)
    }
}

/** Warnings use the error colour plus a warning icon, so they stand out without relying on colour alone. */
@Composable
fun WarningLine(text: String, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(Icons.Default.Warning, contentDescription = null, modifier = Modifier.size(16.dp).padding(top = 3.dp), tint = MaterialTheme.colorScheme.error)
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
}

private const val MAX_REASONS = 2

@Preview(showBackground = true)
@Composable
private fun ShopCardPreview() {
    KoubeiTheme(dynamicColor = false) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ShopCard(PreviewData.recommended, onDetail = {}, onOrder = {})
            ShopCard(PreviewData.risky, onDetail = {}, onOrder = {})
        }
    }
}
