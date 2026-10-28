package com.supernova.launcher

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import com.supernova.launcher.ui.theme.ThemeEngine
import com.supernova.launcher.ui.theme.AccentColor

// The "Apply" button inside an icon pack's own app. Every pack dashboard ships
// one per launcher it knows about, firing an action at that launcher with the
// pack named in an extra; ADW, Apex and others each defined their own, and pack
// templates carry all of them, so this answers to the lot rather than asking
// pack authors for Supernova-specific code.
//
// The pack is not always in an extra: a dashboard that only knows how to fire
// a bare action still identifies itself as the caller, so the calling package
// is the last resort - and, since a pack applies itself, usually the right
// answer.
//
// Exported, so anything installed can start it. Two things keep that safe: the
// named package has to actually be an installed icon pack (it must advertise
// one of the theme actions), and the user confirms the swap in a dialog. An
// app cannot quietly re-theme someone's home screen by firing an intent.
class ApplyIconPackActivity : AppCompatActivity() {

    private val preferences get() = appGraph.preferences
    private val iconPacks get() = appGraph.iconPacks

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The accent overlay is what sets colorAccent, which the dialog's
        // colours resolve through: without it MaterialAlertDialogBuilder
        // found no theme colours and this screen crashed on open.
        ThemeEngine.applyAccent(this, AccentColor.fromName(runBlocking { preferences.flow.first() }.accentColor))

        val requested = requestedPack()
        if (requested == null) {
            Toast.makeText(this, R.string.icon_pack_apply_unknown, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        // An installed pack is one that advertises a theme action; a package
        // that doesn't is either not a pack or not installed, and either way
        // has no icons to apply.
        if (iconPacks.availablePacks().none { it.packageName == requested }) {
            Toast.makeText(this, R.string.icon_pack_apply_unknown, Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val label = iconPacks.packLabel(requested)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.icon_pack)
            .setMessage(getString(R.string.icon_pack_apply_confirm, label))
            .setPositiveButton(R.string.icon_pack_apply) { _, _ -> apply(requested, label) }
            .setNegativeButton(R.string.cancel) { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }

    private fun apply(packPackage: String, label: String) {
        lifecycleScope.launch {
            preferences.setIconPack(packPackage)
            Toast.makeText(
                this@ApplyIconPackActivity,
                getString(R.string.icon_pack_applied, label),
                Toast.LENGTH_SHORT
            ).show()
            // Straight to the home screen: the point of the button the user
            // pressed is to see the new icons, not to land back in a dashboard.
            startActivity(
                Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_HOME)
                    .setPackage(packageName)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            )
            finish()
        }
    }

    // The pack this intent is asking for, from the extras the various pack
    // templates use, then the intent's own data, then whoever started us.
    private fun requestedPack(): String? {
        PACK_EXTRAS.forEach { extra ->
            intent.getStringExtra(extra)?.takeIf { it.isNotBlank() }?.let { return it }
        }
        // ADW names the theme by package in the data uri on some templates.
        intent.data?.schemeSpecificPart?.takeIf { it.isNotBlank() }?.let { return it }
        callingActivity?.packageName?.let { return it }
        // referrer is set for a startActivity from a normal app even when the
        // caller didn't ask for a result, which callingActivity requires.
        referrer?.takeIf { it.scheme == "android-app" }?.host?.takeIf { it.isNotBlank() }
            ?.let { return it }
        return null
    }

    companion object {
        // Extras the common pack dashboards put the package in. Supernova's own
        // comes first; the rest are what the templates already send.
        private val PACK_EXTRAS = listOf(
            "com.supernova.launcher.extra.ICON_THEME_PACKAGE",
            "com.anddoes.launcher.THEME_PACKAGE_NAME",
            "org.adw.launcher.theme.NAME",
            "icon_pack_package",
            "packageName"
        )
    }
}
