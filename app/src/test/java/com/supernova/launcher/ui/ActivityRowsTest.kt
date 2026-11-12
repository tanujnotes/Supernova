package com.supernova.launcher.ui

import com.supernova.launcher.data.ActivityApp
import com.supernova.launcher.data.ActivityCatalog
import com.supernova.launcher.data.ActivityChoice
import com.supernova.launcher.ui.ActivityRows.Row
import com.supernova.launcher.ui.FastScrollMath.Section
import org.junit.Assert.assertEquals
import org.junit.Test

// The Activities picker's cards, their activities, how each activity's class
// is shown, and what its search leaves.
class ActivityRowsTest {

    private fun choice(name: String) = ActivityChoice(name, name.substringAfterLast('.'), hasOwnIcon = false)

    private val egg = ActivityApp(
        "com.android.egg",
        "Android Easter Egg",
        listOf(choice("com.android.egg.neko.NekoLand"), choice("com.android.egg.ComponentActivationActivity"))
    )
    private val camera = ActivityApp(
        "com.sec.android.app.camera", "Camera", listOf(choice("com.sec.android.app.camera.Camera"))
    )
    private val settings = ActivityApp(
        "com.android.settings", "Settings", listOf(choice("com.android.settings.Settings"))
    )

    @Test
    fun closedCardsListOnlyTheApps() {
        assertEquals(
            listOf(Row.App(egg, false), Row.App(camera, false)),
            ActivityRows.flatten(listOf(egg, camera), expanded = emptySet())
        )
    }

    @Test
    fun anOpenCardIsFollowedByItsActivitiesInOrder() {
        assertEquals(
            listOf(
                Row.App(egg, true),
                Row.Activity(egg, egg.activities[0]),
                Row.Activity(egg, egg.activities[1]),
                Row.App(camera, false)
            ),
            ActivityRows.flatten(listOf(egg, camera), expanded = setOf("com.android.egg"))
        )
    }

    @Test
    fun lettersFollowTheAppCardsPastOpenOnes() {
        val rows = ActivityRows.flatten(listOf(egg, camera, settings), expanded = setOf("com.android.egg"))
        assertEquals(
            listOf(Section("A", 0), Section("C", 3), Section("S", 4)),
            ActivityRows.sections(rows, FastScrollMath::plainLabel)
        )
    }

    @Test
    fun aClassInItsPackageIsShownFromThePackagesDot() {
        assertEquals(
            ".neko.NekoLand",
            ActivityCatalog.shortClassName("com.android.egg", "com.android.egg.neko.NekoLand")
        )
    }

    @Test
    fun aClassOutsideItsPackageKeepsItsWholeName() {
        assertEquals(
            "com.android.internal.app.ResolverActivity",
            ActivityCatalog.shortClassName("android", "com.android.internal.app.ResolverActivity")
        )
        // A package name that is only the start of another's.
        assertEquals(
            "com.android.eggs.Main",
            ActivityCatalog.shortClassName("com.android.egg", "com.android.eggs.Main")
        )
    }

    @Test
    fun aBlankQueryLeavesEveryApp() {
        val apps = listOf(egg, camera, settings)
        assertEquals(apps, ActivityRows.Search(apps).matching(""))
        assertEquals(apps, ActivityRows.Search(apps).matching("   "))
    }

    @Test
    fun anAppWhoseNameMatchesKeepsAllItsActivities() {
        // "egg" is also in both of its classes' package, but the name alone decides.
        assertEquals(listOf(egg), ActivityRows.Search(listOf(egg, camera, settings)).matching("Easter"))
        assertEquals(listOf(camera), ActivityRows.Search(listOf(egg, camera, settings)).matching("came"))
    }

    @Test
    fun anActivityNameMatchKeepsOnlyThatActivityUnderItsApp() {
        assertEquals(
            listOf(egg.copy(activities = listOf(egg.activities[0]))),
            ActivityRows.Search(listOf(egg, camera, settings)).matching("nekoland")
        )
    }

    @Test
    fun theClassAsItsRowShowsItMatches() {
        val wifi = ActivityChoice("com.android.settings.wifi.WifiPickerActivity", "Wi-Fi", hasOwnIcon = false)
        val withWifi = settings.copy(label = "Settings", activities = settings.activities + wifi)
        // ".wifi.WifiPickerActivity": the package under it, its class's own part.
        assertEquals(
            listOf(withWifi.copy(activities = listOf(wifi))),
            ActivityRows.Search(listOf(withWifi)).matching(".wifi.")
        )
        // The package before the row's dot is not shown, so it does not match.
        assertEquals(emptyList<ActivityApp>(), ActivityRows.Search(listOf(egg, camera)).matching("com.android"))
    }

    @Test
    fun matchingIgnoresCaseAndTheQuerysOuterSpaces() {
        assertEquals(listOf(camera), ActivityRows.Search(listOf(egg, camera)).matching("  CAMERA "))
    }

    @Test
    fun anAppWithNoMatchDropsOutAndTheOrderStays() {
        // "Settings" has no "a" in its name, its activity's or its class.
        val left = ActivityRows.Search(listOf(egg, camera, settings)).matching("a")
        assertEquals(listOf("Android Easter Egg", "Camera"), left.map { it.label })
        assertEquals(emptyList<ActivityApp>(), ActivityRows.Search(listOf(egg, camera, settings)).matching("zzz"))
    }

    @Test
    fun aQueryDoesNotMatchAcrossANameAndItsClass() {
        // "NekoLand" then ".neko.NekoLand": the end of one and the start of the other.
        assertEquals(emptyList<ActivityApp>(), ActivityRows.Search(listOf(egg)).matching("land.neko"))
    }
}
