package com.koubeiradar.app.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.koubeiradar.agent.llm.LlmProvider
import com.koubeiradar.agent.llm.ProviderPresets
import com.koubeiradar.app.BuildConfig
import com.koubeiradar.app.R
import com.koubeiradar.app.settings.AnthropicSettings
import com.koubeiradar.app.settings.InputProblem
import com.koubeiradar.app.settings.SettingField
import com.koubeiradar.app.ui.components.ScoringPresetSelector
import com.koubeiradar.app.ui.components.SectionTitle
import com.koubeiradar.app.ui.components.asString
import com.koubeiradar.app.ui.criteria.CriteriaSheet
import com.koubeiradar.app.ui.criteria.summaryText

private enum class Dialog { PROVIDER, OPENAI_PRESET, EFFORT, CRITERIA, CLEAR_IMPORTED, CLEAR_POOL, CLEAR_KEYS }

/** 设置: data source, AI provider, preferences, data management and the privacy statement. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: SettingsViewModel, onBack: () -> Unit, onMessage: (String) -> Unit) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val resources = LocalResources.current
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is SettingsEvent.Message -> onMessage(event.text.asString(resources))
            }
        }
    }
    var dialog by rememberSaveable { mutableStateOf<Dialog?>(null) }
    var field by rememberSaveable { mutableStateOf<SettingField?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back)) }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            SectionTitle(stringResource(R.string.settings_section_data), Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
            SettingRow(R.string.settings_amap_key, state.amapKey.summary()) { field = SettingField.AMAP_KEY }
            SwitchRow(R.string.settings_use_demo, R.string.settings_use_demo_summary, state.useDemoData, viewModel::setUseDemoData)
            Text(
                stringResource(R.string.settings_amap_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )

            SectionTitle(stringResource(R.string.settings_section_ai), Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
            SettingRow(R.string.settings_provider, state.provider.label) { dialog = Dialog.PROVIDER }
            when (state.provider) {
                LlmProvider.ANTHROPIC -> {
                    SettingRow(R.string.settings_claude_model, state.anthropicModel) { field = SettingField.ANTHROPIC_MODEL }
                    SettingRow(R.string.settings_claude_effort, state.anthropicEffort.ifEmpty { stringResource(R.string.settings_effort_default) }) { dialog = Dialog.EFFORT }
                    SettingRow(R.string.settings_claude_base_url, state.anthropicBaseUrl.ifEmpty { stringResource(R.string.settings_claude_base_url_default) }) { field = SettingField.ANTHROPIC_BASE_URL }
                    SettingRow(R.string.settings_api_key, state.anthropicKey.summary()) { field = SettingField.ANTHROPIC_KEY }
                }

                LlmProvider.OPENAI_COMPATIBLE -> {
                    SettingRow(R.string.settings_openai_preset, state.openAiPreset?.label ?: stringResource(R.string.settings_preset_custom)) { dialog = Dialog.OPENAI_PRESET }
                    SettingRow(R.string.settings_openai_base_url, state.openAiBaseUrl.ifEmpty { stringResource(R.string.settings_not_set) }) { field = SettingField.OPENAI_BASE_URL }
                    SettingRow(R.string.settings_openai_model, state.openAiModel.ifEmpty { stringResource(R.string.settings_not_set) }) { field = SettingField.OPENAI_MODEL }
                    SettingRow(R.string.settings_openai_vision_model, state.openAiVisionModel.ifEmpty { stringResource(R.string.settings_not_set) }) { field = SettingField.OPENAI_VISION_MODEL }
                    SettingRow(R.string.settings_api_key, state.openAiKey.summary()) { field = SettingField.OPENAI_KEY }
                }
            }

            SectionTitle(stringResource(R.string.settings_section_preferences), Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.settings_scoring_preset), style = MaterialTheme.typography.bodyLarge)
                ScoringPresetSelector(state.scoringPreset, viewModel::setScoringPreset)
            }
            SettingRow(R.string.settings_default_criteria, state.criteria.summaryText()) { dialog = Dialog.CRITERIA }

            SectionTitle(stringResource(R.string.settings_section_manage), Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
            SettingRow(R.string.settings_clear_imported, stringResource(R.string.settings_clear_imported_summary)) { dialog = Dialog.CLEAR_IMPORTED }
            SettingRow(R.string.settings_clear_pool, stringResource(R.string.settings_clear_pool_summary)) { dialog = Dialog.CLEAR_POOL }
            SettingRow(R.string.settings_clear_keys, stringResource(R.string.settings_clear_keys_summary)) { dialog = Dialog.CLEAR_KEYS }

            SectionTitle(stringResource(R.string.settings_section_about), Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.settings_about_body), style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(R.string.settings_privacy_title), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(R.string.settings_privacy_amap), style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(R.string.settings_privacy_llm), style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(R.string.settings_privacy_local), style = MaterialTheme.typography.bodyMedium)
                Text(
                    stringResource(R.string.settings_version, BuildConfig.VERSION_NAME),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    field?.let { current ->
        FieldDialog(
            field = current,
            state = state,
            onSubmit = viewModel::submit,
            onClear = if (current.isCredential && state.isSet(current)) ({ viewModel.clearCredential(current) }) else null,
            onDismiss = { field = null },
        )
    }
    when (dialog) {
        Dialog.PROVIDER -> ChoiceDialog(
            title = R.string.settings_provider,
            options = LlmProvider.entries.map { it.name to it.label },
            selected = state.provider.name,
            onSelect = { viewModel.setProvider(LlmProvider.valueOf(it)) },
            onDismiss = { dialog = null },
        )

        Dialog.OPENAI_PRESET -> ChoiceDialog(
            title = R.string.settings_openai_preset,
            options = ProviderPresets.openAiCompatible.map { it.label to it.label },
            selected = state.openAiPreset?.label,
            onSelect = { label -> ProviderPresets.openAiCompatible.firstOrNull { it.label == label }?.let(viewModel::selectOpenAiPreset) },
            onDismiss = { dialog = null },
        )

        Dialog.EFFORT -> {
            val default = stringResource(R.string.settings_effort_default)
            ChoiceDialog(
                title = R.string.settings_claude_effort,
                options = listOf("" to default) + AnthropicSettings.EFFORT_LEVELS.map { it to it },
                selected = state.anthropicEffort,
                onSelect = viewModel::setClaudeEffort,
                onDismiss = { dialog = null },
            )
        }

        Dialog.CRITERIA -> CriteriaSheet(criteria = state.criteria, onApply = viewModel::setCriteria, onDismiss = { dialog = null })
        Dialog.CLEAR_IMPORTED -> ConfirmDialog(R.string.settings_clear_imported, R.string.settings_clear_imported_confirm, viewModel::clearImportedShops) { dialog = null }
        Dialog.CLEAR_POOL -> ConfirmDialog(R.string.settings_clear_pool, R.string.settings_clear_pool_confirm, viewModel::clearCandidatePool) { dialog = null }
        Dialog.CLEAR_KEYS -> ConfirmDialog(R.string.settings_clear_keys, R.string.settings_clear_keys_confirm, viewModel::clearAllCredentials) { dialog = null }
        null -> Unit
    }
}

@Composable
private fun KeyStatus.summary(): String = when {
    !isSet -> stringResource(R.string.settings_key_not_set)
    tail.isEmpty() -> stringResource(R.string.settings_key_saved)
    else -> stringResource(R.string.settings_key_saved_tail, tail)
}

private fun SettingsUiState.isSet(field: SettingField): Boolean = when (field) {
    SettingField.AMAP_KEY -> amapKey.isSet
    SettingField.ANTHROPIC_KEY -> anthropicKey.isSet
    SettingField.OPENAI_KEY -> openAiKey.isSet
    else -> true
}

@Composable
private fun SettingRow(@StringRes title: Int, summary: String, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(stringResource(title)) },
        supportingContent = { Text(summary, maxLines = 3) },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

@Composable
private fun SwitchRow(@StringRes title: Int, @StringRes summary: Int, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(stringResource(title)) },
        supportingContent = { Text(stringResource(summary)) },
        trailingContent = { Switch(checked = checked, onCheckedChange = null) },
        modifier = Modifier.toggleable(value = checked, role = Role.Switch, onValueChange = onChange),
    )
}

@Composable
private fun ChoiceDialog(
    @StringRes title: Int,
    options: List<Pair<String, String>>,
    selected: String?,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(title)) },
        text = {
            Column {
                options.forEach { (key, label) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .selectable(selected = key == selected, role = Role.RadioButton, onClick = { onSelect(key); onDismiss() }),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        RadioButton(selected = key == selected, onClick = null)
                        Text(label, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun ConfirmDialog(@StringRes title: Int, @StringRes message: Int, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(title)) },
        text = { Text(stringResource(message)) },
        confirmButton = { TextButton(onClick = { onConfirm(); onDismiss() }) { Text(stringResource(R.string.action_confirm)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

private class FieldSpec(@StringRes val title: Int, @StringRes val hint: Int, val initial: String, val keyboard: KeyboardType)

private fun SettingField.spec(state: SettingsUiState): FieldSpec = when (this) {
    SettingField.AMAP_KEY -> FieldSpec(R.string.settings_amap_key, R.string.settings_hint_key, "", KeyboardType.Password)
    SettingField.ANTHROPIC_KEY -> FieldSpec(R.string.settings_api_key, R.string.settings_hint_key, "", KeyboardType.Password)
    SettingField.OPENAI_KEY -> FieldSpec(R.string.settings_api_key, R.string.settings_hint_key, "", KeyboardType.Password)
    SettingField.ANTHROPIC_MODEL -> FieldSpec(R.string.settings_claude_model, R.string.settings_hint_claude_model, state.anthropicModel, KeyboardType.Ascii)
    SettingField.ANTHROPIC_BASE_URL -> FieldSpec(R.string.settings_claude_base_url, R.string.settings_hint_claude_base_url, state.anthropicBaseUrl, KeyboardType.Uri)
    SettingField.OPENAI_BASE_URL -> FieldSpec(R.string.settings_openai_base_url, R.string.settings_hint_openai_base_url, state.openAiBaseUrl, KeyboardType.Uri)
    SettingField.OPENAI_MODEL -> FieldSpec(R.string.settings_openai_model, R.string.settings_hint_openai_model, state.openAiModel, KeyboardType.Ascii)
    SettingField.OPENAI_VISION_MODEL -> FieldSpec(R.string.settings_openai_vision_model, R.string.settings_hint_vision_model, state.openAiVisionModel, KeyboardType.Ascii)
}

@StringRes
private fun InputProblem.messageRes(): Int = when (this) {
    InputProblem.EMPTY -> R.string.problem_empty
    InputProblem.TOO_LONG -> R.string.problem_too_long
    InputProblem.CONTAINS_WHITESPACE -> R.string.problem_whitespace
    InputProblem.NOT_PLAIN_ASCII -> R.string.problem_not_ascii
    InputProblem.NOT_HTTPS -> R.string.problem_not_https
    InputProblem.INVALID_URL -> R.string.problem_invalid_url
    InputProblem.URL_HAS_CREDENTIALS -> R.string.problem_url_credentials
}

/**
 * Edits one text setting. Credentials start empty, are masked while typed (with a reveal toggle) and are never
 * prefilled, so a stored key cannot be read back from the screen.
 */
