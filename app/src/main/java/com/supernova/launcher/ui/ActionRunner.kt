package com.supernova.launcher.ui

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.Intent
import android.os.Build
import android.speech.RecognizerIntent
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.Toast
import com.supernova.launcher.R
import com.supernova.launcher.SettingsActivity
import com.supernova.launcher.SupernovaAccessibilityService
import com.supernova.launcher.SupernovaDeviceAdmin
import com.supernova.launcher.data.LaunchTarget
import com.supernova.launcher.data.LaunchableApp
import com.supernova.launcher.data.LockMethod
import com.supernova.launcher.data.Pref
import com.supernova.launcher.gesture.Gesture
import com.supernova.launcher.gesture.GestureAction
import com.supernova.launcher.gesture.GestureBinding
import com.supernova.launcher.gesture.GestureDefaults
import com.supernova.launcher.runLogged

// What a bound action does. Home gestures, the home button pressed again,
// an action shortcut on the desktop, an icon's swipe and a feed card's
// target all resolve to a GestureBinding and land in run(); the launcher
// activity supplies the surfaces they act on.
class ActionRunner(
    private val activity: Activity,
    private val viewModel: LauncherViewModel,
    private val host: Host
) {
    interface Host {
        // Where a gesture's haptic tick is played.
        val root: View
        fun openDrawer()
        fun expandNotificationShade()
        fun showPage(page: Int)
        fun openSearch(query: String?)
        fun launchApp(app: LaunchableApp)
        fun launchShortcut(packageName: String, shortcutId: String, userSerial: Long)
        fun launchIntentUri(uri: String)
        fun toggleDock()
        // The dock's middle page, the one Home brings back.
        fun showMainDockPage()
        fun showDesktopPreviews()
        fun openFeed()
        // The private space card, grown out of anchor (the action's home
        // icon) or, with none, from the middle of the home screen.
        fun openPrivateSpace(anchor: View?)
    }

    fun runGesture(gesture: Gesture): Boolean {
        val prefs = viewModel.state.value.prefs
        val bound = GestureDefaults.decode(prefs.gestureBindings)[gesture] ?: GestureBinding(GestureAction.NONE)
        // The Expand notifications haptic: a tick as the swipe pulls the
        // shade down.
        val pullsShade = gesture == Gesture.SWIPE_DOWN && bound.action == GestureAction.EXPAND_NOTIFICATIONS
        if (pullsShade && prefs.swipeDownHaptic) {
            host.root.performHapticFeedback(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    HapticFeedbackConstants.GESTURE_START
                } else {
                    HapticFeedbackConstants.VIRTUAL_KEY
                }
            )
        }
        return run(bound)
    }

    // The single action dispatcher. True when the action was taken up, so a
    // gesture bound to nothing falls through to whatever else wants it.
    // anchor: the home icon that ran it, for the actions that open over it.
    fun run(bound: GestureBinding, anchor: View? = null): Boolean {
        return when (bound.action) {
            GestureAction.NONE -> false
            GestureAction.OPEN_DRAWER -> {
                host.openDrawer()
                true
            }
            GestureAction.EXPAND_NOTIFICATIONS -> {
                host.expandNotificationShade()
                true
            }
            GestureAction.OPEN_SETTINGS -> {
                activity.startActivity(Intent(activity, SettingsActivity::class.java))
                true
            }
            GestureAction.LOCK_SCREEN -> lockScreen()
            GestureAction.DEFAULT_PAGE -> {
                val model = viewModel.state.value.model
                host.showPage(model.defaultPage.coerceIn(0, model.pageCount - 1))
                host.showMainDockPage()
                true
            }
            GestureAction.OPEN_SEARCH -> {
                host.openSearch(null)
                true
            }
            GestureAction.SEARCH_QUERY -> {
                val query = bound.payload
                if (query.isNullOrBlank()) false else { host.openSearch(query); true }
            }
            GestureAction.RECENTS -> globalAction(SupernovaAccessibilityService.showRecents())
            GestureAction.QUICK_SETTINGS -> globalAction(SupernovaAccessibilityService.showQuickSettings())
            GestureAction.ASSISTANT -> {
                runLogged("open assistant") { activity.startActivity(Intent(Intent.ACTION_ASSIST)) }
                    .onFailure { Toast.makeText(activity, R.string.assistant_unavailable, Toast.LENGTH_SHORT).show() }
                true
            }
            GestureAction.LAUNCH_APP -> {
                launchApp(bound.payload)
                true
            }
            GestureAction.LAUNCH_SHORTCUT -> {
                launchShortcut(bound.payload)
                true
            }
            GestureAction.LAUNCH_INTENT -> {
                bound.payload?.let(host::launchIntentUri)
                bound.payload != null
            }
            GestureAction.TOGGLE_STATUS_BAR -> {
                viewModel.set(Pref.hideStatusBar, !viewModel.state.value.prefs.hideStatusBar)
                true
            }
            GestureAction.TOGGLE_DOCK -> {
                host.toggleDock()
                true
            }
            GestureAction.JUMP_TO_PAGE -> {
                bound.payload?.toIntOrNull()?.let { page ->
                    host.showPage(page.coerceIn(0, viewModel.state.value.model.pageCount - 1))
                }
                true
            }
            GestureAction.DESKTOP_PREVIEWS -> {
                host.showDesktopPreviews()
                true
            }
            GestureAction.OPEN_FEED -> {
                if (viewModel.state.value.prefs.feedEnabled) {
                    host.openFeed()
                } else {
                    Toast.makeText(activity, R.string.feed_disabled_hint, Toast.LENGTH_SHORT).show()
                }
                true
            }
            GestureAction.VOICE_SEARCH -> {
                startVoiceSearch()
                true
            }
            GestureAction.PRIVATE_SPACE -> {
                host.openPrivateSpace(anchor)
                true
            }
        }
    }

    // Payload is "pkg/cls@userSerial".
    fun launchApp(payload: String?) {
        val (component, serial) = LaunchTarget.split(payload ?: return)
        val pkg = component.substringBefore('/')
        val cls = component.substringAfter('/', "")
        if (pkg.isEmpty() || cls.isEmpty()) return
        host.launchApp(LaunchableApp(pkg, cls, "", serial))
    }

    // Payload is "pkg|shortcutId@userSerial".
    fun launchShortcut(payload: String?) {
        val (body, serial) = LaunchTarget.split(payload ?: return)
        val pkg = body.substringBefore('|')
        val shortcutId = body.substringAfter('|', "")
        if (pkg.isEmpty() || shortcutId.isEmpty()) return
        host.launchShortcut(pkg, shortcutId, serial)
    }

    // Fire-and-forget voice search: web voice search, else the voice command
    // assistant, else a toast. Blind-start each so a missing app can't crash.
    private fun startVoiceSearch() {
        val intents = listOf(
            Intent(RecognizerIntent.ACTION_WEB_SEARCH),
            Intent(Intent.ACTION_VOICE_COMMAND),
            Intent(Intent.ACTION_ASSIST)
        )
        for (intent in intents) {
            val ok = runCatching {
                activity.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.isSuccess
            if (ok) return
        }
        Toast.makeText(activity, R.string.voice_search_unavailable, Toast.LENGTH_SHORT).show()
    }

    // Recents and quick settings both need the accessibility service, same as
    // lock; say why and offer the way there, rather than fail silently or
    // throw the user out of the home screen.
    private fun globalAction(performed: Boolean): Boolean {
        if (!performed) PermissionPrompts.explainAccessibility(activity)
        return true
    }

    // Screen lock needs an accessibility service on API 28+; without it we
    // tell the user rather than silently doing nothing.
    private fun lockScreen(): Boolean {
        if (viewModel.state.value.prefs.lockMethod == LockMethod.DEVICE_ADMIN) {
            // Selected but not yet granted: send the user to enable it.
            if (!SupernovaDeviceAdmin.lock(activity)) promptDeviceAdmin()
            return true
        }
        if (!SupernovaAccessibilityService.lockScreen()) PermissionPrompts.explainAccessibility(activity)
        return true
    }

    private fun promptDeviceAdmin() {
        val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
            .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, SupernovaDeviceAdmin.component(activity))
            .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, activity.getString(R.string.lock_admin_explanation))
        runLogged("ask for device admin") { activity.startActivity(intent) }
    }
}
