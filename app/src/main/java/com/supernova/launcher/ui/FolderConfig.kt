package com.supernova.launcher.ui

import com.supernova.launcher.data.SettingEnumSerializer

// How an opened folder presents: the classic anchored window card, or a
// full-screen immersive sheet over a dimmed scrim.
@kotlinx.serialization.Serializable(with = FolderMode.Names::class)
enum class FolderMode {
    WINDOW,
    IMMERSIVE;

    // Its name in the store and in a backup, read back leniently.
    object Names : SettingEnumSerializer<FolderMode>("FolderMode", entries, WINDOW)

    companion object {
        fun fromName(name: String): FolderMode = Names.parse(name)
    }
}

// How folder content moves when it overflows: vertical scrolling or
// horizontal paging.
@kotlinx.serialization.Serializable(with = FolderScroll.Names::class)
enum class FolderScroll {
    VERTICAL,
    HORIZONTAL;

    // Its name in the store and in a backup, read back leniently.
    object Names : SettingEnumSerializer<FolderScroll>("FolderScroll", entries, HORIZONTAL)

    companion object {
        fun fromName(name: String): FolderScroll = Names.parse(name)
    }
}
