package com.supernova.launcher.data

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

// Names sort in the language's alphabet: an accented first letter files with
// its plain letter instead of after Z, and case makes no difference.
class LabelOrderTest {

    private val names = listOf("Zoom", "Öffi", "maps", "Ärzte", "Uber", "Übersetzer", "apple")

    @Test
    fun umlautsFileWithTheirLettersInEnglish() {
        assertEquals(
            listOf("apple", "Ärzte", "maps", "Öffi", "Uber", "Übersetzer", "Zoom"),
            LabelOrder.sorted(names, locale = Locale.ENGLISH) { it }
        )
    }

    @Test
    fun umlautsFileWithTheirLettersInGerman() {
        assertEquals(
            listOf("apple", "Ärzte", "maps", "Öffi", "Uber", "Übersetzer", "Zoom"),
            LabelOrder.sorted(names, locale = Locale.GERMAN) { it }
        )
    }

    @Test
    fun descendingIsTheReverse() {
        assertEquals(
            listOf("Zoom", "Übersetzer", "Uber", "Öffi", "maps", "Ärzte", "apple"),
            LabelOrder.sorted(names, descending = true, locale = Locale.ENGLISH) { it }
        )
    }

    @Test
    fun namesDifferingOnlyInCaseKeepTheirOrder() {
        val apps = listOf("chrome" to 1, "Chrome" to 2, "CHROME" to 3)
        assertEquals(apps, LabelOrder.sorted(apps, locale = Locale.ENGLISH) { it.first })
        assertEquals(apps, apps.sortedWith(LabelOrder.by(Locale.ENGLISH) { it.first }))
    }

    @Test
    fun theComparatorAgreesWithTheSort() {
        assertEquals(
            LabelOrder.sorted(names, locale = Locale.GERMAN) { it },
            names.sortedWith(LabelOrder.by(Locale.GERMAN) { it })
        )
    }
}
