package com.supernova.launcher.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

// Decodes canned persisted-JSON fixtures the way LauncherRepository does and
// runs them through the migration chain, so old installs keep decoding as the
// model grows.
class ModelMigrationsTest {

    // Mirrors LauncherRepository's configuration.
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Test
    fun versionlessJsonDecodesAsV1AndMigratesToCurrent() {
        val raw = """
            {"items":[
              {"id":"a","itemType":"APP","page":2,"cellX":1,"cellY":1,
               "label":"A","packageName":"com.example","className":"com.example.Main"}
            ]}
        """.trimIndent()

        val decoded = json.decodeFromString<LauncherModel>(raw)
        assertEquals(1, decoded.version)

        val migrated = ModelMigrations.migrate(decoded)
        assertEquals(CURRENT_MODEL_VERSION, migrated.version)
        // v1 predates userSerial; decode must stamp the primary user.
        assertEquals(0L, migrated.items.single().userSerial)
        // v3 derives page count from the pages workspace items occupy.
        assertEquals(3, migrated.pageCount)
        assertEquals(0, migrated.defaultPage)
    }

    @Test
    fun dockItemsDoNotInflateDerivedPageCount() {
        val raw = """
            {"version":2,"items":[
              {"id":"a","itemType":"APP","container":"DOCK","page":5,
               "label":"A","packageName":"p","className":"c"},
              {"id":"b","itemType":"APP","container":"WORKSPACE","page":1,
               "label":"B","packageName":"p","className":"c"}
            ]}
        """.trimIndent()

        val migrated = ModelMigrations.migrate(json.decodeFromString<LauncherModel>(raw))
        assertEquals(2, migrated.pageCount)
    }

    @Test
    fun explicitPageCountSurvivesWhenLargerThanDerived() {
        val model = LauncherModel(version = 2, items = emptyList(), pageCount = 4)
        assertEquals(4, ModelMigrations.migrate(model).pageCount)
    }

    // A canned v4 document (as a phase-6 build persisted it: no feedCards
    // key at all) must decode with the default-supplied empty list and
    // migrate to v5 with only the version stamped.
    @Test
    fun v4FixtureMigratesToV5WithEmptyFeedCards() {
        val raw = """
            {"version":4,"items":[
              {"id":"a","itemType":"APP","container":"WORKSPACE","page":0,"cellX":1,"cellY":2,
               "label":"A","packageName":"com.example","className":"com.example.Main","userSerial":0}
            ],"pageCount":2,"defaultPage":1,
             "appOverrides":{"0/com.example/com.example.Main":{"customLabel":"Renamed"}}}
        """.trimIndent()

        val decoded = json.decodeFromString<LauncherModel>(raw)
        assertEquals(4, decoded.version)
        val migrated = ModelMigrations.migrate(decoded)
        assertEquals(CURRENT_MODEL_VERSION, migrated.version)
        assertEquals(5, migrated.version)
        // Only the version changes: items, layout and overrides are untouched
        // and feedCards arrives as the default empty list.
        assertEquals(decoded.items, migrated.items)
        assertEquals(2, migrated.pageCount)
        assertEquals(1, migrated.defaultPage)
        assertEquals("Renamed", migrated.appOverrides["0/com.example/com.example.Main"]?.customLabel)
        assertEquals(emptyList<com.supernova.launcher.feed.FeedCard>(), migrated.feedCards)
    }

    // A v5 document written before intentUri/iconBase64 existed must decode
    // with both null (they are default-supplied), so no migration is needed.
    @Test
    fun v5FixtureWithoutIntentFieldsDecodesWithNulls() {
        val raw = """
            {"version":5,"items":[
              {"id":"s","itemType":"SHORTCUT","container":"WORKSPACE","page":0,
               "label":"S","packageName":"com.x","shortcutId":"sc1","userSerial":0}
            ],"pageCount":1,"defaultPage":0}
        """.trimIndent()
        val migrated = ModelMigrations.migrate(json.decodeFromString<LauncherModel>(raw))
        assertEquals(CURRENT_MODEL_VERSION, migrated.version)
        val item = migrated.items.single()
        assertEquals(null, item.intentUri)
        assertEquals(null, item.iconBase64)
        assertEquals("sc1", item.shortcutId)
    }

    @Test
    fun currentVersionModelPassesThroughUnchanged() {
        val model = LauncherModel(
            version = CURRENT_MODEL_VERSION,
            items = listOf(LauncherItem(id = "x", itemType = ItemType.FOLDER, label = "F")),
            pageCount = 2,
            defaultPage = 1
        )
        assertEquals(model, ModelMigrations.migrate(model))
    }
}
