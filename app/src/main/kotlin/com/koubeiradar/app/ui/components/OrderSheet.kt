package com.koubeiradar.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.koubeiradar.app.R
import com.koubeiradar.app.platform.orderedDeliveryApps
import com.koubeiradar.core.model.Platform
import com.koubeiradar.core.model.Shop
import com.koubeiradar.data.DeliveryApp

/** The shop to hand over to a delivery app; plain values so it survives rotation. */
data class OrderRequest(val shopName: String, val platforms: Set<Platform>) {
    companion object {
        fun of(shop: Shop) = OrderRequest(shop.name, shop.platforms)

        val Saver = listSaver<OrderRequest?, String>(
            save = { request -> request?.let { listOf(it.shopName) + it.platforms.map(Platform::name) } ?: emptyList() },
            restore = { saved -> saved.firstOrNull()?.let { OrderRequest(it, saved.drop(1).map(Platform::valueOf).toSet()) } },
        )
    }
}

/** "去点单": choose the delivery app; apps the shop is known to be on come first. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OrderPlatformSheet(
    request: OrderRequest,
    isInstalled: (DeliveryApp) -> Boolean,
    onPick: (DeliveryApp) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(bottom = 16.dp)) {
            Text(stringResource(R.string.order_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp))
            Text(
                stringResource(R.string.order_explain, request.shopName),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            orderedDeliveryApps(request.platforms).forEach { app ->
                val note = when {
                    app.platform in request.platforms -> stringResource(R.string.order_shop_available)
                    !isInstalled(app) -> stringResource(R.string.order_not_installed)
                    else -> null
                }
                ListItem(
                    headlineContent = { Text(app.label) },
                    supportingContent = note?.let { { Text(it) } },
                    trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
                    modifier = Modifier.clickable { onPick(app) },
                )
            }
        }
    }
}
