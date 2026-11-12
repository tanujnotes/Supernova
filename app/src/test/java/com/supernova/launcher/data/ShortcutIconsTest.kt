package com.supernova.launcher.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShortcutIconsTest {

    @Test
    fun aHiddenLabelAloneLeavesTheIconAsItWas() {
        assertFalse(ShortcutIcons.changesIcon(AppOverride(hideLabel = true)))
        assertFalse(ShortcutIcons.changesIcon(AppOverride()))
    }

    @Test
    fun aPickedIconShapeOrStyleRedrawsIt() {
        assertTrue(ShortcutIcons.changesIcon(AppOverride(iconBase64 = "PHOTO")))
        assertTrue(ShortcutIcons.changesIcon(AppOverride(iconPackPackage = "com.pack", iconDrawableName = "map")))
        assertTrue(ShortcutIcons.changesIcon(AppOverride(iconShape = "CIRCLE")))
        assertTrue(ShortcutIcons.changesIcon(AppOverride(iconVariant = "THEMED")))
    }

    @Test
    fun onlyAPhotoOrPackIconCountsAsPicked() {
        assertTrue(ShortcutIcons.hasPickedIcon(AppOverride(iconBase64 = "PHOTO")))
        assertTrue(ShortcutIcons.hasPickedIcon(AppOverride(iconPackPackage = "com.pack", iconDrawableName = "map")))
        assertFalse(ShortcutIcons.hasPickedIcon(AppOverride(iconShape = "CIRCLE", iconVariant = "ORIGINAL")))
    }
}
