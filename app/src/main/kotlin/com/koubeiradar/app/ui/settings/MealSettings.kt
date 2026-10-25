package com.koubeiradar.app.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDefaults
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.koubeiradar.app.R
import com.koubeiradar.app.settings.Meal
import com.koubeiradar.app.ui.components.SectionTitle
import com.koubeiradar.app.ui.format.Formatters
import java.time.LocalTime

@StringRes
private fun Meal.nameRes(): Int = when (this) {
    Meal.LUNCH -> R.string.meal_lunch
    Meal.DINNER -> R.string.meal_dinner
}

@StringRes
private fun Meal.titleRes(): Int = when (this) {
    Meal.LUNCH -> R.string.settings_meals_lunch
    Meal.DINNER -> R.string.settings_meals_dinner
}

/** 每日推荐: a switch and a time for each meal, the weekday rule, what the feature uses, and a test button. */
@Composable
internal fun MealSection(
    state: SettingsUiState,
    onToggle: (Meal, Boolean) -> Unit,
    onPickTime: (Meal) -> Unit,
    onWeekdaysOnly: (Boolean) -> Unit,
    onShowBlocked: () -> Unit,
    onSendTest: () -> Unit,
) {
    val meals = state.meals
    SectionTitle(stringResource(R.string.settings_section_meals), Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
    Text(
        stringResource(
            R.string.settings_meals_subtitle,
            stringResource(R.string.meal_lunch), Formatters.time(meals.lunchTime),
            stringResource(R.string.meal_dinner), Formatters.time(meals.dinnerTime),
        ),
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(horizontal = 16.dp),
    )
    Meal.entries.forEach { meal ->
        MealRow(meal, meals.isEnabled(meal), meals.timeOf(meal), onToggle = { onToggle(meal, it) }, onPickTime = { onPickTime(meal) })
    }
    ListItem(
        headlineContent = { Text(stringResource(R.string.settings_meals_weekdays)) },
        supportingContent = { Text(stringResource(R.string.settings_meals_weekdays_summary)) },
        trailingContent = { Switch(checked = meals.weekdaysOnly, onCheckedChange = null) },
        modifier = Modifier.toggleable(value = meals.weekdaysOnly, role = Role.Switch, onValueChange = onWeekdaysOnly),
    )
    if (meals.anyEnabled && !state.notificationsAllowed) {
        ListItem(
            headlineContent = { Text(stringResource(R.string.settings_meals_blocked), color = MaterialTheme.colorScheme.error) },
            supportingContent = { Text(stringResource(R.string.settings_meals_blocked_summary)) },
            modifier = Modifier.clickable(onClick = onShowBlocked),
        )
    }
    Column(Modifier.padding(horizontal = 16.dp)) {
        Text(
            stringResource(R.string.settings_meals_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val place = state.savedPlace
        Text(
            when {
                place == null -> stringResource(R.string.settings_meals_place_none)
                place.fromDevice && place.address.isBlank() -> stringResource(R.string.settings_meals_place_device)
                else -> stringResource(R.string.settings_meals_place_address, place.address)
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
        OutlinedButton(onClick = onSendTest, enabled = !state.sendingTest, modifier = Modifier.padding(top = 8.dp)) {
            Text(stringResource(R.string.settings_meals_test))
        }
        Text(
            stringResource(R.string.settings_meals_test_summary),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun MealRow(meal: Meal, enabled: Boolean, time: LocalTime, onToggle: (Boolean) -> Unit, onPickTime: () -> Unit) {
    val title = stringResource(meal.titleRes())
    val timeText = Formatters.time(time)
    val timeDescription = stringResource(R.string.settings_meals_time_cd, stringResource(meal.nameRes()), timeText)
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = {
            TextButton(onClick = onPickTime, modifier = Modifier.semantics { contentDescription = timeDescription }) { Text(timeText) }
        },
        trailingContent = {
            Switch(checked = enabled, onCheckedChange = onToggle, modifier = Modifier.semantics { contentDescription = title })
        },
    )
}

/** A 24-hour time picker in a dialog; whole minutes. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MealTimeDialog(meal: Meal, initial: LocalTime, onConfirm: (hour: Int, minute: Int) -> Unit, onDismiss: () -> Unit) {
    val picker = rememberTimePickerState(initialHour = initial.hour, initialMinute = initial.minute, is24Hour = true)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_meals_time_dialog_title, stringResource(meal.nameRes()))) },
        text = {
            Box(Modifier.verticalScroll(rememberScrollState())) {
                TimePicker(state = picker, layoutType = TimePickerDefaults.layoutType())
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(picker.hour, picker.minute); onDismiss() }) { Text(stringResource(R.string.action_confirm)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** Explains why the notification permission is needed, before the system asks. */
@Composable
internal fun NotificationRationaleDialog(onContinue: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_meals_rationale_title)) },
        text = { Text(stringResource(R.string.settings_meals_rationale_body)) },
        confirmButton = { TextButton(onClick = onContinue) { Text(stringResource(R.string.settings_meals_rationale_continue)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** Notifications cannot be shown; the way out is the system's notification settings of the app. */
@Composable
internal fun NotificationsBlockedDialog(onOpenSettings: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_meals_denied_title)) },
        text = { Text(stringResource(R.string.settings_meals_denied_body)) },
        confirmButton = { TextButton(onClick = { onOpenSettings(); onDismiss() }) { Text(stringResource(R.string.home_action_open_app_settings)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
