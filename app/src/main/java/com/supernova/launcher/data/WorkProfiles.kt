package com.supernova.launcher.data

// Which of the launcher's profiles are work profiles: any other than the
// user's own and the private space. Only a work profile (or the private
// space) can be paused, so the quiet ones among them are the paused work
// profiles.
object WorkProfiles {

    fun <U> workUsers(profiles: List<U>, self: U, privateSpace: U?): List<U> =
        profiles.filter { it != self && it != privateSpace }

    // What turning work apps on or off asks the system to change: only the
    // profiles not already in the wanted state.
    fun <U> toToggle(workUsers: List<U>, paused: (U) -> Boolean, pause: Boolean): List<U> =
        workUsers.filter { paused(it) != pause }
}
