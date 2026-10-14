package com.supernova.launcher.data

import kotlin.test.Test
import kotlin.test.assertEquals

class DeviceProfileTest {

    @Test
    fun phonesStartOnTheDefaultGrid() {
        assertEquals(DeviceProfile.PHONE, DeviceProfile.forSmallestWidthDp(320))
        assertEquals(DeviceProfile.PHONE, DeviceProfile.forSmallestWidthDp(411))
        assertEquals(DeviceProfile.PHONE, DeviceProfile.forSmallestWidthDp(599))
        // 5 columns, 6 rows on a fresh phone.
        assertEquals(5, DeviceProfile.PHONE.gridCols)
        assertEquals(6, DeviceProfile.PHONE.gridRows)
        assertEquals(5, DeviceProfile.PHONE.dockCount)
    }

    @Test
    fun thePhoneProfileIsThePreferenceDefault() {
        // The store falls back to the defaults where the profile wrote
        // nothing, so the two have to agree.
        assertEquals(LauncherPrefs(), DeviceProfile.PHONE.applyTo(LauncherPrefs()))
    }

    @Test
    fun aProfileSetsTheGridDockAndEveryIconSize() {
        val prefs = DeviceProfile.MEDIUM.applyTo(LauncherPrefs(showLabels = false))
        assertEquals(6, prefs.gridCols)
        assertEquals(5, prefs.gridRows)
        assertEquals(6, prefs.dockCount)
        listOf(prefs.iconScale, prefs.dockIconScale, prefs.drawerIconScale, prefs.folderIconScale).forEach {
            assertEquals(1.2f, it)
        }
        // Nothing else.
        assertEquals(false, prefs.showLabels)
    }

    @Test
    fun mediumScreensGetSixColumns() {
        assertEquals(DeviceProfile.MEDIUM, DeviceProfile.forSmallestWidthDp(600))
        assertEquals(DeviceProfile.MEDIUM, DeviceProfile.forSmallestWidthDp(720))
        assertEquals(DeviceProfile.MEDIUM, DeviceProfile.forSmallestWidthDp(839))
    }

    @Test
    fun expandedScreensGetSevenBySix() {
        assertEquals(DeviceProfile.EXPANDED, DeviceProfile.forSmallestWidthDp(840))
        assertEquals(DeviceProfile.EXPANDED, DeviceProfile.forSmallestWidthDp(1280))
    }

    @Test
    fun everyProfileStaysInsideThePreferenceRanges() {
        listOf(DeviceProfile.PHONE, DeviceProfile.MEDIUM, DeviceProfile.EXPANDED).forEach {
            assert(it.gridCols in 2..12) { it }
            assert(it.gridRows in 2..12) { it }
            assert(it.dockCount in 3..7) { it }
            assert(it.iconScale in 0.7f..1.6f) { it }
        }
    }
}
