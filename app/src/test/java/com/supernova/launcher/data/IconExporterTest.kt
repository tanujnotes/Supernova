package com.supernova.launcher.data

import org.junit.Assert.assertEquals
import org.junit.Test

class IconExporterTest {

    private fun name(label: String, pkg: String = "com.example.app", taken: MutableSet<String> = mutableSetOf()) =
        IconExporter.drawableName(label, pkg, taken)

    @Test
    fun labelsBecomeResourceNames() {
        assertEquals("google_maps", name("Google Maps"))
        assertEquals("brave", name("Brave!"))
        assertEquals("digital_wellbeing_parental_controls", name("Digital Wellbeing & parental controls"))
    }

    @Test
    fun namesStartWithALetter() {
        assertEquals("app_1password", name("1Password"))
        assertEquals("app_2048", name("2048"))
    }

    @Test
    fun javaKeywordsAreNeverNames() {
        assertEquals("app_switch", name("Switch"))
        assertEquals("app_new", name("New"))
        assertEquals("app_true", name("TRUE"))
        // Only a whole name is reserved.
        assertEquals("switch_access", name("Switch Access"))
    }

    @Test
    fun labelsWithoutLatinLettersUseThePackage() {
        assertEquals("hotstar", name("हॉटस्टार", pkg = "in.startv.hotstar"))
        assertEquals("app_123", name("…", pkg = "com.example.123"))
    }

    @Test
    fun repeatsGetASuffix() {
        val taken = mutableSetOf<String>()
        assertEquals("calculator", name("Calculator", taken = taken))
        assertEquals("calculator_2", name("Calculator", taken = taken))
        assertEquals("calculator_3", name("calculator", taken = taken))
    }
}
