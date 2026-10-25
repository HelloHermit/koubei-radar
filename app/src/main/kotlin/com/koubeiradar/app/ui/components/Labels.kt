package com.koubeiradar.app.ui.components

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.koubeiradar.app.R
import com.koubeiradar.app.settings.PoiProvider
import com.koubeiradar.app.settings.ScoringPreset

@StringRes
fun PoiProvider.labelRes(): Int = when (this) {
    PoiProvider.AMAP -> R.string.poi_provider_amap
    PoiProvider.BAIDU -> R.string.poi_provider_baidu
}

@StringRes
fun ScoringPreset.labelRes(): Int = when (this) {
    ScoringPreset.REPUTATION_FIRST -> R.string.preset_reputation
    ScoringPreset.VALUE_FIRST -> R.string.preset_value
    ScoringPreset.FAST_FIRST -> R.string.preset_fast
}

/** 口碑优先 / 性价比 / 速度. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScoringPresetSelector(selected: ScoringPreset, onSelect: (ScoringPreset) -> Unit, modifier: Modifier = Modifier) {
    val presets = ScoringPreset.entries
    SingleChoiceSegmentedButtonRow(modifier.fillMaxWidth()) {
        presets.forEachIndexed { index, preset ->
            SegmentedButton(
                selected = preset == selected,
                onClick = { onSelect(preset) },
                shape = SegmentedButtonDefaults.itemShape(index, presets.size),
            ) {
                Text(stringResource(preset.labelRes()))
            }
        }
    }
}

/** A small non-interactive label, e.g. a dish or a shop tag. */
@Composable
fun TagPill(text: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}
