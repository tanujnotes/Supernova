package com.supernova.launcher.feed

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

// Feeds CardVisibility.Environment with the connected Bluetooth audio
// device names. Registered only while the feed is shown AND at least one
// enabled custom card actually carries a BLUETOOTH condition; otherwise it
// never touches a system service.
class ConditionMonitor(private val context: Context) {

    // Names snapshot from the HEADSET/A2DP profile proxies; refreshed when a
    // proxy connects and re-read on demand via btDeviceNames().
    private var headsetProxy: BluetoothProfile? = null
    private var a2dpProxy: BluetoothProfile? = null
    private var running = false

    var onChanged: (() -> Unit)? = null

    // Bumped by every start, so a Bluetooth proxy answering a request from
    // before the last stop can tell it is stale.
    private var generation = 0

    fun start(needsBluetooth: Boolean) {
        if (running) return
        running = true
        val started = ++generation
        if (needsBluetooth && btPermissionGranted()) {
            val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
            if (adapter != null && runCatching { adapter.isEnabled }.getOrDefault(false)) {
                val listener = object : BluetoothProfile.ServiceListener {
                    override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                        // A proxy answering a request from before a stop, or
                        // a second one for a profile already held, is closed
                        // straight away: kept, or overwritten unclosed, it
                        // stays bound for the life of the process.
                        val held = when (profile) {
                            BluetoothProfile.HEADSET -> headsetProxy
                            BluetoothProfile.A2DP -> a2dpProxy
                            else -> null
                        }
                        if (!running || started != generation || held != null) {
                            runCatching { adapter.closeProfileProxy(profile, proxy) }
                            return
                        }
                        when (profile) {
                            BluetoothProfile.HEADSET -> headsetProxy = proxy
                            BluetoothProfile.A2DP -> a2dpProxy = proxy
                        }
                        onChanged?.invoke()
                    }

                    override fun onServiceDisconnected(profile: Int) {
                        when (profile) {
                            BluetoothProfile.HEADSET -> headsetProxy = null
                            BluetoothProfile.A2DP -> a2dpProxy = null
                        }
                    }
                }
                runCatching { adapter.getProfileProxy(context, listener, BluetoothProfile.HEADSET) }
                runCatching { adapter.getProfileProxy(context, listener, BluetoothProfile.A2DP) }
            }
        }
    }

    fun stop() {
        if (!running) return
        running = false
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        headsetProxy?.let { runCatching { adapter?.closeProfileProxy(BluetoothProfile.HEADSET, it) } }
        a2dpProxy?.let { runCatching { adapter?.closeProfileProxy(BluetoothProfile.A2DP, it) } }
        headsetProxy = null
        a2dpProxy = null
    }

    // Lint can't see through btPermissionGranted(); a revoke racing it
    // throws inside the runCatching.
    @SuppressLint("MissingPermission")
    fun btDeviceNames(): List<String> {
        if (!btPermissionGranted()) return emptyList()
        return listOfNotNull(headsetProxy, a2dpProxy).flatMap { proxy ->
            runCatching {
                proxy.connectedDevices.mapNotNull { device ->
                    runCatching { device.name }.getOrNull()
                }
            }.getOrDefault(emptyList())
        }.distinct()
    }

    private fun btPermissionGranted(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED
}
