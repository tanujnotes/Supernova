package com.supernova.launcher.onboarding

import android.content.Intent
import android.provider.MediaStore
import com.supernova.launcher.data.DeviceProfile
import com.supernova.launcher.onboarding.DefaultLayoutBuilder.Place
import com.supernova.launcher.onboarding.DefaultLayoutBuilder.Seat
import com.supernova.launcher.onboarding.DefaultLayoutBuilder.Source
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DefaultLayoutBuilderTest {

    private fun dock(vararg apps: String?) = Place(null, sequenceOf(*apps))

    private fun cell(x: Int, y: Int, vararg apps: String?) = Place(x to y, sequenceOf(*apps))

    // The default recipe with every place filled by its first candidate.
    private val full = listOf(
        dock("dialer"), dock("sms"), dock("maps"), dock("browser"), dock("camera"),
        cell(-1, 0, "settings"), cell(0, -1, "email"), cell(1, -1, "gallery"), cell(-1, -1, "market")
    )

    // The phone grid: 5 columns by 6 rows under a 5-icon dock.
    private fun arrange(places: List<Place>, cols: Int = 5, rows: Int = 6, dockCount: Int = 5) =
        DefaultLayoutBuilder.arrange(places, cols, rows, dockCount)

    @Test
    fun theDockIsPhoneMessagesMapsBrowserCamera() {
        assertEquals(
            listOf("dialer", "sms", "maps", "browser", "camera").mapIndexed { rank, pkg -> Seat(pkg, rank = rank) },
            arrange(full).filter { it.rank != null }
        )
    }

    @Test
    fun theFirstPageHasSettingsTopRightAndThreeAppsAlongTheBottom() {
        assertEquals(
            // Where the default layout puts them: the top-right corner and
            // the bottom row.
            listOf(Seat("settings", x = 4, y = 0), Seat("email", x = 0, y = 5), Seat("gallery", x = 1, y = 5), Seat("market", x = 4, y = 5)),
            arrange(full).filter { it.rank == null }
        )
        // Counted from the far edge, the corners follow the grid.
        assertEquals(
            listOf(Seat("settings", x = 6, y = 0), Seat("market", x = 6, y = 5)),
            arrange(full, cols = 7, rows = 6).filter { it.packageName in setOf("settings", "market") }
        )
    }

    @Test
    fun aPlaceTakesItsFirstCandidateThatResolved() {
        assertEquals(
            listOf(Seat("music", rank = 0)),
            arrange(listOf(dock(null, "music", "other")))
        )
    }

    @Test
    fun whatDoesNotResolveIsDroppedRatherThanLeavingAGap() {
        assertEquals(
            listOf(Seat("dialer", rank = 0), Seat("browser", rank = 1)),
            arrange(listOf(dock("dialer"), dock(null), dock(), dock("browser")))
        )
    }

    @Test
    fun theResolverActivityIsNotAnApp() {
        assertEquals(
            listOf(Seat("dialer", rank = 0), Seat("sms", rank = 1)),
            arrange(listOf(dock("dialer"), dock("android", "sms"), dock("android")))
        )
    }

    @Test
    fun oneAppThatIsTwoDefaultsTakesOneSlot() {
        assertEquals(
            listOf(Seat("suite", rank = 0), Seat("browser", rank = 1)),
            arrange(listOf(dock("suite"), dock("suite", "sms"), dock("suite"), dock("browser"), cell(0, 0, "suite")))
        )
    }

    @Test
    fun theDockStopsAtItsSlotCount() {
        assertEquals(3, arrange(full, dockCount = 3).count { it.rank != null })
    }

    @Test
    fun aCellOffTheGridOrAlreadyTakenStaysEmpty() {
        // Two columns: the gallery and the store both want (1, last).
        assertEquals(
            listOf(Seat("settings", x = 1, y = 0), Seat("email", x = 0, y = 5), Seat("gallery", x = 1, y = 5)),
            arrange(full, cols = 2).filter { it.rank == null }
        )
        assertEquals(
            emptyList(),
            arrange(listOf(cell(5, 0, "a"), cell(0, 6, "b"), cell(-6, 0, "c")))
        )
    }

    @Test
    fun aDeviceThatResolvesNothingGetsAnEmptyLayout() {
        assertTrue(arrange(listOf(dock(null), dock(), cell(0, 0, null))).isEmpty())
    }

    @Test
    fun thePhoneRecipeIsTheDefault5x5() {
        val recipe = DefaultLayoutBuilder.recipe(tablet = false)
        assertEquals(
            listOf(
                Source.Action(Intent.ACTION_DIAL),
                // The user's own SMS app before the category pick.
                Source.DefaultSms,
                Source.Category(Intent.CATEGORY_APP_MAPS),
                // The default browser before the preinstalled one.
                Source.Action(Intent.ACTION_VIEW, "http://www.example.com"),
                Source.Action(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
            ),
            recipe.filter { it.cell == null }.map { it.sources.first() }
        )
        assertEquals(
            listOf(
                (-1 to 0) to Source.Self,
                (0 to -1) to Source.Category(Intent.CATEGORY_APP_EMAIL),
                (1 to -1) to Source.Category(Intent.CATEGORY_APP_GALLERY),
                (-1 to -1) to Source.Category(Intent.CATEGORY_APP_MARKET)
            ),
            recipe.filter { it.cell != null }.map { it.cell to it.sources.first() }
        )
    }

    @Test
    fun theTabletRecipeIsTheDefault6x5() {
        val recipe = DefaultLayoutBuilder.recipe(tablet = true)
        assertEquals(
            listOf(
                Source.Category(Intent.CATEGORY_APP_EMAIL),
                Source.Category(Intent.CATEGORY_APP_CALENDAR),
                Source.Category(Intent.CATEGORY_APP_GALLERY),
                Source.Category(Intent.CATEGORY_APP_MARKET),
                Source.Action(Intent.ACTION_VIEW, "http://www.example.com"),
                Source.Action(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
            ),
            recipe.map { it.sources.first() }
        )
        // A tablet's first page starts empty.
        assertTrue(recipe.all { it.cell == null })
    }

    @Test
    fun theSeedFitsTheDockEveryProfileStartsWith() {
        // A recipe's dock icons fill ranks from 0; a profile whose dock were
        // shorter would lose the last of them (the camera).
        val phone = DefaultLayoutBuilder.recipe(tablet = false).count { it.cell == null }
        assertTrue(phone <= DeviceProfile.PHONE.dockCount)
        val tablet = DefaultLayoutBuilder.recipe(tablet = true).count { it.cell == null }
        listOf(DeviceProfile.MEDIUM, DeviceProfile.EXPANDED).forEach {
            assertTrue(tablet <= it.dockCount, "$it")
        }
    }
}
