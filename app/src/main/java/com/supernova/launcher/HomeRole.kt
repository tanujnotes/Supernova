package com.supernova.launcher

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings

// Becoming the default home app, across the supported range. Android 10 (Q)
// added RoleManager; on Android 9 there is no role API, so held-ness is read
// from whoever resolves the HOME intent and the ask is the system's home
// picker. Every call site goes through here so the version split lives once.
object HomeRole {

    private val homeIntent: Intent
        get() = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)

    // Whether asking is possible at all. Pre-Q always can: the home-settings
    // screen is a platform activity.
    fun isAvailable(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            context.getSystemService(RoleManager::class.java)
                ?.isRoleAvailable(RoleManager.ROLE_HOME) == true
        } else {
            true
        }

    fun isHeld(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            context.getSystemService(RoleManager::class.java)
                ?.isRoleHeld(RoleManager.ROLE_HOME) == true
        } else {
            defaultHomePackage(context) == context.packageName
        }

    // True when we could ask the user to switch to us.
    fun isNeeded(context: Context): Boolean = isAvailable(context) && !isHeld(context)

    // The ask: the role dialog on Q+, the system home picker on P. Null when
    // the platform offers neither.
    fun requestIntent(context: Context): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            context.getSystemService(RoleManager::class.java)
                ?.createRequestRoleIntent(RoleManager.ROLE_HOME)
        } else {
            Intent(Settings.ACTION_HOME_SETTINGS)
        }

    // Package of the current default home, or null. With several launchers
    // installed and no default chosen this resolves to the system resolver,
    // which is not our package - exactly the "not held" answer we want.
    fun defaultHomePackage(context: Context): String? = runCatching {
        context.packageManager
            .resolveActivity(homeIntent, PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo?.packageName
    }.getOrNull()

    fun defaultHomeLabel(context: Context): CharSequence? = runCatching {
        context.packageManager
            .resolveActivity(homeIntent, PackageManager.MATCH_DEFAULT_ONLY)
            ?.loadLabel(context.packageManager)
    }.getOrNull()

    // Where to send someone who wants to change an already-granted default.
    fun homeSettingsIntent(): Intent = Intent(Settings.ACTION_HOME_SETTINGS)
}
