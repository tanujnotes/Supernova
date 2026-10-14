package com.supernova.launcher.gesture

// A launcher action placed on the home screen as a tappable icon (a
// launcher-action shortcut). Encoded into LauncherItem.intentUri so launchItem can route
// it back through runAction - which respects live state like the feed toggle
// or the accessibility requirement - instead of firing a raw intent.
//
// Encoding: "supernova://action/<ACTION>[?p=<payload>]". The payload backs
// JUMP_TO_PAGE (page index) and the launch-app/shortcut targets, matching the
// gesture payload conventions.
object ActionShortcut {

    const val PREFIX = "supernova://action/"

    fun encode(action: GestureAction, payload: String? = null): String =
        PREFIX + action.name + (payload?.takeIf { it.isNotEmpty() }?.let { "?p=$it" }.orEmpty())

    // Null when the uri is not one of ours, so callers can fall through to the
    // arbitrary-intent path.
    fun decode(intentUri: String?): GestureBinding? {
        if (intentUri == null || !intentUri.startsWith(PREFIX)) return null
        val rest = intentUri.removePrefix(PREFIX)
        val name = rest.substringBefore("?p=")
        val payload = if (rest.contains("?p=")) rest.substringAfter("?p=").ifEmpty { null } else null
        val action = GestureAction.fromName(name)
        if (action.needsPayload && payload == null) return null
        return GestureBinding(action, payload)
    }

    // Actions that make sense as a standalone home icon: no NONE, and nothing
    // that only means something bound to a specific gesture direction. Payload
    // actions (launch app/shortcut, jump to page) are offered as their own
    // pickable targets by the UI, not from this list.
    val choices: List<GestureAction> = listOf(
        GestureAction.OPEN_DRAWER,
        GestureAction.OPEN_SEARCH,
        GestureAction.EXPAND_NOTIFICATIONS,
        GestureAction.QUICK_SETTINGS,
        GestureAction.RECENTS,
        GestureAction.LOCK_SCREEN,
        GestureAction.OPEN_SETTINGS,
        GestureAction.DEFAULT_PAGE,
        GestureAction.TOGGLE_DOCK,
        GestureAction.TOGGLE_STATUS_BAR,
        GestureAction.DESKTOP_PREVIEWS,
        GestureAction.OPEN_FEED,
        GestureAction.ASSISTANT,
        GestureAction.VOICE_SEARCH
    )
}
