package com.supernova.launcher.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkProfilesTest {

    @Test
    fun theUsersOwnProfileAndThePrivateSpaceAreNotWork() {
        val profiles = listOf("owner", "work", "private")
        assertEquals(listOf("work"), WorkProfiles.workUsers(profiles, "owner", privateSpace = "private") { true })
    }

    @Test
    fun withoutAPrivateSpaceEveryOtherProfileIsWork() {
        val profiles = listOf("owner", "work")
        assertEquals(listOf("work"), WorkProfiles.workUsers(profiles, "owner", privateSpace = null) { true })
        assertEquals(emptyList<String>(), WorkProfiles.workUsers(listOf("owner"), "owner", null) { true })
    }

    @Test
    fun aCloneProfileIsNotWork() {
        val profiles = listOf("owner", "work", "clone", "private")
        val work = WorkProfiles.workUsers(profiles, self = "owner", privateSpace = "private") { it != "clone" }
        assertEquals(listOf("work"), work)
    }

    @Test
    fun theSystemsProfileTypeDecidesWhenItIsGiven() {
        assertTrue(WorkProfiles.isWork(WorkProfiles.TYPE_MANAGED, userId = 10))
        assertTrue(WorkProfiles.isWork(WorkProfiles.TYPE_MANAGED, userId = 999))
        assertFalse(WorkProfiles.isWork("android.os.usertype.profile.CLONE", userId = 10))
        assertFalse(WorkProfiles.isWork("android.os.usertype.profile.PRIVATE", userId = 11))
        assertFalse(WorkProfiles.isWork("com.oem.usertype.profile.DUAL", userId = 12))
    }

    @Test
    fun withoutATypeOemCloneIdsAreNotWork() {
        assertFalse(WorkProfiles.isWork(null, userId = 999))
        assertFalse(WorkProfiles.isWork(null, userId = 95))
        assertTrue(WorkProfiles.isWork(null, userId = 10))
        assertTrue(WorkProfiles.isWork(null, userId = 150))
    }

    @Test
    fun onlyProfilesNotAlreadyThereAreAskedToChange() {
        val paused = setOf("a")
        val work = listOf("a", "b")
        assertEquals(listOf("b"), WorkProfiles.toToggle(work, paused::contains, pause = true))
        assertEquals(listOf("a"), WorkProfiles.toToggle(work, paused::contains, pause = false))
        assertEquals(emptyList<String>(), WorkProfiles.toToggle(listOf("a"), paused::contains, pause = true))
    }
}
