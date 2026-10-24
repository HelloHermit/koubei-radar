package com.koubeiradar.app.ui.criteria

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.koubeiradar.app.R
import com.koubeiradar.app.ui.components.SectionTitle
import com.koubeiradar.app.ui.format.Formatters
import com.koubeiradar.core.model.Criteria
import com.koubeiradar.core.model.Platform
import kotlin.math.roundToInt

private const val PRICE_SLIDER_MAX = 150f
private const val PRICE_SLIDER_STEP = 5
private val RATING_OPTIONS = listOf(4.0, 4.3, 4.5, 4.7)
private val COUNT_OPTIONS = listOf(50, 200, 500, 1000)
private val DISTANCE_OPTIONS = listOf(1000, 2000, 3000, 5000)
private val MINUTES_OPTIONS = listOf(30, 45, 60)

@Suppress("UNCHECKED_CAST")
private val DraftSaver = listSaver<CriteriaDraft, Any?>(
    save = {
        listOf(
            it.minRating, it.minRatingCount, it.maxDistanceMeters, it.priceMin, it.priceMax, it.maxDeliveryMinutes,
            it.includeKeywords, it.excludeKeywords, ArrayList(it.platforms.map(Platform::name)), it.onlyOpen,
        )
    },
    restore = {
        CriteriaDraft(
            minRating = it[0] as Double?,
            minRatingCount = it[1] as Int?,
            maxDistanceMeters = it[2] as Int?,
            priceMin = it[3] as Int?,
            priceMax = it[4] as Int?,
            maxDeliveryMinutes = it[5] as Int?,
            includeKeywords = it[6] as String,
            excludeKeywords = it[7] as String,
            platforms = (it[8] as List<String>).map(Platform::valueOf).toSet(),
            onlyOpen = it[9] as Boolean,
        )
    },
)

/** Bottom sheet that edits a [Criteria]; shared by the home screen and the settings. Edits are applied with the button. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CriteriaSheet(criteria: Criteria, onApply: (Criteria) -> Unit, onDismiss: () -> Unit) {
    var draft by rememberSaveable(stateSaver = DraftSaver) { mutableStateOf(CriteriaDraft.of(criteria)) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(R.string.criteria_sheet_title), style = MaterialTheme.typography.titleLarge)
                TextButton(onClick = { draft = CriteriaDraft() }) { Text(stringResource(R.string.criteria_reset)) }
            }
            OptionRow(
                title = stringResource(R.string.criteria_section_rating),
                options = RATING_OPTIONS,
                selected = draft.minRating,
                label = { stringResource(R.string.criteria_option_min_rating, Formatters.rating(it)) },
                onSelect = { draft = draft.copy(minRating = it) },
            )
            OptionRow(
                title = stringResource(R.string.criteria_section_count),
                options = COUNT_OPTIONS,
                selected = draft.minRatingCount,
                label = { stringResource(R.string.criteria_option_min_count, it) },
                onSelect = { draft = draft.copy(minRatingCount = it) },
            )
            OptionRow(
                title = stringResource(R.string.criteria_section_distance),
                options = DISTANCE_OPTIONS,
                selected = draft.maxDistanceMeters,
                label = { stringResource(R.string.criteria_option_max_distance, Formatters.distance(it)) },
                onSelect = { draft = draft.copy(maxDistanceMeters = it) },
            )
            PriceRange(draft.priceMin, draft.priceMax) { min, max -> draft = draft.copy(priceMin = min, priceMax = max) }
            OptionRow(
                title = stringResource(R.string.criteria_section_minutes),
                options = MINUTES_OPTIONS,
                selected = draft.maxDeliveryMinutes,
                label = { stringResource(R.string.criteria_option_minutes, it) },
                onSelect = { draft = draft.copy(maxDeliveryMinutes = it) },
            )
            KeywordField(draft.includeKeywords, stringResource(R.string.criteria_include_label)) { draft = draft.copy(includeKeywords = it) }
            KeywordField(draft.excludeKeywords, stringResource(R.string.criteria_exclude_label)) { draft = draft.copy(excludeKeywords = it) }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionTitle(stringResource(R.string.criteria_section_platforms))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Platform.entries.forEach { platform ->
                        FilterChip(
                            selected = platform in draft.platforms,
                            onClick = { draft = draft.copy(platforms = draft.platforms.toggled(platform)) },
                            label = { Text(platform.displayName) },
                        )
                    }
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .toggleable(value = draft.onlyOpen, role = Role.Switch, onValueChange = { draft = draft.copy(onlyOpen = it) }),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(stringResource(R.string.criteria_only_open_title), style = MaterialTheme.typography.bodyLarge)
                Switch(checked = draft.onlyOpen, onCheckedChange = null)
            }
            Button(onClick = { onApply(draft.applyTo(criteria)); onDismiss() }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.criteria_apply))
            }
        }
    }
}

private fun <T> Set<T>.toggled(item: T): Set<T> = if (item in this) this - item else this + item

/** Single choice among a few common values plus "不限"; a value outside the list (set by the agent) is shown as well. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T : Comparable<T>> OptionRow(
    title: String,
    options: List<T>,
    selected: T?,
    label: @Composable (T) -> String,
    onSelect: (T?) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle(title)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = selected == null, onClick = { onSelect(null) }, label = { Text(stringResource(R.string.criteria_any)) })
            (options + listOfNotNull(selected)).distinct().sorted().forEach { option ->
                FilterChip(selected = option == selected, onClick = { onSelect(option) }, label = { Text(label(option)) })
            }
        }
    }
}

@Composable
private fun PriceRange(min: Int?, max: Int?, onChange: (Int?, Int?) -> Unit) {
    val start = (min ?: 0).toFloat().coerceIn(0f, PRICE_SLIDER_MAX)
    val end = (max ?: PRICE_SLIDER_MAX.roundToInt()).toFloat().coerceIn(0f, PRICE_SLIDER_MAX)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SectionTitle(stringResource(R.string.criteria_section_price))
        Text(
            text = when {
                min == null && max == null -> stringResource(R.string.criteria_any)
                min != null && max != null -> stringResource(R.string.criteria_price_range, Formatters.price(min.toDouble()), Formatters.price(max.toDouble()))
                max != null -> stringResource(R.string.criteria_price_max, Formatters.price(max.toDouble()))
                else -> stringResource(R.string.criteria_price_min, Formatters.price((min ?: 0).toDouble()))
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        RangeSlider(
            value = start..end,
            onValueChange = { range ->
                onChange(
                    range.start.roundToInt().takeIf { it > 0 },
                    range.endInclusive.roundToInt().takeIf { it < PRICE_SLIDER_MAX },
                )
            },
            valueRange = 0f..PRICE_SLIDER_MAX,
            steps = (PRICE_SLIDER_MAX / PRICE_SLIDER_STEP).roundToInt() - 1,
        )
    }
}

@Composable
private fun KeywordField(value: String, label: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(it.take(MAX_KEYWORD_FIELD_CHARS)) },
        label = { Text(label) },
        supportingText = { Text(stringResource(R.string.criteria_keywords_hint)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

private const val MAX_KEYWORD_FIELD_CHARS = 100
