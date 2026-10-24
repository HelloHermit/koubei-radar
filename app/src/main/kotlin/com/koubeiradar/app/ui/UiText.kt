package com.koubeiradar.app.ui

import androidx.annotation.StringRes

/**
 * A user-visible message that is resolved to a string only in the UI layer, so view models stay free of Android
 * resources and their state can be compared in plain unit tests.
 */
data class UiText(@StringRes val resId: Int, val args: List<Any>) {
    constructor(@StringRes resId: Int, vararg formatArgs: Any) : this(resId, formatArgs.toList())
}
