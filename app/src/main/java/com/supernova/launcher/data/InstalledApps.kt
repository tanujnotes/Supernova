package com.supernova.launcher.data

import android.content.pm.ApplicationInfo
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.os.Build
import android.os.UserHandle
import android.os.UserManager

// Whether an app the home screen still holds is surely gone for its user:
// no longer on the phone, or uninstalled for this user while another user
// keeps it. An app mid-update, disabled, hidden, archived (Android 15+), or
// in a profile that is paused, locked or out of reach (Supernova not the
// default home) is not: its items stay for when it comes back.
internal object InstalledApps {
    private const val LOOKUP_FLAGS = PackageManager.MATCH_UNINSTALLED_PACKAGES or
        PackageManager.MATCH_DISABLED_COMPONENTS or PackageManager.MATCH_DISABLED_UNTIL_USED_COMPONENTS

    fun isGone(launcherApps: LauncherApps, userManager: UserManager, packageName: String, user: UserHandle): Boolean {
        if (!reachable(launcherApps, userManager, user)) return false
        val info = runCatching { launcherApps.getApplicationInfo(packageName, LOOKUP_FLAGS, user) }
        return info.exceptionOrNull() is PackageManager.NameNotFoundException ||
            info.getOrNull()?.let(::uninstalledForUser) == true
    }

    private fun reachable(launcherApps: LauncherApps, userManager: UserManager, user: UserHandle): Boolean =
        runCatching {
            user in launcherApps.profiles && !userManager.isQuietModeEnabled(user) && userManager.isUserUnlocked(user)
        }.getOrDefault(false)

    private fun uninstalledForUser(info: ApplicationInfo): Boolean {
        val archived = Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM && info.isArchived
        return info.flags and ApplicationInfo.FLAG_INSTALLED == 0 && !archived
    }
}
