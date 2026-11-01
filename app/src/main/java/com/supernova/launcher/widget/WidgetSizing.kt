package com.supernova.launcher.widget

import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.util.SizeF
import kotlin.math.ceil
import kotlin.math.max

object WidgetSizing {

    fun defaultSpans(
        context: Context,
        info: AppWidgetProviderInfo,
        cellWidthPx: Int,
        cellHeightPx: Int,
        cols: Int,
        rows: Int
    ): Pair<Int, Int> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            info.targetCellWidth in 1..cols && info.targetCellHeight in 1..rows
        ) {
            return info.targetCellWidth to info.targetCellHeight
        }
        // minWidth/minHeight are already resolved to px by the framework.
        val padding = AppWidgetHostView.getDefaultPaddingForWidget(context, info.provider, null)
        val spanX = ceilDiv(info.minWidth + padding.left + padding.right, cellWidthPx)
        val spanY = ceilDiv(info.minHeight + padding.top + padding.bottom, cellHeightPx)
        return spanX.coerceIn(1, cols) to spanY.coerceIn(1, rows)
    }

    fun reportSize(hostView: AppWidgetHostView, widthPx: Int, heightPx: Int, density: Float) {
        val widthDp = (widthPx - hostView.paddingLeft - hostView.paddingRight) / density
        val heightDp = (heightPx - hostView.paddingTop - hostView.paddingBottom) / density
        if (widthDp <= 0f || heightDp <= 0f) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            hostView.updateAppWidgetSize(Bundle(), listOf(SizeF(widthDp, heightDp)))
        } else {
            // Fixed-cell host: current size is both min and max.
            @Suppress("DEPRECATION")
            hostView.updateAppWidgetSize(null, widthDp.toInt(), heightDp.toInt(), widthDp.toInt(), heightDp.toInt())
        }
    }

    fun initialBindOptions(
        spanX: Int,
        spanY: Int,
        cellWidthPx: Int,
        cellHeightPx: Int,
        density: Float
    ): Bundle {
        val widthDp = (spanX * cellWidthPx / density).toInt()
        val heightDp = (spanY * cellHeightPx / density).toInt()
        return Bundle().apply {
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, widthDp)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, heightDp)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, widthDp)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, heightDp)
            putInt(
                AppWidgetManager.OPTION_APPWIDGET_HOST_CATEGORY,
                AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN
            )
        }
    }

    private fun ceilDiv(value: Int, divisor: Int): Int =
        ceil(value.toDouble() / max(1, divisor)).toInt()
}
