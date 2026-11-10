package com.supernova.launcher.ui

import com.supernova.launcher.data.SettingEnumSerializer

@kotlinx.serialization.Serializable(with = DockBgStyle.Names::class)
enum class DockBgStyle {
    NONE,
    PILL,
    ROUNDED,
    // Square corners.
    SQUARE;

    // Its name in the store and in a backup, read back leniently.
    object Names : SettingEnumSerializer<DockBgStyle>("DockBgStyle", entries, NONE)

    companion object {
        fun fromName(name: String): DockBgStyle = Names.parse(name)
    }
}
