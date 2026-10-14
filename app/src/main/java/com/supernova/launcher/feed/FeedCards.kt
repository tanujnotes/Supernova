package com.supernova.launcher.feed

import kotlinx.serialization.Serializable

// Model-v5 custom feed cards. Every field is default-supplied or nullable so
// v4 documents (no feedCards at all) and hand-rolled/junk JSON decode without
// migration work, matching the LauncherItem discipline.
//
// featuredType is one of the FeaturedType names; featuredTarget encodes the
// launch target with the same conventions the gesture payloads use:
//   APP      -> "pkg/cls@userSerial"
//   SHORTCUT -> "pkg|shortcutId@userSerial"
//   WIDGET   -> appWidgetId as a decimal string; widgetProvider carries the
//               flattened provider component for post-restore rebinds.
@Serializable
data class FeedCard(
    val id: String,
    val name: String = "",
    val featuredType: String? = null,
    val featuredTarget: String? = null,
    // Host id of a WIDGET featured card. Nulled on backup import (host ids
    // never survive a restore) exactly like LauncherItem.appWidgetId.
    val appWidgetId: Int? = null,
    val widgetProvider: String? = null,
    val actions: List<CardAction> = emptyList(),
    val conditions: List<CardCondition> = emptyList(),
    val enabled: Boolean = true,
    // "Pin": the card stays at the top of the feed, ahead of the
    // activated ones.
    val pinned: Boolean = false,
    // Height of a featured widget's tile ("Widget height").
    val widgetHeightDp: Int = 160,
    // The "Activate" switch: off keeps the conditions but stops them
    // activating the card (it then lives in the feed and search only).
    val activationEnabled: Boolean = true
) {
    // Activation: a card with conditions is "activated" while they all
    // hold; that is what promotes it into the app drawer. A card without
    // conditions is never activated - it lives in the feed and in search.
    val hasConditions: Boolean get() = conditions.isNotEmpty()

    companion object {
        // Action chips are a compact row; more than six stops being one.
        const val MAX_ACTIONS = 6
    }
}

enum class FeaturedType {
    APP,
    SHORTCUT,
    WIDGET;

    companion object {
        fun fromName(name: String?): FeaturedType? =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
    }
}

// One tappable chip under a card. type/target reuse the FeaturedType names and
// target encodings (APP and SHORTCUT only; a widget is never an action).
@Serializable
data class CardAction(
    val type: String = "",
    val target: String = "",
    val label: String = ""
)

// An activation condition; all of a card's conditions must hold for it to
// activate (evaluated in feed/CardVisibility while the feed or drawer is
// shown, never while both are hidden).
//   DAY_TIME  -> days (1 = Monday .. 7 = Sunday, null/empty = every day) plus
//                an optional startMinute/endMinute minute-of-day range that
//                may wrap past midnight (e.g. 1320..360 = 22:00-06:00).
//   WIFI      -> ssid matches the current Wi-Fi network name.
//   BLUETOOTH -> btName matches a connected headset/A2DP device name.
@Serializable
data class CardCondition(
    val kind: String = "",
    val days: List<Int>? = null,
    val startMinute: Int? = null,
    val endMinute: Int? = null,
    val ssid: String? = null,
    val btName: String? = null
)

enum class ConditionKind {
    DAY_TIME,
    WIFI,
    BLUETOOTH;

    companion object {
        fun fromName(name: String?): ConditionKind? =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
    }
}
