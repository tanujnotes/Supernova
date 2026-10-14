package com.supernova.launcher.ui

// How an opened folder presents: the classic anchored window card, or a
// full-screen immersive sheet over a dimmed scrim.
enum class FolderMode {
    WINDOW,
    IMMERSIVE;

    companion object {
        fun fromName(name: String): FolderMode =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: WINDOW
    }
}

// How folder content moves when it overflows: vertical scrolling or
// horizontal paging.
enum class FolderScroll {
    VERTICAL,
    HORIZONTAL;

    companion object {
        fun fromName(name: String): FolderScroll =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: HORIZONTAL
    }
}
