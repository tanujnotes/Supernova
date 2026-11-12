package com.supernova.launcher.data

import org.junit.Assert.assertEquals
import org.junit.Test

class WorkProfilesTest {

    @Test
    fun theUsersOwnProfileAndThePrivateSpaceAreNotWork() {
        val profiles = listOf("owner", "work", "private")
        assertEquals(listOf("work"), WorkProfiles.workUsers(profiles, self = "owner", privateSpace = "private"))
    }

    @Test
    fun withoutAPrivateSpaceEveryOtherProfileIsWork() {
        val profiles = listOf("owner", "work")
        assertEquals(listOf("work"), WorkProfiles.workUsers(profiles, self = "owner", privateSpace = null))
        assertEquals(emptyList<String>(), WorkProfiles.workUsers(listOf("owner"), self = "owner", privateSpace = null))
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
