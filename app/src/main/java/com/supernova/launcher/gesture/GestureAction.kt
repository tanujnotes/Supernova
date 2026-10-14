package com.supernova.launcher.gesture

enum class Gesture {
    SWIPE_UP,
    SWIPE_DOWN,
    DOUBLE_TAP,
    PINCH_IN,
    PINCH_OUT,
    TWO_FINGER_SWIPE_UP,
    TWO_FINGER_SWIPE_DOWN,
    // Fire only when the workspace can't scroll further that way (edge page,
    // infinite scroll off) so page swipes keep working.
    SWIPE_LEFT,
    SWIPE_RIGHT,
    // A second tap that turns into a swipe within the double-tap window.
    DOUBLE_TAP_SWIPE_UP,
    DOUBLE_TAP_SWIPE_DOWN,
    // Two pointers rotating around their midpoint without pinching.
    TWO_FINGER_ROTATE_CW,
    TWO_FINGER_ROTATE_CCW;

    companion object {
        fun fromName(name: String): Gesture? = entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
    }
}

enum class GestureAction {
    NONE,
    OPEN_DRAWER,
    EXPAND_NOTIFICATIONS,
    OPEN_SETTINGS,
    LOCK_SCREEN,
    DEFAULT_PAGE,
    OPEN_SEARCH,
    RECENTS,
    QUICK_SETTINGS,
    ASSISTANT,
    LAUNCH_APP,
    LAUNCH_SHORTCUT,
    TOGGLE_STATUS_BAR,
    TOGGLE_DOCK,
    // Payload = 0-based page index.
    JUMP_TO_PAGE,
    DESKTOP_PREVIEWS,
    // Opens the feed page pane (phase 7); a toast points to Settings while
    // the feed master toggle is off.
    OPEN_FEED,
    // Fire-and-forget voice search / voice assistant.
    VOICE_SEARCH,

    // An intent an app's own shortcut screen made (the Shortcuts tab: a
    // direct dial, a Maps route). Payload = the intent's URI.
    LAUNCH_INTENT,

    // Re-runs a saved query in the search overlay. The payload is the query
    // itself, so this only ever comes from a pinned recent search - never from
    // the gesture pickers, which have no query to offer.
    SEARCH_QUERY;

    // Actions that mean nothing without a target.
    val needsPayload: Boolean
        get() = this == LAUNCH_APP || this == LAUNCH_SHORTCUT || this == JUMP_TO_PAGE ||
            this == SEARCH_QUERY || this == LAUNCH_INTENT

    companion object {
        fun fromName(name: String): GestureAction =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: NONE
    }
}

// The home-button re-press action. Stored as a GestureAction name in the
// homeButtonAction pref; "" derives from the legacy homeSearch boolean so
// pre-phase-4 configurations keep behaving identically.
object HomeButtonAction {

    // Curated subset that makes sense for a home re-press (no payload actions,
    // nothing that needs the accessibility service).
    val choices = listOf(
        GestureAction.NONE,
        GestureAction.OPEN_SEARCH,
        GestureAction.OPEN_DRAWER,
        GestureAction.EXPAND_NOTIFICATIONS,
        GestureAction.DEFAULT_PAGE
    )

    fun effective(homeButtonAction: String, homeSearch: Boolean): GestureAction =
        if (homeButtonAction.isEmpty()) {
            if (homeSearch) GestureAction.OPEN_SEARCH else GestureAction.NONE
        } else {
            GestureAction.fromName(homeButtonAction)
        }
}

// A bound action plus its target, if any. Payload is "pkg/cls@userSerial" for
// LAUNCH_APP, or "pkg|shortcutId@userSerial" for LAUNCH_SHORTCUT.
data class GestureBinding(
    val action: GestureAction,
    val payload: String? = null,
    val label: String? = null
)

