package com.supernova.launcher.feed

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build

// Feeds CardVisibility.Environment with the live Wi-Fi SSID and connected
// Bluetooth audio device names. Registered only while the feed is shown AND
// at least one enabled custom card actually carries a WIFI/BLUETOOTH
// condition; otherwise it never touches a system service.
//
// Wi-Fi SSID caveats (documented degrade, surfaced as a "location needed"
// hint in the card editor, never on the feed):
//  - Reading the SSID requires ACCESS_FINE_LOCATION granted AND location on;
//    otherwise the OS returns "<unknown ssid>" and the condition is unmet.
//  - API 31+: WifiInfo arrives via NetworkCallback transportInfo, which only
//    carries location-sensitive fields when the callback is registered with
//    FLAG_INCLUDE_LOCATION_INFO.
//  - API 29-30: transportInfo does not exist; the deprecated
//    WifiManager.connectionInfo is the only source.
class ConditionMonitor(private val context: Context) {

    @Volatile
    var currentSsid: String? = null
        private set

    // Names snapshot from the HEADSET/A2DP profile proxies; refreshed when a
    // proxy connects and re-read on demand via btDeviceNames().
    private var headsetProxy: BluetoothProfile? = null
    private var a2dpProxy: BluetoothProfile? = null
    private var running = false
    private var networkCallbackRegistered = false

    var onChanged: (() -> Unit)? = null

    private val connectivity: ConnectivityManager?
        get() = context.getSystemService(ConnectivityManager::class.java)

    private val networkCallback = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        object : ConnectivityManager.NetworkCallback(FLAG_INCLUDE_LOCATION_INFO) {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                updateSsid((caps.transportInfo as? WifiInfo)?.ssid)
            }

            override fun onLost(network: Network) = updateSsid(null)
        }
    } else {
        object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                // Pre-S: transportInfo carries no WifiInfo; fall back to the
                // deprecated global connection info.
                @Suppress("DEPRECATION")
                updateSsid(
                    context.getSystemService(WifiManager::class.java)?.connectionInfo?.ssid
                )
            }

            override fun onLost(network: Network) = updateSsid(null)
        }
    }

    // Bumped by every start, so a Bluetooth proxy answering a request from
    // before the last stop can tell it is stale.
    private var generation = 0

    fun start(needsWifi: Boolean, needsBluetooth: Boolean) {
        if (running) return
        running = true
        val started = ++generation
        if (needsWifi && locationUsable()) {
            runCatching {
                connectivity?.registerNetworkCallback(
                    NetworkRequest.Builder()
                        .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                        .build(),
                    networkCallback
                )
                networkCallbackRegistered = true
            }
        }
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
        if (networkCallbackRegistered) {
            networkCallbackRegistered = false
            runCatching { connectivity?.unregisterNetworkCallback(networkCallback) }
        }
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        headsetProxy?.let { runCatching { adapter?.closeProfileProxy(BluetoothProfile.HEADSET, it) } }
        a2dpProxy?.let { runCatching { adapter?.closeProfileProxy(BluetoothProfile.A2DP, it) } }
        headsetProxy = null
        a2dpProxy = null
        currentSsid = null
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

    private fun updateSsid(raw: String?) {
        val cleaned = raw?.removeSurrounding("\"")
            ?.takeIf { it.isNotBlank() && it != UNKNOWN_SSID }
        if (cleaned != currentSsid) {
            currentSsid = cleaned
            onChanged?.invoke()
        }
    }

    // The SSID is location-gated: both the runtime grant and the global
    // location toggle must be on, or the OS serves "<unknown ssid>".
    fun locationUsable(): Boolean {
        val granted = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) return false
        val lm = context.getSystemService(LocationManager::class.java) ?: return false
        return runCatching { lm.isLocationEnabled }.getOrDefault(false)
    }

    private fun btPermissionGranted(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    private companion object {
        const val UNKNOWN_SSID = "<unknown ssid>"
    }
}
