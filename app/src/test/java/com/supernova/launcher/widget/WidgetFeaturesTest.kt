package com.supernova.launcher.widget

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// When a widget's configure screen opens on add, and when its settings are
// offered again once placed.
class WidgetFeaturesTest {

    private val reconfigurable = 0x1
    private val hideFromPicker = 0x2
    private val optional = 0x4

    @Test
    fun noConfigureScreenNeedsNoSetup() {
        assertFalse(WidgetFeatures.needsSetup(hasConfigure = false, features = 0))
        assertFalse(WidgetFeatures.reconfigurable(hasConfigure = false, features = reconfigurable))
    }

    @Test
    fun plainConfigureScreenIsRequired() {
        assertTrue(WidgetFeatures.needsSetup(hasConfigure = true, features = 0))
        assertTrue(WidgetFeatures.needsSetup(hasConfigure = true, features = reconfigurable))
    }

    @Test
    fun optionalSetupCountsOnlyWhenReconfigurable() {
        assertFalse(WidgetFeatures.needsSetup(hasConfigure = true, features = reconfigurable or optional))
        assertFalse(
            WidgetFeatures.needsSetup(hasConfigure = true, features = reconfigurable or optional or hideFromPicker)
        )
        // Optional alone leaves no way back to the settings, so setup still runs.
        assertTrue(WidgetFeatures.needsSetup(hasConfigure = true, features = optional))
    }

    @Test
    fun settingsAreOfferedAgainForReconfigurableWidgets() {
        assertTrue(WidgetFeatures.reconfigurable(hasConfigure = true, features = reconfigurable or optional))
        assertFalse(WidgetFeatures.reconfigurable(hasConfigure = true, features = optional))
    }
}
