package com.supernova.launcher.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class ShortcutEditsTest {

    private val shortcut = LauncherItem(
        id = "s", itemType = ItemType.SHORTCUT, label = "Call Mum",
        packageName = "com.d", className = "com.d.Main", shortcutId = "contact-7", iconBase64 = "PHOTO"
    )

    @Test
    fun savesTheNameHiddenLabelAndIconOnTheItem() {
        val edit = AppOverride(hideLabel = true, iconPackPackage = "com.pack", iconDrawableName = "phone")

        val saved = ShortcutEdits.apply(shortcut, "s", "  Mum  ", edit)

        assertEquals("Mum", saved.label)
        assertEquals(edit, saved.shortcutEdit)
        // The shortcut's own picture stays, for when the icon goes back to Default.
        assertEquals("PHOTO", saved.iconBase64)
    }

    @Test
    fun aBlankNameKeepsTheOldOne() {
        assertEquals("Call Mum", ShortcutEdits.apply(shortcut, "s", "  ", AppOverride()).label)
    }

    @Test
    fun anEmptyEditIsStoredAsNone() {
        val edited = shortcut.copy(shortcutEdit = AppOverride(iconBase64 = "PICKED"))

        assertNull(ShortcutEdits.apply(edited, "s", "Call Mum", AppOverride()).shortcutEdit)
    }

    @Test
    fun theEditNeverHoldsAName() {
        val saved = ShortcutEdits.apply(shortcut, "s", "Mum", AppOverride(customLabel = "Other", hideLabel = true))

        assertEquals(AppOverride(hideLabel = true), saved.shortcutEdit)
        assertEquals("Mum", saved.label)
    }

    @Test
    fun otherItemsPassThrough() {
        val app = LauncherItem(id = "s", itemType = ItemType.APP, label = "App")
        val other = shortcut.copy(id = "t")

        assertSame(app, ShortcutEdits.apply(app, "s", "New", AppOverride(hideLabel = true)))
        assertSame(other, ShortcutEdits.apply(other, "s", "New", AppOverride(hideLabel = true)))
    }
}
