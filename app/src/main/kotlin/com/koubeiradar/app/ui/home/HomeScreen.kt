package com.koubeiradar.app.ui.home

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.koubeiradar.app.R
import com.koubeiradar.app.service.PlaceState
import com.koubeiradar.app.settings.ScoringPreset
import com.koubeiradar.app.ui.components.KoubeiIcons
import com.koubeiradar.app.ui.components.LoadingPanel
import com.koubeiradar.app.ui.components.MessagePanel
import com.koubeiradar.app.ui.components.NoticeCard
import com.koubeiradar.app.ui.components.NoticeTone
import com.koubeiradar.app.ui.components.ScoringPresetSelector
import com.koubeiradar.app.ui.components.ShopCard
import com.koubeiradar.app.ui.components.asString
import com.koubeiradar.app.ui.components.findActivity
import com.koubeiradar.app.ui.criteria.CriteriaSheet
import com.koubeiradar.app.ui.criteria.chips
import com.koubeiradar.app.ui.criteria.isUnrestricted
import com.koubeiradar.app.ui.criteria.label
import com.koubeiradar.app.ui.messageRes
import com.koubeiradar.core.model.Shop
import com.koubeiradar.data.ShopSourceException

private val LOCATION_PERMISSIONS = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

/** 推荐: the ranked shops around the user. */
@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onOpenSettings: () -> Unit,
    onOpenDetail: (shopId: String) -> Unit,
    onOrder: (Shop) -> Unit,
    onMessage: (String) -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val resources = LocalResources.current
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        val granted = grants.values.any { it }
        val activity = context.findActivity()
        // The system stops showing the dialog after repeated denials; then only the app settings can grant the permission.
        val permanent = !granted && activity != null &&
            LOCATION_PERMISSIONS.none { ActivityCompat.shouldShowRequestPermissionRationale(activity, it) }
        viewModel.onPermissionResult(granted, permanent)
    }
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                HomeEvent.RequestLocationPermission -> permissionLauncher.launch(LOCATION_PERMISSIONS)
                HomeEvent.OpenAppSettings -> try {
                    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
                } catch (e: ActivityNotFoundException) {
                    onMessage(resources.getString(R.string.home_msg_permission_denied))
                }

                is HomeEvent.Message -> onMessage(event.text.asString(resources))
            }
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.onScreenResumed() }

    var showCriteria by rememberSaveable { mutableStateOf(false) }
    HomeContent(
        state = state,
        onRefresh = viewModel::refresh,
        onOpenSettings = onOpenSettings,
        onEditCriteria = { showCriteria = true },
        onSetPreset = viewModel::setPreset,
        onRelax = viewModel::relaxCriteria,
        onLocate = viewModel::onLocateRequested,
        onEditPlace = viewModel::openAddressDialog,
        onDismissDemoBanner = viewModel::dismissDemoBanner,
        onOpenDetail = onOpenDetail,
        onOrder = onOrder,
    )
    if (showCriteria) {
        CriteriaSheet(criteria = state.criteria, onApply = viewModel::setCriteria, onDismiss = { showCriteria = false })
    }
    state.addressDialog?.let { dialog ->
        AddressDialog(
            state = dialog,
            canGeocode = state.canGeocode,
            onSubmit = viewModel::submitAddress,
            onUseCurrent = {
                viewModel.closeAddressDialog()
                viewModel.onLocateRequested()
            },
            onOpenSettings = {
                viewModel.closeAddressDialog()
                onOpenSettings()
            },
            onDismiss = viewModel::closeAddressDialog,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeContent(
    state: HomeUiState,
    onRefresh: () -> Unit,
    onOpenSettings: () -> Unit,
    onEditCriteria: () -> Unit,
    onSetPreset: (ScoringPreset) -> Unit,
    onRelax: () -> Unit,
    onLocate: () -> Unit,
    onEditPlace: () -> Unit,
    onDismissDemoBanner: () -> Unit,
    onOpenDetail: (String) -> Unit,
    onOrder: (Shop) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    IconButton(onClick = onRefresh) { Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.home_action_refresh)) }
                    IconButton(onClick = onOpenSettings) { Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.home_action_settings)) }
                },
            )
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = state.load is LoadState.Loading && state.shops.isNotEmpty(),
            onRefresh = onRefresh,
            modifier = Modifier.padding(padding),
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item(key = "place") { PlaceRow(state, onEditPlace, onOpenSettings) }
                if (state.showDemoBanner) {
                    item(key = "banner") {
                        NoticeCard(text = stringResource(R.string.home_demo_banner)) {
                            TextButton(onClick = onOpenSettings) { Text(stringResource(R.string.home_demo_banner_action)) }
                            TextButton(onClick = onDismissDemoBanner) { Text(stringResource(R.string.home_demo_banner_dismiss)) }
                        }
                    }
                }
                item(key = "preset") { ScoringPresetSelector(state.preset, onSetPreset) }
                item(key = "criteria") { CriteriaRow(state, onEditCriteria) }
                content(state, onRefresh, onOpenSettings, onEditCriteria, onRelax, onLocate, onEditPlace, onOpenDetail, onOrder)
            }
        }
    }
}

