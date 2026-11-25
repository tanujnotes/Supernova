package com.supernova.launcher.search

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShortcutShownTest {
    private val apps = setOf("0/com.google.android.youtube")
    private val activities = setOf("0/com.google.android.youtube/.HomeActivity")

    @Test
    fun listedActivityIsShown() {
        assertTrue(ShortcutSearchProvider.isShown(0, "com.google.android.youtube", ".HomeActivity", apps, activities))
    }

    @Test
    fun unusedAliasIsNotShown() {
        assertFalse(ShortcutSearchProvider.isShown(0, "com.google.android.youtube", ".Icon2Activity", apps, activities))
    }

    @Test
    fun noActivityFallsBackToThePackage() {
        assertTrue(ShortcutSearchProvider.isShown(0, "com.google.android.youtube", null, apps, activities))
        assertFalse(ShortcutSearchProvider.isShown(0, "com.example.hidden", null, apps, activities))
    }

    @Test
    fun otherProfileIsNotShown() {
        assertFalse(ShortcutSearchProvider.isShown(10, "com.google.android.youtube", ".HomeActivity", apps, activities))
    }

    @Test
    fun otherSmsAppsChatsAreHidden() {
        val others = setOf("org.fossify.messages")
        assertTrue(ShortcutSearchProvider.hidesChat("org.fossify.messages", manifest = false, others))
        assertFalse(ShortcutSearchProvider.hidesChat("org.fossify.messages", manifest = true, others))
        assertFalse(ShortcutSearchProvider.hidesChat("com.google.android.apps.messaging", manifest = false, others))
    }
}
