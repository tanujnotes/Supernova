package com.supernova.launcher.gesture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ActionShortcutTest {

    @Test
    fun encodeDecodeRoundTripsAPayloadlessAction() {
        val uri = ActionShortcut.encode(GestureAction.OPEN_DRAWER)
        assertEquals("supernova://action/OPEN_DRAWER", uri)
        val decoded = ActionShortcut.decode(uri)
        assertEquals(GestureAction.OPEN_DRAWER, decoded?.action)
        assertNull(decoded?.payload)
    }

    @Test
    fun encodeDecodeCarriesAPayload() {
        val uri = ActionShortcut.encode(GestureAction.JUMP_TO_PAGE, "2")
        val decoded = ActionShortcut.decode(uri)
        assertEquals(GestureAction.JUMP_TO_PAGE, decoded?.action)
        assertEquals("2", decoded?.payload)
    }

    @Test
    fun decodeReturnsNullForForeignUris() {
        assertNull(ActionShortcut.decode(null))
        assertNull(ActionShortcut.decode("https://example.com"))
        assertNull(ActionShortcut.decode("intent:#Intent;action=android.intent.action.VIEW;end"))
    }

    @Test
    fun payloadActionWithoutPayloadDecodesToNull() {
        // A page-jump with no page index is meaningless; decode rejects it so
        // launchItem falls through rather than jumping nowhere.
        assertNull(ActionShortcut.decode("supernova://action/JUMP_TO_PAGE"))
    }

    @Test
    fun unknownActionNameDecodesToNoneBinding() {
        val decoded = ActionShortcut.decode("supernova://action/NOT_A_REAL_ACTION")
        assertEquals(GestureAction.NONE, decoded?.action)
    }

    @Test
    fun choicesAreAllRenderableAndPayloadless() {
        // Everything offered as a standalone icon must be launchable with no
        // target; payload actions are added through their own target pickers.
        ActionShortcut.choices.forEach { action ->
            val uri = ActionShortcut.encode(action)
            assertEquals(action, ActionShortcut.decode(uri)?.action)
        }
    }
}
