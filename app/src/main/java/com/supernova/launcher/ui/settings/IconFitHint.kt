package com.supernova.launcher.ui.settings

import android.content.Context
import com.supernova.launcher.R
import com.supernova.launcher.data.DeviceProfile
import com.supernova.launcher.data.LauncherPrefs
import com.supernova.launcher.ui.DrawerStyle
import com.supernova.launcher.ui.IconFitRoom

// The note under an Icon size row when icons at that size don't fit their
// grid cells and are drawn smaller (IconFitRoom), so a larger size seemingly
// does nothing; null when they fit. Worked out against the room the home
// screen and the drawer last had, with this row's grid. Labels are not in
// it: they never change an icon's size (#383).
class IconFitHint(private val context: Context) {

    private val density get() = context.resources.displayMetrics.density

    private fun dp(value: Int): Int = (value * density).toInt()

    // The desktop's icons, and the drawer's while it follows the desktop.
    fun desktop(prefs: LauncherPrefs): CharSequence? {
        val cell = IconFitRoom.desktopCell(prefs.gridCols, prefs.gridRows)
        val fitted = cell != null && IconFitRoom.desktopFitted(
            cell.first, cell.second, (dp(ICON_BASE_DP) * prefs.iconScale).toInt(), dp(ICON_PADDING_DP)
        )
        return note(fitted || (prefs.drawerMatchDesktop && drawerFitted(prefs, prefs.iconScale)))
    }

    fun drawer(prefs: LauncherPrefs): CharSequence? = note(drawerFitted(prefs, prefs.drawerIconScale))

    private fun note(fitted: Boolean): CharSequence? =
        if (fitted) context.getString(R.string.icon_size_fitted) else null

    // As the drawer lays its grid out (DrawerController.columnCount,
    // refreshGridRows), in portrait.
    private fun drawerFitted(prefs: LauncherPrefs, scale: Float): Boolean {
        if (prefs.drawerStyle == DrawerStyle.VERTICAL_LIST) return false
        val metrics = context.resources.displayMetrics
        val window = minOf(metrics.widthPixels, metrics.heightPixels)
        val width = (window - 2 * dp(prefs.drawerPaddingH)).coerceAtLeast(1)
        val columns = prefs.drawerColumns.takeIf { it > 0 } ?: run {
            val cellDp = if (DeviceProfile.isWide(context)) WIDE_CELL_DP else MIN_CELL_DP
            (window / (density * cellDp * scale.coerceAtLeast(1f)).toInt().coerceAtLeast(1)).coerceAtLeast(MIN_COLUMNS)
        }
        val rows = prefs.drawerRows
        val height = IconFitRoom.drawerHeightPx
        val rowPx = if (prefs.drawerStyle == DrawerStyle.VERTICAL_GRID && rows > 0 && height > 0) height / rows else 0
        return IconFitRoom.drawerFitted(
            width / columns, dp(TILE_PADDING_H_DP), rowPx, (dp(ICON_BASE_DP) * scale).toInt()
        )
    }

    private companion object {
        // LauncherActivity's icon box and item padding, and the drawer's
        // tile side padding and automatic cell (DrawerController).
        const val ICON_BASE_DP = IconFitRoom.BASE_DP
        const val ICON_PADDING_DP = IconFitRoom.PADDING_DP
        const val TILE_PADDING_H_DP = IconFitRoom.PADDING_DP
        const val MIN_CELL_DP = 72f
        const val WIDE_CELL_DP = 96f
        const val MIN_COLUMNS = 3
    }
}
