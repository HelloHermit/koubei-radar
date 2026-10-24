package com.koubeiradar.app.ui.components

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.koubeiradar.app.ui.UiText

@Composable
fun UiText.asString(): String = stringResource(resId, *args.toTypedArray())

fun UiText.asString(resources: Resources): String = resources.getString(resId, *args.toTypedArray())

fun Context.findActivity(): Activity? {
    var current = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}