@Composable
private fun FieldDialog(
    field: SettingField,
    state: SettingsUiState,
    onSubmit: (SettingField, String) -> InputProblem?,
    onClear: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    val spec = field.spec(state)
    val secret = field.isCredential
    // Secrets are kept out of saved instance state on purpose.
    var text by if (secret) remember { mutableStateOf(spec.initial) } else rememberSaveable { mutableStateOf(spec.initial) }
    var revealed by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<InputProblem?>(null) }
    val submit = {
        problem = onSubmit(field, text)
        if (problem == null) onDismiss()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(spec.title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = {
                        text = it
                        problem = null
                    },
                    singleLine = true,
                    visualTransformation = if (secret && !revealed) PasswordVisualTransformation() else VisualTransformation.None,
                    keyboardOptions = KeyboardOptions(keyboardType = spec.keyboard, autoCorrectEnabled = false, imeAction = ImeAction.Done),
                    isError = problem != null,
                    supportingText = { Text(problem?.let { stringResource(it.messageRes()) } ?: stringResource(spec.hint)) },
                    trailingIcon = if (secret) {
                        { TextButton(onClick = { revealed = !revealed }) { Text(stringResource(if (revealed) R.string.settings_hide else R.string.settings_show)) } }
                    } else {
                        null
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                onClear?.let { clear ->
                    TextButton(onClick = { clear(); onDismiss() }) { Text(stringResource(R.string.settings_clear_saved_key)) }
                }
            }
        },
        confirmButton = { TextButton(onClick = submit) { Text(stringResource(R.string.action_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
