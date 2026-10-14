package com.supernova.launcher.ui

enum class DockBgStyle {
    NONE,
    PILL,
    ROUNDED,
    // Square corners.
    SQUARE;

    companion object {
        fun fromName(name: String): DockBgStyle =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: NONE
    }
}
