package com.supernova.launcher.ui

import com.supernova.launcher.data.SettingEnumSerializer

@kotlinx.serialization.Serializable(with = DrawerStyle.Names::class)
enum class DrawerStyle {
    VERTICAL_GRID,
    PAGED_GRID,
    VERTICAL_LIST;

    // Its name in the store and in a backup, read back leniently.
    object Names : SettingEnumSerializer<DrawerStyle>("DrawerStyle", entries, VERTICAL_GRID)

    companion object {
        fun fromName(name: String): DrawerStyle = Names.parse(name)
    }
}

@kotlinx.serialization.Serializable(with = DrawerSort.Names::class)
enum class DrawerSort {
    ALPHA,
    ALPHA_REVERSE,
    INSTALL_DATE,
    MOST_USED;

    // Its name in the store and in a backup, read back leniently.
    object Names : SettingEnumSerializer<DrawerSort>("DrawerSort", entries, ALPHA)

    companion object {
        fun fromName(name: String): DrawerSort = Names.parse(name)
    }
}

// Where the drawer's group tab bar renders. NONE hides it entirely, which
// makes group tabs unreachable - the drawer then always shows the All list.
@kotlinx.serialization.Serializable(with = DrawerTabBar.Names::class)
enum class DrawerTabBar {
    TOP,
    BOTTOM,
    NONE;

    // Its name in the store and in a backup, read back leniently.
    object Names : SettingEnumSerializer<DrawerTabBar>("DrawerTabBar", entries, TOP)

    companion object {
        fun fromName(name: String): DrawerTabBar = Names.parse(name)
    }
}

// The Tab type for the drawer's group tabs.
@kotlinx.serialization.Serializable(with = DrawerTabStyle.Names::class)
enum class DrawerTabStyle {
    CLASSIC,
    BUBBLE;

    // Its name in the store and in a backup, read back leniently.
    object Names : SettingEnumSerializer<DrawerTabStyle>("DrawerTabStyle", entries, CLASSIC)

    companion object {
        fun fromName(name: String): DrawerTabStyle = Names.parse(name)
    }
}

// Optional header action buttons beside the drawer search field (C10),
// stored comma-joined in the drawerActionButtons pref. A stored SEARCH (since
// removed) is simply dropped by decode.
enum class DrawerActionButton {
    PLAY_STORE,
    SETTINGS;

    companion object {
        fun decode(raw: String): List<DrawerActionButton> =
            raw.split(',').mapNotNull { token ->
                entries.firstOrNull { it.name.equals(token.trim(), ignoreCase = true) }
            }

        fun encode(buttons: List<DrawerActionButton>): String =
            buttons.joinToString(",") { it.name }
    }
}
