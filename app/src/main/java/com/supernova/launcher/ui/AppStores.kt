package com.supernova.launcher.ui

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.view.View
import com.supernova.launcher.data.LauncherPrefs
import com.supernova.launcher.runLogged

// The app stores on the device: every app that opens a market:// details
// link and can be launched (Play Store, F-Droid, Galaxy Store, Aurora and
// the like). The Play Store comes first, the rest by name.
object AppStores {

    class Store(val packageName: String, val label: CharSequence, val launch: Intent)

    private const val PLAY_STORE = "com.android.vending"

    fun installed(context: Context): List<Store> {
        val pm = context.packageManager
        val probe = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=${context.packageName}"))
        val packages = pm.queryIntentActivities(probe, PackageManager.MATCH_DEFAULT_ONLY)
            .map { it.activityInfo.packageName }
            .distinct()
        return packages.mapNotNull { pkg ->
            val launch = pm.getLaunchIntentForPackage(pkg) ?: return@mapNotNull null
            val label = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)) }.getOrNull() ?: pkg
            Store(pkg, label, launch)
        }.sortedWith(
            compareBy<Store> { it.packageName != PLAY_STORE }
                .thenBy(java.text.Collator.getInstance()) { it.label.toString() }
        )
    }

    // The store button: opens the only store installed, or a menu of them
    // when there are several. Without prefs to style the menu, the first.
    fun open(anchor: View, prefs: LauncherPrefs?) {
        val context = anchor.context
        val stores = installed(context)
        val direct = stores.singleOrNull() ?: stores.firstOrNull().takeIf { prefs == null }
        when {
            direct != null -> runLogged("open app store") { context.startActivity(direct.launch) }
            prefs != null && stores.isNotEmpty() ->
                ChoiceMenu.showActions(anchor, prefs, stores.map { it.label }) { index ->
                    runLogged("open app store") { context.startActivity(stores[index].launch) }
                }
        }
    }
}
