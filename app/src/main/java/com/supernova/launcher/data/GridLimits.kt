package com.supernova.launcher.data

// The largest grids the pickers offer. Labs' Big grid size options
// lifts them to the BIG values, for heavily customized setups. Stored values
// are always clamped to the big range, so a grid set while it was on stays
// as it is when it goes off; only the pickers stop offering more.
object GridLimits {
    const val DESKTOP = 12
    const val DESKTOP_BIG = 24
    const val DOCK = 7
    const val DOCK_BIG = 12
    const val DRAWER_COLUMNS = 8
    const val DRAWER_COLUMNS_BIG = 12
    const val DRAWER_ROWS = 12
    const val DRAWER_ROWS_BIG = 20
    const val FOLDER_COLUMNS = 6
    const val FOLDER_COLUMNS_BIG = 10

    fun desktop(prefs: LauncherPrefs) = if (prefs.labsBigGrid) DESKTOP_BIG else DESKTOP
    fun drawerColumns(prefs: LauncherPrefs) = if (prefs.labsBigGrid) DRAWER_COLUMNS_BIG else DRAWER_COLUMNS
    fun drawerRows(prefs: LauncherPrefs) = if (prefs.labsBigGrid) DRAWER_ROWS_BIG else DRAWER_ROWS
}
