package com.supernova.launcher.ui

import com.supernova.launcher.data.PrivateSpace
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LauncherUiStatePageOnlyTest {

    private val base = LauncherUiState(loaded = true)

    @Test
    fun aPageChangeAloneIsOnlyAPageMove() {
        assertTrue(base.copy(currentPage = 2).onlyPageMovedFrom(base))
    }

    @Test
    fun workAppsPausingIsNotOnlyAPageMove() {
        // The app list stays the same when work apps pause, so this was the
        // only change, and the drawer never heard of it.
        assertFalse(base.copy(pausedWorkProfiles = setOf(10L)).onlyPageMovedFrom(base))
        assertFalse(base.onlyPageMovedFrom(base.copy(pausedWorkProfiles = setOf(10L))))
    }

    @Test
    fun thePrivateSpaceLockingIsNotOnlyAPageMove() {
        val locked = base.copy(privateSpace = PrivateSpace(11L, locked = true, hiddenWhenLocked = false))
        val unlocked = base.copy(privateSpace = PrivateSpace(11L, locked = false, hiddenWhenLocked = false))
        assertFalse(unlocked.onlyPageMovedFrom(locked))
    }
}
