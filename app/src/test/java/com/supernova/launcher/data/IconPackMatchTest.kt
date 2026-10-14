package com.supernova.launcher.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IconPackMatchTest {

    private val byComponent = mapOf(
        "com.android.chrome/com.google.android.apps.chrome.Main" to "chrome",
        "com.whatsapp/com.whatsapp.Main" to "whatsapp"
    )
    private val byPackage = mapOf(
        "com.android.chrome" to "chrome",
        "com.whatsapp" to "whatsapp"
    )

    @Test
    fun exactComponentWins() {
        assertEquals(
            "chrome",
            IconPackRepository.drawableFor(
                byComponent, byPackage,
                "com.android.chrome", "com.google.android.apps.chrome.Main"
            )
        )
    }

    @Test
    fun renamedActivityStillThemesFromTheSamePackage() {
        // WhatsApp's launcher activity moved to HomeActivity years after most
        // packs were written; without the package fallback it renders unthemed.
        assertEquals(
            "whatsapp",
            IconPackRepository.drawableFor(
                byComponent, byPackage, "com.whatsapp", "com.whatsapp.HomeActivity"
            )
        )
    }

    @Test
    fun anUnknownPackageStaysUnthemed() {
        assertNull(
            IconPackRepository.drawableFor(
                byComponent, byPackage, "com.example.unknown", "com.example.unknown.Main"
            )
        )
    }

    @Test
    fun backIndexIsStableAndInRange() {
        val component = "com.android.chrome/com.google.android.apps.chrome.Main"
        val first = IconPackRepository.backIndex(component, 4)
        assertEquals(first, IconPackRepository.backIndex(component, 4))
        assertTrue(first in 0..3)
    }

    @Test
    fun backIndexNeverGoesNegativeOnANegativeHash() {
        // String.hashCode is signed; a raw modulo would index out of bounds.
        repeat(500) { i ->
            val index = IconPackRepository.backIndex("pkg$i/cls$i", 3)
            assertTrue("index $index out of range for pkg$i", index in 0..2)
        }
    }

    @Test
    fun oneBackIsAlwaysIndexZero() {
        assertEquals(0, IconPackRepository.backIndex("anything/at.All", 1))
        assertEquals(0, IconPackRepository.backIndex("anything/at.All", 0))
    }

    @Test
    fun calendarNameStillAppendsTheDay() {
        assertNotNull(IconPackRepository.calendarDrawableName("cal_", 3))
        assertEquals("cal_3", IconPackRepository.calendarDrawableName("cal_", 3))
    }
}
