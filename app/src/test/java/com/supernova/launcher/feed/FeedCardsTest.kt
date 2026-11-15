package com.supernova.launcher.feed

import com.supernova.launcher.data.CURRENT_MODEL_VERSION
import com.supernova.launcher.data.LauncherModel
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// FeedCard codec discipline: full round-trip through the repository's JSON
// configuration, plus tolerance for junk (unknown keys, missing fields,
// free-form enum strings) so hand-edited or future backups never brick the
// model decode.
class FeedCardsTest {

    // Mirrors LauncherRepository's configuration.
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private fun fullCard() = FeedCard(
        id = "card-1",
        name = "Morning",
        featuredType = "APP",
        featuredTarget = "com.a/com.a.Main@0",
        appWidgetId = 42,
        widgetProvider = "com.w/com.w.Provider",
        actions = listOf(
            CardAction("APP", "com.b/com.b.Main@0", "B"),
            CardAction("SHORTCUT", "com.c|shortcut1@10", "C: One")
        ),
        conditions = listOf(
            CardCondition(
                kind = "DAY_TIME",
                days = listOf(1, 2, 3, 4, 5),
                startMinute = 6 * 60,
                endMinute = 11 * 60
            ),
            CardCondition(kind = "BLUETOOTH", btName = "Car Audio")
        ),
        enabled = false,
        pinned = true,
        widgetHeightDp = 720,
        activationEnabled = false,
        showFrame = false,
        fillPage = true
    )

    @Test
    fun cardRoundTripsEveryField() {
        val card = fullCard()
        val decoded = json.decodeFromString<FeedCard>(json.encodeToString(card))
        assertEquals(card, decoded)
    }

    @Test
    fun modelWithCardsRoundTrips() {
        val model = LauncherModel(
            version = CURRENT_MODEL_VERSION,
            feedCards = listOf(fullCard(), FeedCard(id = "bare"))
        )
        val decoded = json.decodeFromString<LauncherModel>(json.encodeToString(model))
        assertEquals(model, decoded)
    }

    // A card written by a future version (extra keys, unknown enum strings)
    // or hand-edited (missing everything but the id) must still decode.
    @Test
    fun junkTolerantDecode() {
        val raw = """
            {"id":"weird","someFutureField":{"nested":true},
             "featuredType":"HOLOGRAM","featuredTarget":null,
             "actions":[{"type":"APP","target":"com.x/com.x.Main@0","label":"X","futureKey":1}],
             "conditions":[{"kind":"MOON_PHASE"},{"kind":"WIFI","ssid":"Net"}]}
        """.trimIndent()

        val decoded = json.decodeFromString<FeedCard>(raw)
        assertEquals("weird", decoded.id)
        // Defaults fill everything absent.
        assertEquals("", decoded.name)
        assertTrue(decoded.enabled)
        assertFalse(decoded.pinned)
        assertNull(decoded.appWidgetId)
        assertEquals(1, decoded.actions.size)
        assertEquals(2, decoded.conditions.size)
        // Unknown enum strings resolve to null / are ignored by consumers.
        assertNull(FeaturedType.fromName(decoded.featuredType))
        assertNull(ConditionKind.fromName("MOON_PHASE"))
        // The unknown-kind condition never blocks visibility.
        assertTrue(
            CardVisibility.isVisible(
                decoded.copy(conditions = listOf(CardCondition(kind = "MOON_PHASE"))),
                CardVisibility.Environment(dayOfWeek = 1, minuteOfDay = 0)
            )
        )
    }

    // A backup carries "Fill the page" and a height past the old 400dp cap.
    @Test
    fun backupKeepsFillPageAndTallHeight() {
        val payload = com.supernova.launcher.data.BackupPayload(
            exportedAtEpochMs = 1,
            model = LauncherModel(version = CURRENT_MODEL_VERSION, feedCards = listOf(fullCard())),
            prefs = com.supernova.launcher.data.LauncherPrefs()
        )
        val raw = json.encodeToString(com.supernova.launcher.data.BackupPayload.serializer(), payload)
        val card = json.decodeFromString<com.supernova.launcher.data.BackupPayload>(raw).model.feedCards.single()
        assertTrue(card.fillPage)
        assertEquals(720, card.widgetHeight)
    }

    // Stores and backups from before the switch existed: off.
    @Test
    fun olderCardWithoutFillPageIsOff() {
        val decoded = json.decodeFromString<FeedCard>("""{"id":"x","featuredType":"WIDGET","widgetHeightDp":300}""")
        assertFalse(decoded.fillPage)
        assertEquals(300, decoded.widgetHeight)
    }

    // Whatever was stored, the height read back stays on the stepper.
    @Test
    fun widgetHeightIsClampedToTheStepperRange() {
        assertEquals(FeedCard.WIDGET_HEIGHT_MIN_DP, FeedCard(id = "a", widgetHeightDp = 0).widgetHeight)
        assertEquals(FeedCard.WIDGET_HEIGHT_MIN_DP, FeedCard(id = "a", widgetHeightDp = -40).widgetHeight)
        assertEquals(FeedCard.WIDGET_HEIGHT_MAX_DP, FeedCard(id = "a", widgetHeightDp = 5000).widgetHeight)
        assertEquals(800, FeedCard(id = "a", widgetHeightDp = 800).widgetHeight)
        assertEquals(160, FeedCard(id = "a").widgetHeight)
    }

    @Test
    fun bareMinimumJsonDecodes() {
        val decoded = json.decodeFromString<FeedCard>("""{"id":"x"}""")
        assertEquals(FeedCard(id = "x"), decoded)
    }
}
