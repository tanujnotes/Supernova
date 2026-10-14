package com.supernova.launcher.ui

import androidx.annotation.DrawableRes
import com.supernova.launcher.R
import com.supernova.launcher.gesture.GestureAction

// Drawable tile for each launcher action placed on the home screen. The whens
// are exhaustive so a new GestureAction fails compilation here until it gets a
// tile, mirroring EnumLabels.
object ActionIcons {

    @DrawableRes
    fun of(action: GestureAction): Int = when (action) {
        GestureAction.OPEN_DRAWER -> R.drawable.ic_action_drawer
        GestureAction.OPEN_SEARCH -> R.drawable.ic_action_search
        GestureAction.EXPAND_NOTIFICATIONS -> R.drawable.ic_action_notifications
        GestureAction.QUICK_SETTINGS -> R.drawable.ic_action_quick_settings
        GestureAction.RECENTS -> R.drawable.ic_action_recents
        GestureAction.LOCK_SCREEN -> R.drawable.ic_action_lock
        GestureAction.OPEN_SETTINGS -> R.drawable.ic_action_settings
        GestureAction.DEFAULT_PAGE -> R.drawable.ic_action_home
        GestureAction.TOGGLE_DOCK -> R.drawable.ic_action_dock
        GestureAction.TOGGLE_STATUS_BAR -> R.drawable.ic_action_status_bar
        GestureAction.DESKTOP_PREVIEWS -> R.drawable.ic_action_previews
        GestureAction.OPEN_FEED -> R.drawable.ic_action_feed
        GestureAction.ASSISTANT -> R.drawable.ic_action_assistant
        GestureAction.VOICE_SEARCH -> R.drawable.ic_action_voice
        GestureAction.SEARCH_QUERY -> R.drawable.ic_action_search
        // Not offered as standalone home icons (they need a specific gesture
        // context or a payload target), but map to something sensible.
        GestureAction.LAUNCH_APP,
        GestureAction.LAUNCH_SHORTCUT,
        GestureAction.LAUNCH_INTENT -> R.drawable.ic_action_drawer
        GestureAction.JUMP_TO_PAGE -> R.drawable.ic_action_home
        GestureAction.NONE -> R.drawable.ic_action_drawer
    }
}
