package com.koubeiradar.app.ui.criteria

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.koubeiradar.app.R
import com.koubeiradar.app.ui.format.Formatters
import com.koubeiradar.core.model.Criteria

@Composable
fun CriteriaChip.label(): String {
    val separator = stringResource(R.string.list_separator)
    return when (this) {
        is CriteriaChip.MinRating -> stringResource(R.string.criteria_min_rating, Formatters.rating(rating))
        is CriteriaChip.MinRatingCount -> stringResource(R.string.criteria_min_count, count)
        is CriteriaChip.MaxDistance -> stringResource(R.string.criteria_max_distance, Formatters.distance(meters))
        is CriteriaChip.PriceRange -> when {
            min != null && max != null -> stringResource(R.string.criteria_price_range, Formatters.price(min), Formatters.price(max))
            max != null -> stringResource(R.string.criteria_price_max, Formatters.price(max))
            else -> stringResource(R.string.criteria_price_min, Formatters.price(min ?: 0.0))
        }

        is CriteriaChip.MaxDeliveryMinutes -> stringResource(R.string.criteria_max_minutes, minutes)
        is CriteriaChip.MaxDeliveryFee -> stringResource(R.string.criteria_max_fee, Formatters.price(yuan))
        is CriteriaChip.IncludeKeywords -> stringResource(R.string.criteria_include, keywords.joinToString(separator))
        is CriteriaChip.ExcludeKeywords -> stringResource(R.string.criteria_exclude, keywords.joinToString(separator))
        is CriteriaChip.Platforms -> platforms.joinToString(separator) { it.displayName }
        CriteriaChip.OnlyOpen -> stringResource(R.string.criteria_only_open)
        is CriteriaChip.MinScore -> stringResource(R.string.criteria_min_score, Formatters.score(score))
    }
}

/** One sentence for the whole filter, e.g. for the agent's "已为你筛选：…" line. */
@Composable
fun Criteria.summaryText(): String {
    val labels = chips().map { it.label() }
    return if (labels.isEmpty()) stringResource(R.string.criteria_unrestricted) else labels.joinToString(stringResource(R.string.criteria_join))
}
