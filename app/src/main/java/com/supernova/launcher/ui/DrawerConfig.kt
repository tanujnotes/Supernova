package com.supernova.launcher.ui

enum class DrawerStyle {
    VERTICAL_GRID,
    PAGED_GRID,
    VERTICAL_LIST;

    companion object {
        fun fromName(name: String): DrawerStyle =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: VERTICAL_GRID
    }
}

enum class DrawerSort {
    ALPHA,
    ALPHA_REVERSE,
    INSTALL_DATE,
    MOST_USED;

    companion object {
        fun fromName(name: String): DrawerSort =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: ALPHA
    }
}

// Where the drawer's group tab bar renders. NONE hides it entirely, which
// makes group tabs unreachable - the drawer then always shows the All list.
enum class DrawerTabBar {
    TOP,
    BOTTOM,
    NONE;

    companion object {
        fun fromName(name: String): DrawerTabBar =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: TOP
    }
}

// The Tab type for the drawer's group tabs.
enum class DrawerTabStyle {
    CLASSIC,
    BUBBLE;

    companion object {
        fun fromName(name: String): DrawerTabStyle =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: CLASSIC
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
