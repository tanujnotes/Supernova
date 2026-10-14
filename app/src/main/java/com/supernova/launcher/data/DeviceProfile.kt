package com.supernova.launcher.data

import android.content.Context

// The grid a fresh install starts with, chosen once from the screen the
// launcher first ran on. A phone starts on the default grid for today's phones,
// 5 columns by 6 rows under a 5-icon dock.
// That grid leaves a tablet as a field of stamps with acres between them, so
// wider screens start with more columns and a fuller dock. Chosen once and then written into the
// preference store, rather than derived live from the configuration: a
// foldable's inner and outer screens would otherwise flip the grid on
// every fold and strand whatever sat in the columns that vanished. The
// user can change all three afterwards, exactly as on a phone.
// iconScale is the starting size for every icon surface (desktop, dock,
// drawer, folder): a 48dp icon that fills a phone cell is a stamp in a
// tablet's.
data class DeviceProfile(
    val gridCols: Int,
    val gridRows: Int,
    val dockCount: Int,
    val iconScale: Float
) {

    // `prefs` on this profile's grid, dock and icon sizes: what a fresh
    // install on this screen starts with, and what Reset to default returns
    // to.
    fun applyTo(prefs: LauncherPrefs): LauncherPrefs = prefs.copy(
        gridCols = gridCols,
        gridRows = gridRows,
        dockCount = dockCount,
        iconScale = iconScale,
        dockIconScale = iconScale,
        drawerIconScale = iconScale,
        folderIconScale = iconScale
    )

    companion object {
        // Material's width classes: compact (< 600dp) is a phone, medium
        // (600-839dp) an 8" tablet or an unfolded foldable, expanded a 10"+
        // tablet.
        const val MEDIUM_WIDTH_DP = 600
        private const val EXPANDED_WIDTH_DP = 840

        // True on any screen that gets a tablet profile; for surfaces that
        // size themselves live (the drawer's automatic columns) rather than
        // from the stored grid.
        fun isWide(context: Context): Boolean =
            context.resources.configuration.smallestScreenWidthDp >= MEDIUM_WIDTH_DP

        val PHONE = DeviceProfile(gridCols = 5, gridRows = 6, dockCount = 5, iconScale = 1f)
        val MEDIUM = DeviceProfile(gridCols = 6, gridRows = 5, dockCount = 6, iconScale = 1.2f)
        val EXPANDED = DeviceProfile(gridCols = 7, gridRows = 6, dockCount = 7, iconScale = 1.35f)

        fun forSmallestWidthDp(smallestWidthDp: Int): DeviceProfile = when {
            smallestWidthDp >= EXPANDED_WIDTH_DP -> EXPANDED
            smallestWidthDp >= MEDIUM_WIDTH_DP -> MEDIUM
            else -> PHONE
        }

        // smallestScreenWidthDp is the shorter side of the current screen in
        // dp, which is what the profile keys on: a tablet is a tablet in
        // either orientation.
        fun forDevice(context: Context): DeviceProfile =
            forSmallestWidthDp(context.resources.configuration.smallestScreenWidthDp)
    }
}
