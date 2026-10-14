package com.supernova.launcher.data

import org.junit.Assert.assertEquals
import org.junit.Test

class LaunchTargetTest {

    @Test
    fun anAppTargetSplitsIntoComponentAndProfile() {
        assertEquals("com.a/com.a.Main" to 10L, LaunchTarget.split("com.a/com.a.Main@10"))
    }

    @Test
    fun aShortcutIdMayHoldAnAt() {
        assertEquals(
            "com.whatsapp|919876543210@s.whatsapp.net" to 0L,
            LaunchTarget.split("com.whatsapp|919876543210@s.whatsapp.net@0")
        )
        assertEquals("com.chat|room@x" to 11L, LaunchTarget.split("com.chat|room@x@11"))
    }

    @Test
    fun noProfileMeansThePersonalOne() {
        assertEquals("com.a/com.a.Main" to 0L, LaunchTarget.split("com.a/com.a.Main"))
        // An '@' not followed by a number is part of the id.
        assertEquals("com.chat|room@x" to 0L, LaunchTarget.split("com.chat|room@x"))
    }
}
