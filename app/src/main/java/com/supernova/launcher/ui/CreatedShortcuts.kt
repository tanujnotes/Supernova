package com.supernova.launcher.ui

import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.os.UserManager

// What an app's own shortcut screen (ACTION_CREATE_SHORTCUT) answered, as a
// launch target for a gesture or an icon swipe.
sealed class CreatedShortcut {
    abstract val label: String

    // A pinned shortcut, "pkg|shortcutId@serial" (LaunchTarget).
    data class Pinned(val target: String, override val label: String) : CreatedShortcut()

    // An older app's intent, as its URI.
    data class Legacy(val intentUri: String, override val label: String) : CreatedShortcut()
}

object CreatedShortcuts {

    // Newer apps answer with a pin request, which pins the shortcut to us
    // once accepted, so it launches through startShortcut; older ones hand
    // back an intent and a name.
    fun read(context: Context, data: Intent): CreatedShortcut? {
        val launcherApps = context.getSystemService(LauncherApps::class.java)
        val pin = runCatching { launcherApps?.getPinItemRequest(data) }.getOrNull()
            ?.takeIf { it.isValid && it.requestType == LauncherApps.PinItemRequest.REQUEST_TYPE_SHORTCUT }
        return if (pin != null) pinned(context, pin) else legacy(data)
    }

    private fun pinned(context: Context, pin: LauncherApps.PinItemRequest): CreatedShortcut? {
        val shortcut = pin.shortcutInfo?.takeIf { runCatching { pin.accept() }.getOrDefault(false) } ?: return null
        val serial = context.getSystemService(UserManager::class.java)
            ?.getSerialNumberForUser(shortcut.userHandle) ?: 0L
        val label = (shortcut.shortLabel ?: shortcut.longLabel ?: shortcut.`package`).toString()
        return CreatedShortcut.Pinned("${shortcut.`package`}|${shortcut.id}@$serial", label)
    }

    private fun legacy(data: Intent): CreatedShortcut? {
        @Suppress("DEPRECATION")
        val intent = data.getParcelableExtra<Intent>(Intent.EXTRA_SHORTCUT_INTENT) ?: return null
        val label = data.getStringExtra(Intent.EXTRA_SHORTCUT_NAME).orEmpty()
        return CreatedShortcut.Legacy(intent.toUri(Intent.URI_INTENT_SCHEME), label)
    }

    // Whether a stored icon-swipe target is an intent URI (see Legacy).
    fun isIntentUri(target: String): Boolean = target.startsWith(INTENT_SCHEME)

    private const val INTENT_SCHEME = "intent:"
}
