package com.supernova.launcher.ui

import android.content.Context
import android.view.View

// The one dp-to-px conversion. Truncates, as every local copy it replaced did,
// so no layout moves by a pixel.
fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

fun Context.dp(value: Float): Float = value * resources.displayMetrics.density

fun View.dp(value: Int): Int = context.dp(value)

fun View.dp(value: Float): Float = context.dp(value)

// A text size from the type scale in res/values/dimens.xml.
fun android.widget.TextView.textSizeOf(@androidx.annotation.DimenRes size: Int) =
    setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, resources.getDimension(size))
