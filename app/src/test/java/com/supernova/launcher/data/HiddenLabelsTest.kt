package com.supernova.launcher.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class HiddenLabelsTest {

    private val model = LauncherModel(
        version = CURRENT_MODEL_VERSION,
        items = listOf(
            LauncherItem(id = "app", itemType = ItemType.APP, packageName = "com.a", className = "com.a.Main"),
            LauncherItem(id = "folder", itemType = ItemType.FOLDER, label = "Games"),
            // Hidden label only: the edit goes entirely.
            LauncherItem(
                id = "hiddenOnly", itemType = ItemType.SHORTCUT, label = "Call Mum", shortcutId = "c7",
                packageName = "com.d", className = "com.d.Main",
                shortcutEdit = AppOverride(hideLabel = true)
            ),
            // Hidden label and a picked icon: the icon stays.
            LauncherItem(
                id = "hiddenWithIcon", itemType = ItemType.SHORTCUT, label = "Map", shortcutId = "m",
                packageName = "com.e", className = "com.e.Main", iconBase64 = "OWN",
                shortcutEdit = AppOverride(hideLabel = true, iconBase64 = "PICKED", iconShape = "CIRCLE")
            ),
            LauncherItem(
                id = "shown", itemType = ItemType.SHORTCUT, label = "Call", shortcutId = "c",
                packageName = "com.f", className = "com.f.Main",
                shortcutEdit = AppOverride(iconVariant = "THEMED")
            )
        ),
        appOverrides = mapOf(
            "0/com.a/com.a.Main" to AppOverride(hideLabel = true),
            "0/com.b/com.b.Main" to AppOverride(customLabel = "Bee", hideLabel = true, iconBase64 = "CCCC"),
            "0/com.c/com.c.Main" to AppOverride(iconShape = "CIRCLE")
        )
    )

    @Test
    fun countsAppsAndShortcutsWithAHiddenLabel() {
        assertEquals(HiddenLabels.Count(apps = 2, shortcuts = 2), HiddenLabels.count(model))
    }

    @Test
    fun resetShowsAppLabelsAndKeepsNamesAndIcons() {
        val reset = HiddenLabels.reset(model)

        assertEquals(HiddenLabels.Count(0, 0), HiddenLabels.count(reset))
        assertFalse("0/com.a/com.a.Main" in reset.appOverrides)
        assertEquals(AppOverride(customLabel = "Bee", iconBase64 = "CCCC"), reset.appOverrides["0/com.b/com.b.Main"])
        assertEquals(AppOverride(iconShape = "CIRCLE"), reset.appOverrides["0/com.c/com.c.Main"])
    }

    @Test
    fun resetShowsShortcutLabelsAndKeepsTheirIcons() {
        val items = HiddenLabels.reset(model).items.associateBy { it.id }

        assertNull(items.getValue("hiddenOnly").shortcutEdit)
        assertEquals("Call Mum", items.getValue("hiddenOnly").label)
        val withIcon = items.getValue("hiddenWithIcon")
        assertEquals(AppOverride(iconBase64 = "PICKED", iconShape = "CIRCLE"), withIcon.shortcutEdit)
        assertEquals("OWN", withIcon.iconBase64)
        assertEquals(model.items.first { it.id == "shown" }, items.getValue("shown"))
        assertEquals(model.items.first { it.id == "folder" }, items.getValue("folder"))
    }

    @Test
    fun resetOfAModelWithoutHiddenLabelsChangesNothing() {
        val plain = HiddenLabels.reset(model)
        assertEquals(plain, HiddenLabels.reset(plain))
    }
}