private fun LazyListScope.content(
    state: HomeUiState,
    onRetry: () -> Unit,
    onOpenSettings: () -> Unit,
    onEditCriteria: () -> Unit,
    onRelax: () -> Unit,
    onLocate: () -> Unit,
    onEditPlace: () -> Unit,
    onOpenDetail: (String) -> Unit,
    onOrder: (Shop) -> Unit,
) {
    when (val content = state.content) {
        HomeContent.Results -> {
            item(key = "count") {
                Text(
                    stringResource(R.string.home_results_count, state.shops.size),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            (state.load as? LoadState.Failed)?.let { failed ->
                item(key = "stale-error") {
                    NoticeCard(text = stringResource(failed.kind.messageRes()), tone = NoticeTone.ERROR, icon = Icons.Default.Warning) {
                        TextButton(onClick = onRetry) { Text(stringResource(R.string.action_retry)) }
                    }
                }
            }
            items(state.shops, key = { it.shop.id }) { scored ->
                ShopCard(scored, onDetail = { onOpenDetail(scored.shop.id) }, onOrder = { onOrder(scored.shop) })
            }
            item(key = "disclaimer") {
                Text(
                    stringResource(R.string.home_score_disclaimer),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
        }

        HomeContent.Loading -> item(key = "loading") { LoadingPanel(stringResource(R.string.home_loading)) }

        HomeContent.NeedsLocation -> item(key = "needs-location") {
            val permanentlyDenied = (state.place as? PlaceState.PermissionDenied)?.permanent == true
            MessagePanel(
                icon = Icons.Default.LocationOn,
                title = stringResource(R.string.home_needs_location_title),
                body = stringResource(R.string.home_needs_location_body),
            ) {
                Button(onClick = onLocate, enabled = !state.locating) {
                    Text(stringResource(if (state.locating) R.string.home_location_locating else if (permanentlyDenied) R.string.home_action_open_app_settings else R.string.home_action_allow_location))
                }
                OutlinedButton(onClick = onEditPlace) { Text(stringResource(R.string.home_action_enter_address)) }
            }
        }

        is HomeContent.Failed -> item(key = "failed") {
            MessagePanel(
                icon = Icons.Default.Warning,
                title = stringResource(R.string.home_error_title),
                body = stringResource(content.kind.messageRes()),
            ) {
                Button(onClick = onRetry) { Text(stringResource(R.string.action_retry)) }
                if (content.kind == ShopSourceException.Kind.AUTH) {
                    OutlinedButton(onClick = onOpenSettings) { Text(stringResource(R.string.action_go_settings)) }
                }
            }
        }

        HomeContent.NoShops -> item(key = "no-shops") {
            MessagePanel(
                icon = Icons.Default.Search,
                title = stringResource(R.string.home_no_shops_title),
                body = stringResource(R.string.home_no_shops_body),
            ) {
                Button(onClick = onRetry) { Text(stringResource(R.string.home_action_reload)) }
                OutlinedButton(onClick = onEditPlace) { Text(stringResource(R.string.home_action_change_place)) }
            }
        }

        HomeContent.NoMatch -> item(key = "no-match") {
            MessagePanel(
                icon = Icons.Default.Search,
                title = stringResource(R.string.home_no_match_title),
                body = stringResource(R.string.home_no_match_body, state.poolSize),
            ) {
                if (!state.criteria.isUnrestricted()) Button(onClick = onRelax) { Text(stringResource(R.string.home_action_relax)) }
                OutlinedButton(onClick = onEditCriteria) { Text(stringResource(R.string.home_action_edit_criteria)) }
            }
        }
    }
}

/** Where the search is centred (tap to change) and which data source is in use (tap to open the settings). */
@Composable
private fun PlaceRow(state: HomeUiState, onEditPlace: () -> Unit, onOpenSettings: () -> Unit) {
    val place = state.place
    val title = when {
        state.locating -> stringResource(R.string.home_location_locating)
        place is PlaceState.Device -> stringResource(R.string.home_location_device)
        place is PlaceState.Manual -> place.address
        else -> stringResource(R.string.home_location_unknown)
    }
    val detail = when {
        place is PlaceState.Device && place.accuracyMeters != null -> stringResource(R.string.home_location_accuracy, place.accuracyMeters)
        place is PlaceState.Device || place is PlaceState.Manual -> null
        else -> stringResource(R.string.home_location_hint)
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Surface(onClick = onEditPlace, shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(Icons.Default.LocationOn, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }
        if (state.locating) LinearProgressIndicator(Modifier.fillMaxWidth())
        AssistChip(
            onClick = onOpenSettings,
            label = { Text(stringResource(if (state.source == SourceKind.AMAP) R.string.home_source_amap else R.string.home_source_demo)) },
        )
    }
}

@Composable
private fun CriteriaRow(state: HomeUiState, onEdit: () -> Unit) {
    val chips = state.criteria.chips()
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            AssistChip(
                onClick = onEdit,
                label = { Text(stringResource(if (chips.isEmpty()) R.string.criteria_edit_unrestricted else R.string.criteria_edit)) },
                leadingIcon = { Icon(KoubeiIcons.Tune, contentDescription = null, modifier = Modifier.size(18.dp)) },
            )
        }
        items(chips) { chip -> AssistChip(onClick = onEdit, label = { Text(chip.label()) }) }
    }
}

@Composable
private fun AddressDialog(
    state: AddressDialogState,
    canGeocode: Boolean,
    onSubmit: (String) -> Unit,
    onUseCurrent: () -> Unit,
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    var text by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.address_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onUseCurrent, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.LocationOn, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(stringResource(R.string.address_use_current), modifier = Modifier.padding(start = 8.dp))
                }
                if (canGeocode) {
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it.take(MAX_ADDRESS_CHARS) },
                        label = { Text(stringResource(R.string.address_label)) },
                        placeholder = { Text(stringResource(R.string.address_hint)) },
                        singleLine = true,
                        enabled = !state.searching,
                        isError = state.error != null,
                        supportingText = state.error?.let { { Text(it.asString()) } },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { onSubmit(text) }),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (state.searching) LinearProgressIndicator(Modifier.fillMaxWidth())
                } else {
                    Text(stringResource(R.string.home_address_needs_key), style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = {
            if (canGeocode) {
                TextButton(onClick = { onSubmit(text) }, enabled = !state.searching) { Text(stringResource(R.string.address_search)) }
            } else {
                TextButton(onClick = onOpenSettings) { Text(stringResource(R.string.action_go_settings)) }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

private const val MAX_ADDRESS_CHARS = 100
