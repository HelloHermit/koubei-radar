package com.koubeiradar.app.platform

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

/** Asks the UI to show the home tab, from outside it (a tap on the daily recommendation). */
class HomeRequests {
    private val channel = Channel<Unit>(Channel.CONFLATED)
    val requests: Flow<Unit> = channel.receiveAsFlow()

    fun request() {
        channel.trySend(Unit)
    }
}
