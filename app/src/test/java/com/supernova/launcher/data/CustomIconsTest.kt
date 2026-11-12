package com.supernova.launcher.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class CustomIconsTest {

    private val model = LauncherModel(
        version = CURRENT_MODEL_VERSION,
        items = listOf(
            LauncherItem(id = "app", itemType = ItemType.APP, packageName = "com.a", className = "com.a.Main"),
            LauncherItem(
                id = "packFolder", itemType = ItemType.FOLDER, label = "Games",
                customIconPack = "com.pack", customIconName = "folder_games"
            ),
            LauncherItem(id = "photoFolder", itemType = ItemType.FOLDER, label = "Work", iconBase64 = "AAAA"),
            LauncherItem(id = "plainFolder", itemType = ItemType.FOLDER, label = "Tools"),
            // An activity shortcut's snapshot is its only icon.
            LauncherItem(
                id = "shortcut", itemType = ItemType.SHORTCUT, label = "Act",
                intentUri = "intent:#Intent;component=com.x/.Act;end", iconBase64 = "BBBB"
            ),
            LauncherItem(
                id = "action", itemType = ItemType.SHORTCUT, label = "Drawer",
                intentUri = "supernova://action/OPEN_DRAWER", customIconPack = "com.pack", customIconName = "drawer"
            ),
            // A pinned shortcut whose Edit picked an icon and hid its label.
            LauncherItem(
                id = "edited", itemType = ItemType.SHORTCUT, label = "Call Mum", shortcutId = "c7",
                packageName = "com.d", className = "com.d.Main", iconBase64 = "PHOTO",
                shortcutEdit = AppOverride(hideLabel = true, iconBase64 = "PICKED")
            ),
            LauncherItem(
                id = "iconOnly", itemType = ItemType.SHORTCUT, label = "Map", shortcutId = "m",
                packageName = "com.e", className = "com.e.Main",
                shortcutEdit = AppOverride(iconPackPackage = "com.pack", iconDrawableName = "map")
            ),
            // Only a shape and style picked: its own picture, drawn differently.
            LauncherItem(
                id = "styled", itemType = ItemType.SHORTCUT, label = "Call", shortcutId = "c",
                packageName = "com.f", className = "com.f.Main", iconBase64 = "OWN",
                shortcutEdit = AppOverride(iconShape = "CIRCLE", iconVariant = "THEMED")
            )
        ),
        appOverrides = mapOf(
            "0/com.a/com.a.Main" to AppOverride(iconPackPackage = "com.pack", iconDrawableName = "a"),
            "0/com.b/com.b.Main" to AppOverride(customLabel = "Bee", iconBase64 = "CCCC"),
            "0/com.c/com.c.Main" to AppOverride(iconShape = "CIRCLE", iconVariant = "THEMED"),
            "0/com.d/com.d.Main" to AppOverride(hideLabel = true)
        )
    )

    @Test
    fun countsAppsFoldersAndShortcutsWithTheirOwnIcon() {
        assertEquals(CustomIcons.Count(apps = 3, folders = 2, shortcuts = 4), CustomIcons.count(model))
    }

    @Test
    fun resetClearsIconsButKeepsNamesAndHiddenLabels() {
        val reset = CustomIcons.reset(model)

        assertEquals(CustomIcons.Count(0, 0, 0), CustomIcons.count(reset))
        // An override that held only an icon goes entirely.
        assertFalse("0/com.a/com.a.Main" in reset.appOverrides)
        assertFalse("0/com.c/com.c.Main" in reset.appOverrides)
        assertEquals(AppOverride(customLabel = "Bee"), reset.appOverrides["0/com.b/com.b.Main"])
        assertEquals(AppOverride(hideLabel = true), reset.appOverrides["0/com.d/com.d.Main"])
    }

    @Test
    fun resetLeavesShortcutIconsAndFolderNamesAlone() {
        val items = CustomIcons.reset(model).items.associateBy { it.id }

        val packFolder = items.getValue("packFolder")
        assertNull(packFolder.customIconPack)
        assertNull(packFolder.customIconName)
        assertEquals("Games", packFolder.label)
        assertNull(items.getValue("photoFolder").iconBase64)
        assertEquals("BBBB", items.getValue("shortcut").iconBase64)
        assertEquals(model.items.first { it.id == "app" }, items.getValue("app"))
    }

    @Test
    fun resetClearsAShortcutsPickedIconButKeepsItsPictureAndHiddenLabel() {
        val items = CustomIcons.reset(model).items.associateBy { it.id }

        val edited = items.getValue("edited")
        assertEquals(AppOverride(hideLabel = true), edited.shortcutEdit)
        assertEquals("PHOTO", edited.iconBase64)
        assertEquals("Call Mum", edited.label)
        assertNull(items.getValue("iconOnly").shortcutEdit)
        assertNull(items.getValue("styled").shortcutEdit)
        assertEquals("OWN", items.getValue("styled").iconBase64)
        assertNull(items.getValue("action").customIconPack)
    }

    @Test
    fun resetOfAModelWithoutCustomIconsChangesNothing() {
        val plain = CustomIcons.reset(model)
        assertEquals(plain, CustomIcons.reset(plain))
    }
}
