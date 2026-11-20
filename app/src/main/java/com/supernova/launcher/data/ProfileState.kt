package com.supernova.launcher.data

import android.content.IntentSender
import android.content.pm.LauncherApps
import android.content.pm.LauncherUserInfo
import android.os.Build
import android.os.UserHandle
import android.os.UserManager
import com.supernova.launcher.runLogged

// The profiles beside the main one: the private space (Android 15+) and work
// profiles, their state and their switches.
class ProfileState(
    private val launcherApps: LauncherApps,
    private val userManager: UserManager,
    private val serialFor: (UserHandle) -> Long
) {
    // The private space profile (Android 15+), seen only while this is the
    // default home app and holds ACCESS_HIDDEN_PROFILES.
    fun privateSpaceUser(): UserHandle? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return null
        return runCatching {
            launcherApps.profiles.firstOrNull { user ->
                launcherApps.getLauncherUserInfo(user)?.userType == UserManager.USER_TYPE_PROFILE_PRIVATE
            }
        }.getOrNull()
    }

    fun privateSpace(): PrivateSpace? {
        val user = privateSpaceUser() ?: return null
        val locked = runCatching { userManager.isQuietModeEnabled(user) }.getOrDefault(true)
        // The system's "Hide private space when locked": the entry point
        // goes too while it is locked.
        val entryHidden = Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA && runCatching {
            launcherApps.getLauncherUserInfo(user)?.userConfig
                ?.getBoolean(LauncherUserInfo.PRIVATE_SPACE_ENTRYPOINT_HIDDEN) == true
        }.getOrDefault(false)
        return PrivateSpace(serialFor(user), locked, entryHidden)
    }

    // Locking takes effect at once; unlocking asks for the space's own
    // lock first, through the system's prompt.
    fun setPrivateSpaceLocked(locked: Boolean): Boolean {
        val user = privateSpaceUser() ?: return false
        return runLogged("lock private space") { userManager.requestQuietModeEnabled(locked, user) }.getOrDefault(false)
    }

    private fun workUsers(): List<UserHandle> = runCatching {
        val self = android.os.Process.myUserHandle()
        WorkProfiles.workUsers(launcherApps.profiles, self, privateSpaceUser(), ::isWorkUser)
    }.getOrDefault(emptyList())

    // A managed profile, not a clone one (dual apps). Android 15 on says
    // which; before it, the user id tells the OEM clones apart (a
    // UserHandle's hash is its user id).
    fun isWorkUser(user: UserHandle): Boolean {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            runCatching { launcherApps.getLauncherUserInfo(user)?.userType }.getOrNull()
        } else {
            null
        }
        return WorkProfiles.isWork(type, user.hashCode())
    }

    private fun isPaused(user: UserHandle): Boolean =
        runCatching { userManager.isQuietModeEnabled(user) }.getOrDefault(false)

    // The serials of the work profiles whose apps are paused.
    fun pausedWorkProfiles(): Set<Long> =
        workUsers().filter(::isPaused).mapTo(mutableSetOf(), serialFor)

    // Pausing takes effect at once; turning work apps on may ask for the
    // work profile's own lock first, through the system's prompt.
    fun setWorkPaused(paused: Boolean) {
        WorkProfiles.toToggle(workUsers(), ::isPaused, paused).forEach { user ->
            runLogged("pause work apps") { userManager.requestQuietModeEnabled(paused, user) }
        }
    }

    // Android 16 on; Android 15 offers launchers no way in.
    fun privateSpaceSettings(): IntentSender? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.BAKLAVA) return null
        return runCatching { launcherApps.privateSpaceSettingsIntent }.getOrNull()
    }

    // The app store, opened to install into the private space.
    fun privateSpaceInstall(): IntentSender? {
        val user = privateSpaceUser()
        if (user == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return null
        return runCatching { launcherApps.getAppMarketActivityIntent(null, user) }.getOrNull()
    }
}