object GestureDefaults {
    val bindings: Map<Gesture, GestureBinding> = mapOf(
        Gesture.SWIPE_UP to GestureBinding(GestureAction.OPEN_DRAWER),
        Gesture.SWIPE_DOWN to GestureBinding(GestureAction.EXPAND_NOTIFICATIONS),
        Gesture.DOUBLE_TAP to GestureBinding(GestureAction.NONE),
        Gesture.PINCH_IN to GestureBinding(GestureAction.NONE),
        Gesture.PINCH_OUT to GestureBinding(GestureAction.NONE),
        Gesture.TWO_FINGER_SWIPE_UP to GestureBinding(GestureAction.NONE),
        Gesture.TWO_FINGER_SWIPE_DOWN to GestureBinding(GestureAction.NONE),
        Gesture.SWIPE_LEFT to GestureBinding(GestureAction.NONE),
        Gesture.SWIPE_RIGHT to GestureBinding(GestureAction.NONE),
        Gesture.DOUBLE_TAP_SWIPE_UP to GestureBinding(GestureAction.NONE),
        Gesture.DOUBLE_TAP_SWIPE_DOWN to GestureBinding(GestureAction.NONE),
        Gesture.TWO_FINGER_ROTATE_CW to GestureBinding(GestureAction.NONE),
        Gesture.TWO_FINGER_ROTATE_CCW to GestureBinding(GestureAction.NONE)
    )

    // Persisted as "GESTURE:ACTION" or "GESTURE:ACTION:payload:label" so the
    // whole map lives in one preference entry. Two-segment entries written
    // before payloads existed still decode; a payload-needing action without
    // one degrades to NONE.
    fun decode(stored: Set<String>): Map<Gesture, GestureBinding> {
        val parsed = stored.mapNotNull { entry ->
            val parts = entry.split(':', limit = 4)
            if (parts.size < 2) return@mapNotNull null
            val gesture = Gesture.fromName(parts[0]) ?: return@mapNotNull null
            val action = GestureAction.fromName(parts[1])
            val payload = parts.getOrNull(2)?.takeIf { it.isNotEmpty() }?.let(::decodeField)
            val label = parts.getOrNull(3)?.takeIf { it.isNotEmpty() }?.let(::decodeField)
            if (action.needsPayload && payload == null) {
                gesture to GestureBinding(GestureAction.NONE)
            } else {
                gesture to GestureBinding(action, payload, label)
            }
        }.toMap()
        return bindings + parsed
    }

    // The launcher actions a gesture's picker offers. Launching an app, a
    // shortcut or an intent come from the picker's other tabs, and a saved
    // search only ever from a pinned search - listed here, it showed up as
    // a second "Open search" that saved as Nothing.
    val pickerActions: List<GestureAction> = GestureAction.entries.filterNot {
        it == GestureAction.NONE || it == GestureAction.LAUNCH_APP || it == GestureAction.LAUNCH_SHORTCUT ||
            it == GestureAction.LAUNCH_INTENT || it == GestureAction.SEARCH_QUERY
    }

    // Whether a swipe up on the home screen pulls the drawer in, the gesture
    // the Open App Drawer options (the swipe indicator, the haptic tick)
    // belong to.
    fun swipeUpOpensDrawer(stored: Set<String>): Boolean =
        decode(stored)[Gesture.SWIPE_UP]?.action == GestureAction.OPEN_DRAWER

    fun encode(gesture: Gesture, binding: GestureBinding): String = buildString {
        append(gesture.name)
        append(':')
        append(binding.action.name)
        if (binding.payload != null) {
            append(':')
            append(encodeField(binding.payload))
            append(':')
            append(encodeField(binding.label.orEmpty()))
        }
    }

    // ':' separates the fields, and an intent URI or a shortcut id can hold
    // one, which cut the payload short on the way back. Such a field is
    // written escaped behind a '~' marker; every other field is written as
    // it is, so older builds still read it, and entries written before the
    // marker read back exactly as stored (a literal "%3A" stays "%3A").
    private const val ESCAPED = '~'

    private fun encodeField(value: String): String =
        if (value.any { it == ':' || it == '%' } || value.startsWith(ESCAPED)) {
            ESCAPED + value.replace("%", "%25").replace(":", "%3A")
        } else {
            value
        }

    private fun decodeField(value: String): String =
        if (value.startsWith(ESCAPED)) value.substring(1).replace("%3A", ":").replace("%25", "%") else value
}
