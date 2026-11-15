package com.supernova.launcher.data

// Which of the launcher's profiles are work profiles. Only a managed
// profile is: a clone profile (dual apps, app twins) keeps its apps on the
// personal list with the system's badge, and the private space has its own
// place. Only a work profile (or the private space) can be paused, so the
// quiet ones among them are the paused work profiles.
object WorkProfiles {

    const val TYPE_MANAGED = "android.os.usertype.profile.MANAGED"

    // The user ids OEM app-cloning features put their clones under, for
    // phones that don't say a profile's type (before Android 15): 999 on
    // Xiaomi, OnePlus, Oppo, Realme and vivo, 95 on Samsung.
    private const val SAMSUNG_CLONE_USER = 95
    private const val OEM_CLONE_USER = 999
    private val CLONE_USER_IDS = setOf(SAMSUNG_CLONE_USER, OEM_CLONE_USER)

    // A profile other than the user's own: the system's word for its type
    // when it gives one, else everything but a known clone id is work.
    fun isWork(userType: String?, userId: Int): Boolean =
        if (userType != null) userType == TYPE_MANAGED else userId !in CLONE_USER_IDS

    fun <U> workUsers(profiles: List<U>, self: U, privateSpace: U?, isWork: (U) -> Boolean): List<U> =
        profiles.filter { it != self && it != privateSpace && isWork(it) }

    // What turning work apps on or off asks the system to change: only the
    // profiles not already in the wanted state.
    fun <U> toToggle(workUsers: List<U>, paused: (U) -> Boolean, pause: Boolean): List<U> =
        workUsers.filter { paused(it) != pause }
}
