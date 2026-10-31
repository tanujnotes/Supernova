package com.supernova.launcher.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// The drawer-group wire format must stay backward-decodable in both
// directions: entries written by old builds (fewer segments) decode with safe
// defaults, and entries written by this build degrade gracefully when an old
// build reads only the first three segments.
class DrawerGroupCodecTest {

    @Test
    fun decodesLegacyTwoSegmentEntry() {
        val group = DrawerGroup.decode("Games|0/com.a/com.a.Main,0/com.b/com.b.Main")
        assertEquals("Games", group?.name)
        assertEquals(setOf("0/com.a/com.a.Main", "0/com.b/com.b.Main"), group?.members)
        assertEquals(Int.MAX_VALUE, group?.order)
        assertEquals(DrawerGroupType.TAB, group?.type)
        assertFalse(group!!.smart)
    }

    @Test
    fun decodesLegacyThreeSegmentEntry() {
        val group = DrawerGroup.decode("Work|0/com.a/com.a.Main|2")
        assertEquals("Work", group?.name)
        assertEquals(2, group?.order)
        assertEquals(DrawerGroupType.TAB, group?.type)
        assertFalse(group!!.smart)
    }

    @Test
    fun roundTripsFolderTypeAndSmartFlag() {
        val original = DrawerGroup(
            name = "Social",
            members = setOf("0/com.whatsapp/com.whatsapp.Main"),
            order = 1,
            type = DrawerGroupType.FOLDER,
            smart = true
        )
        assertEquals(original, DrawerGroup.decode(original.encode()))
    }

    @Test
    fun roundTripsUserFolderWithoutSmartFlag() {
        val original = DrawerGroup(
            name = "Tools",
            members = setOf("0/com.a/com.a.Main"),
            order = 0,
            type = DrawerGroupType.FOLDER,
            smart = false
        )
        val decoded = DrawerGroup.decode(original.encode())
        assertEquals(original, decoded)
        assertFalse(decoded!!.smart)
    }

    @Test
    fun roundTripsDefaultTabGroup() {
        val original = DrawerGroup("Games", setOf("0/com.a/com.a.Main"), order = 3)
        assertEquals(original, DrawerGroup.decode(original.encode()))
    }

    @Test
    fun newEncodingDegradesToTabInOldBuilds() {
        // Mirror of the pre-v5 decoder: split('|'), read parts 0..2 only.
        val encoded = DrawerGroup(
            name = "Finance",
            members = setOf("0/com.phonepe/com.phonepe.Main"),
            order = 4,
            type = DrawerGroupType.FOLDER,
            smart = true
        ).encode()

        val parts = encoded.split('|')
        assertEquals("Finance", parts[0])
        assertEquals("0/com.phonepe/com.phonepe.Main", parts[1])
        assertEquals(4, parts[2].toInt())
        // The extra segments ride along without corrupting the first three.
        assertEquals("FOLDER", parts[3])
        assertEquals(DrawerGroup.SMART_MARKER, parts[4])
    }

    @Test
    fun unknownTypeSegmentFallsBackToTab() {
        // A future build writing a new type must still decode here as TAB.
        val group = DrawerGroup.decode("X|0/com.a/com.a.Main|1|HOLOGRAM|")
        assertEquals(DrawerGroupType.TAB, group?.type)
        assertFalse(group!!.smart)
    }

    @Test
    fun blankNameStaysUndecodable() {
        assertNull(DrawerGroup.decode("|a,b|1|FOLDER|smart"))
    }

    @Test
    fun smartMarkerIsExactMatch() {
        val decoded = DrawerGroup.decode("A|k|1|FOLDER|smartish")
        assertTrue(decoded != null && !decoded.smart)
    }

    // Keep apps in main app tab: kept, moved, or never chosen (a group
    // from before the choice, which keeps its old behaviour).
    @Test
    fun roundTripsKeepInMainChoice() {
        val kept = DrawerGroup("Media", setOf("0/com.a/com.a.Main"), 0, DrawerGroupType.TAB, keepInMain = true)
        val moved = kept.copy(name = "Chat", keepInMain = false)
        assertEquals(kept, DrawerGroup.decode(kept.encode()))
        assertEquals(moved, DrawerGroup.decode(moved.encode()))
    }

    @Test
    fun groupWithoutKeepSegmentHasNoChoice() {
        assertNull(DrawerGroup.decode("Games|0/com.a/com.a.Main|1|FOLDER|smart")!!.keepInMain)
        val unchosen = DrawerGroup("Tools", emptySet(), 2, DrawerGroupType.FOLDER)
        assertEquals("Tools||2|FOLDER|", unchosen.encode())
    }

    @Test
    fun customIconRoundTrips() {
        val photo = DrawerGroup("A", setOf("k"), 1, DrawerGroupType.FOLDER, iconBase64 = "aGk+/w==", iconRaw = true)
        assertEquals(photo, DrawerGroup.decode(photo.encode()))
        val pack = photo.copy(keepInMain = true, iconBase64 = null, iconPack = "com.pack", iconName = "star", iconRaw = false)
        assertEquals(pack, DrawerGroup.decode(pack.encode()))
    }

    @Test
    fun noIconKeepsTheOldFormat() {
        val group = DrawerGroup("A", setOf("k"), 1, DrawerGroupType.FOLDER)
        assertEquals("A|k|1|FOLDER|", group.encode())
        assertEquals("A|k|1|FOLDER||keep", group.copy(keepInMain = true).encode())
    }
}
