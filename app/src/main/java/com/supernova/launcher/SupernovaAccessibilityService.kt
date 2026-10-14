package com.supernova.launcher

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

// Optional service: the only way for a third-party launcher to lock the
// screen on API 28+ without Device Admin. Nothing else depends on it, and
// gestures bound to lock fall back to prompting the user to enable it.
class SupernovaAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    companion object {
        @Volatile
        private var instance: SupernovaAccessibilityService? = null

        // Switched on in the system's accessibility settings; the service
        // may not be connected yet even so.
        fun isEnabled(context: android.content.Context): Boolean {
            val flat = android.provider.Settings.Secure.getString(
                context.contentResolver,
                android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ).orEmpty()
            return flat.split(':').any {
                android.content.ComponentName.unflattenFromString(it)?.packageName == context.packageName
            }
        }

        fun lockScreen(): Boolean = perform(GLOBAL_ACTION_LOCK_SCREEN)

        fun showRecents(): Boolean = perform(GLOBAL_ACTION_RECENTS)

        fun showQuickSettings(): Boolean = perform(GLOBAL_ACTION_QUICK_SETTINGS)

        private fun perform(action: Int): Boolean {
            val service = instance ?: return false
            return service.performGlobalAction(action)
        }
    }
}
