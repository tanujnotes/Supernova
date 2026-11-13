package com.supernova.launcher.ui

import com.supernova.launcher.data.SettingEnumSerializer

@kotlinx.serialization.Serializable(with = DockBgStyle.Names::class)
// Settings offer it as a switch: off is NONE, on is ROUNDED at the
// Background radius (dockBgRadius). SQUARE is what a dock set before the
// switch keeps until its radius is changed, and draws at radius 0.
enum class DockBgStyle {
    NONE,
    ROUNDED,
    SQUARE;

    // Its name in the store and in a backup, read back leniently.
    // Pill was removed; a dock that had it keeps a background, rounded.
    object Names : SettingEnumSerializer<DockBgStyle>(
        "DockBgStyle", entries, NONE, aliases = mapOf("PILL" to ROUNDED)
    )

    companion object {
        fun fromName(name: String): DockBgStyle = Names.parse(name)
    }
}
