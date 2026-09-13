/* SPDX-License-Identifier: Apache-2.0 */
package com.wireguard.android.wireroute

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import com.wireguard.android.Application
import com.wireguard.android.R
import com.wireguard.android.activity.WireRouteActivity
import com.wireguard.android.backend.Tunnel
import com.wireguard.android.backend.WgQuickBackend
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/** User-enabled network monitoring. Never changes routes, DNS policies, or another active VPN. */
class WireRouteOnDemandService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val changes = Channel<Unit>(Channel.CONFLATED)
    private val networks = ConcurrentHashMap<Network, NetworkCapabilities>()
    private val connectivity by lazy { getSystemService(ConnectivityManager::class.java) }
    private val store get() = Application.getWireRouteStore()
    private var callback: ConnectivityManager.NetworkCallback? = null
    private var lastStatus = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        try {
            val manager = getSystemService(NotificationManager::class.java)
            if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(NotificationChannel(
                CHANNEL, "On-Demand VPN", NotificationManager.IMPORTANCE_LOW
            ))
            val notification = notification("Checking connection rules…")
            if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED)
            else startForeground(NOTIFICATION_ID, notification)
            val flags = if (Build.VERSION.SDK_INT >= 31) ConnectivityManager.NetworkCallback.FLAG_INCLUDE_LOCATION_INFO else 0
            callback = if (Build.VERSION.SDK_INT >= 31) callbackWithLocation(flags) else callbackLegacy()
            connectivity.registerNetworkCallback(NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build(), callback!!)
            scope.launch {
                for (ignored in changes) {
                    delay(600) // Coalesce Wi-Fi/mobile handovers without cancelling an in-flight VPN change.
                    try { evaluate() }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) {
                        Log.w(TAG, "On-Demand evaluation failed", error)
                        status("Connection failed. Open WireRoute to review the profile; retrying.")
                    }
                }
            }
            scope.launch { while (isActive) { changes.trySend(Unit); delay(15_000) } }
        } catch (error: Exception) {
            store.putSetting(STATUS_KEY, "On-Demand could not start. Open WireRoute and check VPN permission.")
            Log.w(TAG, "On-Demand monitor could not start", error)
            stopSelf()
        }
    }

    @androidx.annotation.RequiresApi(31)
    private fun callbackWithLocation(flags: Int) = object : ConnectivityManager.NetworkCallback(flags) {
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) { update(network, caps) }
        override fun onLost(network: Network) { lost(network) }
    }

    private fun callbackLegacy() = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) { update(network, caps) }
        override fun onLost(network: Network) { lost(network) }
    }

    private fun update(network: Network, caps: NetworkCapabilities) { networks[network] = caps; generation.incrementAndGet(); changes.trySend(Unit) }
    private fun lost(network: Network) { networks.remove(network); generation.incrementAndGet(); changes.trySend(Unit) }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        changes.trySend(Unit)
        return START_STICKY
    }

    private suspend fun evaluate() {
        val switching = withContext(Dispatchers.IO) { store.profileSwitching() }
        if (switching.enabled) { evaluateSwitching(); return }
        val name = withContext(Dispatchers.IO) { store.enabledOnDemandProfile() }
        if (name == null) { status("Off"); stopSelf(); return }
        if (VpnService.prepare(this) != null) {
            status("VPN permission required. Open WireRoute to enable On-Demand.")
            stopSelf(); return
        }
        val manager = Application.getTunnelManager()
        val tunnels = manager.getTunnels().toList()
        val tunnel = tunnels.firstOrNull { it.name == name }
        if (tunnel == null) { status("Profile unavailable. Review On-Demand settings."); return }
        val backend = Application.getBackend()
        if (backend is WgQuickBackend) { status("Restart WireRoute to use On-Demand with the userspace VPN engine."); return }
        val alwaysOn = Build.VERSION.SDK_INT >= 29 && withContext(Dispatchers.IO) {
            runCatching { backend.isAlwaysOn }.getOrDefault(false)
        }
        if (alwaysOn) { status("Android Always-on VPN is enabled. Turn it off to use conditional On-Demand rules."); return }

        val active = connectivity.activeNetwork
        val activeCaps = active?.let(connectivity::getNetworkCapabilities)
        // Android's public default-network API reports our VPN while connected.
        // With no explicit underlying-network override, prefer validated Wi-Fi
        // over cellular, which can remain registered during Wi-Fi handovers.
        val physical = active?.takeIf { networks.containsKey(it) }
        val selected = physical?.let { network -> networks[network]?.let { network to it } } ?: networks.entries
            .sortedByDescending { (_, caps) ->
                (if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) 10 else 0) +
                    (if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) 2 else 1)
            }.firstOrNull()?.let { it.key to it.value }
        if (selected == null) { status("Waiting for a network."); return }
        val (network, caps) = selected
        val transport = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> OnDemandTransport.WIFI
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> OnDemandTransport.CELLULAR
            else -> OnDemandTransport.OTHER
        }
        val policy = withContext(Dispatchers.IO) { store.onDemandPolicy(name) }
        val ssid = if (transport == OnDemandTransport.WIFI && policy.needsWifiNames) wifiName(caps) else null
        val identity = "${network.networkHandle}:${transport.name}:${ssid.orEmpty()}"
        networkIdentity = identity
        if (tunnels.any { it.name != name && it.state == Tunnel.State.UP } ||
            (activeCaps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true && tunnels.none { it.state == Tunnel.State.UP })) {
            status("Another VPN is active. On-Demand will not replace it."); return
        }
        val paused = withContext(Dispatchers.IO) { store.pausedOnDemandNetwork(name) }
        if (paused == identity) { status("Paused after manual disconnect. Resumes on the next network change."); return }
        if (paused != null) withContext(Dispatchers.IO) { store.pauseOnDemand(name, null) }
        when (policy.decide(transport, ssid)) {
            OnDemandDecision.HOLD -> status("Wi-Fi name unavailable. Check location permission and device Location settings.")
            OnDemandDecision.CONNECT -> {
                if (tunnel.state != Tunnel.State.UP) {
                    status("Connecting $name…")
                    withContext(NonCancellable) { manager.setTunnelState(tunnel, Tunnel.State.UP, automatic = true) }
                }
                if (tunnel.state == Tunnel.State.UP) status("$name connected automatically.")
            }
            OnDemandDecision.DISCONNECT -> {
                if (tunnel.state == Tunnel.State.UP) withContext(NonCancellable) { manager.setTunnelState(tunnel, Tunnel.State.DOWN, automatic = true) }
                if (tunnel.state == Tunnel.State.DOWN) status("Waiting for a network that matches $name.")
            }
        }
    }

    private suspend fun evaluateSwitching() {
        val (policy, revision) = store.profileSwitchSnapshot()
        if (!policy.enabled) return
        val observedGeneration = networkGeneration
        if (VpnService.prepare(this) != null) {
            status("VPN permission required. Open WireRoute to enable automatic profile switching.")
            stopSelf(); return
        }
        val backend = Application.getBackend()
        if (backend is WgQuickBackend) { status("Restart WireRoute to use automatic profiles with the userspace VPN engine."); return }
        if (Build.VERSION.SDK_INT >= 29 && withContext(Dispatchers.IO) { runCatching { backend.isAlwaysOn }.getOrDefault(false) }) {
            status("Android Always-on VPN is enabled. Turn it off to use automatic profile switching."); return
        }
        val manager = Application.getTunnelManager()
        val tunnels = manager.getTunnels().toList()
        val active = connectivity.activeNetwork
        val activeCaps = active?.let(connectivity::getNetworkCapabilities)
        val selected = active?.let { network -> networks[network]?.let { network to it } } ?: networks.entries
            .sortedByDescending { (_, caps) ->
                (if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) 10 else 0) + when {
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> 3
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> 2
                    else -> 1
                }
            }.firstOrNull()?.let { it.key to it.value }
        if (selected == null) { status("Waiting for a network."); return }
        val (network, caps) = selected
        val transport = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> OnDemandTransport.ETHERNET
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> OnDemandTransport.WIFI
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> OnDemandTransport.CELLULAR
            else -> OnDemandTransport.OTHER
        }
        val ssid = if (transport == OnDemandTransport.WIFI && policy.needsWifiNames) wifiName(caps) else null
        val identity = "${network.networkHandle}:${transport.name}:${ssid.orEmpty()}"
        networkIdentity = identity
        if (store.setting("profile_switch_pause", "") == identity) {
            status("Paused after manual control. Resumes on the next network change."); return
        }
        val owned = store.setting("profile_switch_owned", "")
        val running = tunnels.filter { it.state == Tunnel.State.UP }
        if (running.any { it.name != owned } || (running.isEmpty() && activeCaps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true)) {
            status("A manually connected profile or another VPN is active. Automatic switching will not replace it."); return
        }
        val desired = when (val decision = policy.decide(transport, ssid, tunnels.map { it.name }.toSet())) {
            is ProfileSwitchDecision.Hold -> { status(decision.reason); return }
            is ProfileSwitchDecision.Connect -> tunnels.first { it.name == decision.profile }
            ProfileSwitchDecision.Disconnect -> null
        }
        // Validate the destination before bringing down a working connection.
        desired?.getConfigAsync()
        val request = AutomaticProfileSwitch(revision, observedGeneration, identity)
        val previous = running.firstOrNull()
        if (previous != null && previous != desired) {
            status("Switching network profile…")
            withContext(NonCancellable) { manager.setTunnelState(previous, Tunnel.State.DOWN, automatic = true, switchRequest = request) }
            if (previous.state != Tunnel.State.DOWN) return
        }
        if (desired != null && desired.state != Tunnel.State.UP) {
            status("Connecting ${desired.name} automatically…")
            withContext(NonCancellable) { manager.setTunnelState(desired, Tunnel.State.UP, automatic = true, switchRequest = request) }
        }
        if (store.setting("profile_switch_revision", "") != revision || networkGeneration != observedGeneration) return
        status(when {
            desired == null -> "VPN off for this network. Automatic profile switching is watching."
            desired.state == Tunnel.State.UP -> "${desired.name} connected automatically."
            else -> "Waiting to apply automatic profile rules."
        })
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun wifiName(caps: NetworkCapabilities): String? {
        if (!hasWifiNameAccess(this) || !LocationManagerCompat.isLocationEnabled(getSystemService(LocationManager::class.java))) return null
        val info = if (Build.VERSION.SDK_INT >= 29) caps.transportInfo as? WifiInfo else null
        val raw = (info ?: applicationContext.getSystemService(WifiManager::class.java)?.connectionInfo)?.ssid
        return raw?.takeUnless { it == WifiManager.UNKNOWN_SSID || it.isEmpty() }?.removeSurrounding("\"")
    }

    private fun notification(message: String) = NotificationCompat.Builder(this, CHANNEL)
        .setSmallIcon(R.drawable.ic_wireroute_status).setContentTitle("WireRoute On-Demand")
        .setContentText(message).setStyle(NotificationCompat.BigTextStyle().bigText(message))
        .setOnlyAlertOnce(true).setOngoing(true)
        .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, WireRouteActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)).build()

    private fun status(message: String) {
        if (!scope.isActive || lastStatus == message) return
        lastStatus = message
        store.putSetting(STATUS_KEY, message)
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(message))
    }

    override fun onDestroy() {
        callback?.let { runCatching { connectivity.unregisterNetworkCallback(it) } }
        scope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    class RestartReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action !in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED)) return
            sync(context)
        }
    }

    companion object {
        private const val TAG = "WireRoute/OnDemand"
        private const val CHANNEL = "wireroute-on-demand"
        private const val NOTIFICATION_ID = 5201
        const val STATUS_KEY = "on_demand_status"
        private val generation = java.util.concurrent.atomic.AtomicLong()
        val networkGeneration: Long get() = generation.get()
        @Volatile var networkIdentity: String = "none"
            private set

        fun hasWifiNameAccess(context: Context): Boolean =
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED &&
                (Build.VERSION.SDK_INT < 29 || ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED)

        fun sync(context: Context) {
            val store = Application.getWireRouteStore()
            val intent = Intent(context, WireRouteOnDemandService::class.java)
            if (!store.profileSwitching().enabled && store.enabledOnDemandProfile() == null) { context.stopService(intent); return }
            if (VpnService.prepare(context) != null) {
                store.putSetting(STATUS_KEY, "Open WireRoute to grant VPN permission."); return
            }
            try { ContextCompat.startForegroundService(context, intent) }
            catch (error: Exception) {
                store.putSetting(STATUS_KEY, "Open WireRoute to resume On-Demand; Android prevented a background start.")
                Log.w(TAG, "On-Demand start deferred", error)
            }
        }
    }
}
