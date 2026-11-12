package com.supernova.launcher.widget

import android.appwidget.AppWidgetProviderInfo

// What a provider's widgetFeatures flags mean for adding and editing its
// widgets. The flags are read from the provider's XML on every release
// minSdk covers, but some named constants only exist from API 31.
object WidgetFeatures {
    private const val RECONFIGURABLE = 0x1
    private const val HIDE_FROM_PICKER = 0x2
    private const val CONFIGURATION_OPTIONAL = 0x4

    // A configure screen is skipped when the widget says setup is optional
    // (it must also be reconfigurable for that to count): the widget works
    // as placed, and a configure screen that fails or is backed out of
    // would otherwise take the widget with it.
    fun needsSetup(info: AppWidgetProviderInfo): Boolean =
        needsSetup(info.configure != null, info.widgetFeatures)

    fun needsSetup(hasConfigure: Boolean, features: Int): Boolean =
        hasConfigure && !(features and RECONFIGURABLE != 0 && features and CONFIGURATION_OPTIONAL != 0)

    // Placed widgets that offer their settings screen again.
    fun reconfigurable(info: AppWidgetProviderInfo): Boolean =
        reconfigurable(info.configure != null, info.widgetFeatures)

    fun reconfigurable(hasConfigure: Boolean, features: Int): Boolean =
        hasConfigure && features and RECONFIGURABLE != 0

    // Providers can ask to stay out of pickers (older variants kept only so
    // already-placed copies keep working).
    fun listable(info: AppWidgetProviderInfo): Boolean = info.widgetFeatures and HIDE_FROM_PICKER == 0
}
