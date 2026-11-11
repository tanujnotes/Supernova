package com.supernova.launcher.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

// Usage data used to live in the main store. An upgrade must carry it into
// the usage store, take it out of the main one, and touch nothing else there.
class UsageMigrationTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val scope = CoroutineScope(Dispatchers.IO + Job())

    @After
    fun tearDown() = scope.cancel()

    private fun store(name: String, migration: UsageMigration? = null): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(
            migrations = listOfNotNull(migration),
            scope = scope,
            produceFile = { folder.root.resolve("$name.preferences_pb") }
        )

    @Test
    fun `usage keys move out of the main store`() = runBlocking {
        val main = store("main")
        main.edit {
            it[UsageKeys.launchCounts] = setOf("0/com.a/com.a.Main=3")
            it[UsageKeys.recentLaunches] = setOf("0/com.a/com.a.Main=1700000000000")
            it[UsageKeys.dockPageState] = 2
            it[UsageKeys.searchRecents] = "maps\nclock"
            it[StoreKeys.model] = "{}"
            it[StoreKeys.onboardingDone] = true
        }
        val usage = store("usage", UsageMigration { main })

        val moved = usage.data.first()
        assertEquals(setOf("0/com.a/com.a.Main=3"), moved[UsageKeys.launchCounts])
        assertEquals(setOf("0/com.a/com.a.Main=1700000000000"), moved[UsageKeys.recentLaunches])
        assertEquals(2, moved[UsageKeys.dockPageState])
        assertEquals("maps\nclock", moved[UsageKeys.searchRecents])

        val left = main.data.first()
        UsageKeys.all.forEach { assertFalse("$it still in the main store", it in left) }
        assertEquals("{}", left[StoreKeys.model])
        assertEquals(true, left[StoreKeys.onboardingDone])
    }

    @Test
    fun `a fresh install has nothing to move`() = runBlocking {
        val main = store("main")
        main.edit { it[StoreKeys.model] = "{}" }
        val usage = store("usage", UsageMigration { main })

        assertNull(usage.data.first()[UsageKeys.launchCounts])
        assertEquals("{}", main.data.first()[StoreKeys.model])
    }
}
