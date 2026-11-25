package com.supernova.launcher.data

import android.content.Context
import android.content.pm.LauncherApps
import android.os.UserManager
import com.supernova.launcher.runLogged

// Every app shortcut on the home screen is pinned to this launcher (see
// LauncherActivity.pinShortcut). A restored layout's pins are gone when the
// layout comes from another install - Android drops a launcher's pins with
// it - so they are asked for again. Only a shortcut the app still offers
// (dynamic or manifest) can be pinned again; a pin-only one (an app's "add
// to home screen") went with the old install and only its app can make it
// again.
internal object ShortcutPins {

    data class Owner(val packageName: String, val userSerial: Long)

    // The app shortcuts a layout holds, by app and user. Launcher actions
    // and intent items (intentUri) are no app shortcuts.
    fun wanted(items: List<LauncherItem>): Map<Owner, Set<String>> =
        items.asSequence()
            .filter { it.itemType == ItemType.SHORTCUT && it.intentUri == null }
            .mapNotNull { item ->
                val pkg = item.packageName ?: return@mapNotNull null
                val id = item.shortcutId ?: return@mapNotNull null
                Owner(pkg, item.userSerial) to id
            }
            .groupBy({ it.first }, { it.second })
            .mapValues { it.value.toSet() }

    // pinShortcuts sets an app's whole pinned list, so it is the list already
    // pinned plus the missing ones; ids the app no longer has are skipped by
    // the system. Needs the home role, so it is a no-op until then.
    fun repin(context: Context, items: List<LauncherItem>) {
        val launcherApps = context.getSystemService(LauncherApps::class.java) ?: return
        val users = context.getSystemService(UserManager::class.java)
        val isHome = runCatching { launcherApps.hasShortcutHostPermission() }.getOrDefault(false)
        if (users == null || !isHome) return
        wanted(items).forEach { (owner, ids) ->
            val user = users.getUserForSerialNumber(owner.userSerial) ?: return@forEach
            runLogged("re-pin shortcuts") {
                val query = LauncherApps.ShortcutQuery()
                    .setPackage(owner.packageName)
                    .setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED)
                val pinned = launcherApps.getShortcuts(query, user).orEmpty().map { it.id }
                val missing = ids - pinned.toSet()
                if (missing.isNotEmpty()) launcherApps.pinShortcuts(owner.packageName, pinned + missing, user)
            }
        }
    }
}
