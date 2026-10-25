package com.koubeiradar.app.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.koubeiradar.app.AppContainer
import com.koubeiradar.app.R
import com.koubeiradar.app.platform.OrderOutcome
import com.koubeiradar.app.ui.agent.AgentScreen
import com.koubeiradar.app.ui.components.KoubeiIcons
import com.koubeiradar.app.ui.components.OrderPlatformSheet
import com.koubeiradar.app.ui.components.OrderRequest
import com.koubeiradar.app.ui.detail.DetailScreen
import com.koubeiradar.app.ui.home.HomeScreen
import com.koubeiradar.app.ui.importer.ImportScreen
import com.koubeiradar.app.ui.settings.SettingsScreen
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

@Serializable
data object HomeRoute

@Serializable
data object AgentRoute

@Serializable
data object ImportRoute

@Serializable
data object SettingsRoute

@Serializable
data class DetailRoute(val shopId: String)

private enum class Tab(val route: Any, @StringRes val label: Int, val icon: ImageVector) {
    HOME(HomeRoute, R.string.nav_home, Icons.Default.Home),
    AGENT(AgentRoute, R.string.nav_agent, KoubeiIcons.Sparkles),
    IMPORT(ImportRoute, R.string.nav_import, KoubeiIcons.Download),
}

private fun NavDestination?.isOn(tab: Tab): Boolean = this?.hierarchy?.any { it.hasRoute(tab.route::class) } == true

/** Switches tab keeping each tab's back stack and state (view models included) alive. */
private fun NavController.switchToTab(tab: Tab) = navigate(tab.route) {
    popUpTo(graph.findStartDestination().id) { saveState = true }
    launchSingleTop = true
    restoreState = true
}

/** Back to the home tab from wherever the user is (another tab, settings, a shop's details). Home is the start of the stack, so it is always there to return to. */
private fun NavController.showHome() {
    if (currentDestination?.hasRoute(HomeRoute::class) != true) popBackStack(HomeRoute, inclusive = false)
}

/** The app: three tabs (推荐 / 智能体 / 导入) with settings and shop details pushed on top. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun KoubeiApp(container: AppContainer) {
    val navController = rememberNavController()
    val resources = LocalResources.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val showMessage: (String) -> Unit = { text ->
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar(text)
        }
    }
    var order by rememberSaveable(stateSaver = OrderRequest.Saver) { mutableStateOf<OrderRequest?>(null) }

    val destination = navController.currentBackStackEntryAsState().value?.destination
    // The bar hides behind the keyboard; hiding it also keeps the chat input directly above the keyboard.
    val showBottomBar = Tab.entries.any { destination.isOn(it) } && !WindowInsets.isImeVisible

    // A share from another app lands on the import tab. This waits for the arrival itself, not for `pending` to become
    // non-null: an import screen that is already alive consumes the payload at once, and the change would go unseen.
    LaunchedEffect(container.shareInbox) {
        container.shareInbox.arrivals.collect { navController.switchToTab(Tab.IMPORT) }
    }

    LaunchedEffect(container.homeRequests) {
        container.homeRequests.requests.collect { navController.showHome() }
    }

    Scaffold(
        snackbarHost = {
            // Without the bar the message would sit under the system navigation bar / keyboard.
            val insets = if (showBottomBar) Modifier else Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))
            SnackbarHost(snackbar, modifier = insets)
        },
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    Tab.entries.forEach { tab ->
                        NavigationBarItem(
                            selected = destination.isOn(tab),
                            onClick = { navController.switchToTab(tab) },
                            icon = { Icon(tab.icon, contentDescription = null) },
                            label = { Text(stringResource(tab.label)) },
                        )
                    }
                }
            }
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = HomeRoute,
            modifier = Modifier.padding(padding).consumeWindowInsets(padding),
        ) {
            composable<HomeRoute> {
                HomeScreen(
                    viewModel = viewModel(factory = homeViewModelFactory(container)),
                    onOpenSettings = { navController.navigate(SettingsRoute) { launchSingleTop = true } },
                    onOpenDetail = { navController.navigate(DetailRoute(it)) },
                    onOrder = { order = OrderRequest.of(it) },
                    onMessage = showMessage,
                )
            }
            composable<AgentRoute> {
                AgentScreen(
                    viewModel = viewModel(factory = agentViewModelFactory(container)),
                    onOpenSettings = { navController.navigate(SettingsRoute) { launchSingleTop = true } },
                    onOpenDetail = { navController.navigate(DetailRoute(it)) },
                    onOrder = { order = OrderRequest.of(it) },
                )
            }
            composable<ImportRoute> {
                ImportScreen(
                    viewModel = viewModel(factory = importViewModelFactory(container)),
                    imageLoader = container.imageLoader,
                    onOpenSettings = { navController.navigate(SettingsRoute) { launchSingleTop = true } },
                    onMerged = { count ->
                        navController.switchToTab(Tab.HOME)
                        showMessage(resources.getString(R.string.import_msg_merged, count))
                    },
                )
            }
            composable<SettingsRoute> {
                SettingsScreen(
                    viewModel = viewModel(factory = settingsViewModelFactory(container)),
                    onBack = navController::navigateUp,
                    onMessage = showMessage,
                )
            }
            composable<DetailRoute> { entry ->
                val route = entry.toRoute<DetailRoute>()
                DetailScreen(
                    viewModel = viewModel(factory = detailViewModelFactory(container, route.shopId)),
                    onBack = navController::navigateUp,
                    onOrder = { order = OrderRequest.of(it) },
                )
            }
        }
    }

    order?.let { request ->
        OrderPlatformSheet(
            request = request,
            isInstalled = container.deliveryLauncher::isInstalled,
            onPick = { app ->
                order = null
                val message = when (container.deliveryLauncher.order(request.shopName, app)) {
                    OrderOutcome.OPENED -> R.string.order_msg_opened
                    OrderOutcome.NOT_INSTALLED -> R.string.order_msg_not_installed
                    OrderOutcome.FAILED -> R.string.order_msg_failed
                }
                showMessage(resources.getString(message, request.shopName, app.label))
            },
            onDismiss = { order = null },
        )
    }
}
