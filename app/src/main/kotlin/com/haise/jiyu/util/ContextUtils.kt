package com.haise.jiyu.util

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper

/**
 * Compose `LocalView.current.context` nemusí být přímo Activity (může být obalená v
 * [ContextWrapper], např. při zobrazení v dialogu nebo v preview) - přímé přetypování
 * `as Activity` pak padá na ClassCastException.
 */
fun Context.findActivity(): Activity? {
    var ctx: Context? = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}
