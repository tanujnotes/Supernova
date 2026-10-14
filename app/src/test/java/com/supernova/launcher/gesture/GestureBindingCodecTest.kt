package com.supernova.launcher.gesture

import org.junit.Assert.assertEquals
import org.junit.Test

class GestureBindingCodecTest {

    private fun roundTrip(binding: GestureBinding): GestureBinding? =
        GestureDefaults.decode(setOf(GestureDefaults.encode(Gesture.DOUBLE_TAP, binding)))[Gesture.DOUBLE_TAP]

    // An app's shortcut screen hands back an intent, and its URI is full of
    // ':'s - the field separator - which used to cut it short.
    @Test
    fun intentUriPayloadSurvives() {
        val binding = GestureBinding(
            GestureAction.LAUNCH_INTENT,
            payload = "intent:tel:5551234#Intent;action=android.intent.action.CALL;end",
            label = "Call: Mom"
        )
        assertEquals(binding, roundTrip(binding))
    }

    @Test
    fun percentSignsSurvive() {
        val binding = GestureBinding(GestureAction.LAUNCH_APP, payload = "com.a/com.a.Main@0", label = "100% %3A app")
        assertEquals(binding, roundTrip(binding))
    }

    // Written before payloads were escaped: a label with ':' in it (every
    // "App: Shortcut" label) was already fine, as the label is the last field.
    @Test
    fun entriesWrittenBeforeEscapingStillDecode() {
        val stored = "DOUBLE_TAP:LAUNCH_SHORTCUT:com.a|chat@s.whatsapp.net@0:Chrome: New tab"
        val decoded = GestureDefaults.decode(setOf(stored))
        assertEquals(
            GestureBinding(GestureAction.LAUNCH_SHORTCUT, "com.a|chat@s.whatsapp.net@0", "Chrome: New tab"),
            decoded[Gesture.DOUBLE_TAP]
        )
    }

    // Nothing to escape: written exactly as before, so older builds read it.
    @Test
    fun plainFieldsAreWrittenAsTheyAre() {
        val binding = GestureBinding(GestureAction.LAUNCH_APP, payload = "com.a/com.a.Main@0", label = "Calculator")
        val encoded = GestureDefaults.encode(Gesture.DOUBLE_TAP, binding)
        assertEquals("DOUBLE_TAP:LAUNCH_APP:com.a/com.a.Main@0:Calculator", encoded)
    }

    // An entry from before escaping keeps a literal "%3A" (a URL-encoded
    // shortcut id) as it was.
    @Test
    fun oldEntriesAreNotUnescaped() {
        val decoded = GestureDefaults.decode(setOf("DOUBLE_TAP:LAUNCH_SHORTCUT:com.a|id%3Ax@0:Maps"))
        assertEquals("com.a|id%3Ax@0", decoded[Gesture.DOUBLE_TAP]?.payload)
    }

    @Test
    fun aValueThatStartsWithTheMarkerSurvives() {
        val binding = GestureBinding(GestureAction.LAUNCH_APP, payload = "com.a/com.a.Main@0", label = "~tilde app")
        assertEquals(binding, roundTrip(binding))
    }

    @Test
    fun pickerNeverOffersTheSavedSearchAction() {
        assert(GestureAction.SEARCH_QUERY !in GestureDefaults.pickerActions)
        assert(GestureAction.NONE !in GestureDefaults.pickerActions)
        assert(GestureAction.JUMP_TO_PAGE in GestureDefaults.pickerActions)
    }
}
