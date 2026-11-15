package com.supernova.launcher.feed

import kotlinx.serialization.Serializable

// Model-v5 custom feed cards. Every field is default-supplied or nullable so
// v4 documents (no feedCards at all) and hand-rolled/junk JSON decode without
// migration work, matching the LauncherItem discipline. Keys a card no
// longer has (the retired drawer conditions: conditions, activationEnabled)
// are ignored on decode.
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
    // Host id of a WIDGET featured card. Nulled on backup import unless still
    // bound here, exactly like LauncherItem.appWidgetId.
    val appWidgetId: Int? = null,
    val widgetProvider: String? = null,
    val actions: List<CardAction> = emptyList(),
    val enabled: Boolean = true,
    // "Pin to top": the card stays at the top of the feed.
    val pinned: Boolean = false,
    // Height of a featured widget's tile ("Widget height"). Read through
    // widgetHeight, which keeps an old or hand-edited value in range.
    val widgetHeightDp: Int = DEFAULT_WIDGET_HEIGHT_DP,
    // "Card background": off draws the card bare, with no plate and no
    // header row, so a featured widget sits on the page as on the home
    // screen. Its menu is then a long-press away.
    val showFrame: Boolean = true,
    // "Fill the page": a featured widget grows so the whole card fills the
    // feed page's visible height (CardFill). Elsewhere (search, previews)
    // it keeps widgetHeight. Absent in older stores, so off.
    val fillPage: Boolean = false
) {
    // The stored widget height, kept to the stepper's range: a backup or an
    // old store may hold anything.
    val widgetHeight: Int get() = widgetHeightDp.coerceIn(WIDGET_HEIGHT_MIN_DP, WIDGET_HEIGHT_MAX_DP)

    companion object {
        // Action chips are a compact row; more than six stops being one.
        const val MAX_ACTIONS = 6

        // The "Widget height" stepper.
        const val DEFAULT_WIDGET_HEIGHT_DP = 160
        const val WIDGET_HEIGHT_MIN_DP = 60
        const val WIDGET_HEIGHT_MAX_DP = 800
        const val WIDGET_HEIGHT_STEP_DP = 10

        // A card made for a widget just picked: the widget on its own, with
        // no plate or title, the way it sits on the home screen. Card
        // background turns it back on.
        fun newWidgetCard(id: String): FeedCard = FeedCard(id = id, showFrame = false)
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
