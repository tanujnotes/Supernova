package com.supernova.launcher.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ShortcutPinsTest {

    private fun shortcut(id: String, pkg: String?, shortcutId: String?, serial: Long = 0L, intentUri: String? = null) =
        LauncherItem(
            id = id, itemType = ItemType.SHORTCUT, packageName = pkg, shortcutId = shortcutId,
            userSerial = serial, intentUri = intentUri, container = ContainerType.FOLDER
        )

    @Test
    fun groupsAppShortcutsByAppAndUser() {
        val items = listOf(
            shortcut("a", "com.a", "one"),
            shortcut("b", "com.a", "two"),
            shortcut("c", "com.a", "one", serial = 10L),
            shortcut("d", "com.b", "x")
        )

        assertEquals(
            mapOf(
                ShortcutPins.Owner("com.a", 0L) to setOf("one", "two"),
                ShortcutPins.Owner("com.a", 10L) to setOf("one"),
                ShortcutPins.Owner("com.b", 0L) to setOf("x")
            ),
            ShortcutPins.wanted(items)
        )
    }

    @Test
    fun leavesOutAppsLauncherActionsAndIntentItems() {
        val items = listOf(
            LauncherItem(id = "app", itemType = ItemType.APP, packageName = "com.a", className = "com.a.Main"),
            shortcut("action", null, null, intentUri = "supernova://action/drawer"),
            shortcut("intent", "com.a", null, intentUri = "intent:#Intent;component=com.a/.Main;end"),
            shortcut("noId", "com.a", null)
        )

        assertEquals(emptyMap<ShortcutPins.Owner, Set<String>>(), ShortcutPins.wanted(items))
    }
}
