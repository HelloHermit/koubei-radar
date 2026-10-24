package com.koubeiradar.app

import android.app.Application

open class KoubeiApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = createContainer()
    }

    /** Test seam only (Robolectric has no AndroidKeyStore): a test subclass may build the container with another key provider. */
    protected open fun createContainer(): AppContainer = AppContainer(this)
}
