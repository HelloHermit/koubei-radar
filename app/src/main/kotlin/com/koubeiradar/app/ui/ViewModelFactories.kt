package com.koubeiradar.app.ui

import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.koubeiradar.app.AppContainer
import com.koubeiradar.app.ui.agent.AgentViewModel
import com.koubeiradar.app.ui.detail.DetailViewModel
import com.koubeiradar.app.ui.home.HomeViewModel
import com.koubeiradar.app.ui.importer.ImportViewModel
import com.koubeiradar.app.ui.settings.SettingsViewModel

fun homeViewModelFactory(c: AppContainer) = viewModelFactory {
    initializer { HomeViewModel(c.settings, c.shopStore, c.rankers, c.dataSources, c.locationService, c.places) }
}

fun agentViewModelFactory(c: AppContainer) = viewModelFactory {
    initializer { AgentViewModel(c.settings, c.advisorService, c.shopStore, c.rankers, c.dataSources, c.places, c.clock) }
}

fun importViewModelFactory(c: AppContainer) = viewModelFactory {
    initializer { ImportViewModel(c.settings, c.importService, c.imageLoader, c.shopStore, c.shareInbox) }
}

fun detailViewModelFactory(c: AppContainer, shopId: String) = viewModelFactory {
    initializer { DetailViewModel(shopId, c.settings, c.shopStore, c.rankers) }
}

fun settingsViewModelFactory(c: AppContainer) = viewModelFactory {
    initializer { SettingsViewModel(c.settings, c.shopStore) }
}
