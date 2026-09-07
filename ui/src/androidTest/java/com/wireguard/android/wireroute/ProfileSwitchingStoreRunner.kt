/* SPDX-License-Identifier: Apache-2.0 */
package com.wireguard.android.wireroute

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import android.content.Intent
import android.net.VpnService
import android.net.wifi.WifiManager
import android.os.Build
import android.os.ParcelFileDescriptor
import com.wireguard.android.Application
import com.wireguard.android.activity.WireRouteActivity
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import com.wireguard.crypto.KeyPair
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayInputStream

/** Real SQLite/JSON checks, isolated from the user's profile database and VPN state. */
class ProfileSwitchingStoreRunner : Instrumentation() {
    private var runtime = false
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); runtime = arguments?.getString("runtime") == "true"; start() }

    override fun onStart() {
        val report = Bundle()
        try {
            val name = "switching-test-${System.nanoTime()}.db"
            val initial = WireRouteStore(targetContext, name)
            check(!initial.profileSwitching().enabled)
            val legacy = WireRouteOnDemandPolicy(enabled = true, wifiRule = OnDemandWifiRule.EXCEPT, ssids = listOf("Home"))
            initial.saveOnDemandPolicy("Personal", legacy)
            initial.close()
            WireRouteStore(targetContext, name).use { store ->
                check(store.enabledOnDemandProfile() == "Personal")
                check(!store.profileSwitching().enabled) // Existing users do not opt in during upgrade.
                val policy = WireRouteProfileSwitching(enabled = true, defaultProfile = "Personal",
                    trustedSsids = listOf("Home"), assignments = listOf(WifiProfileAssignment("Office", "Work")),
                    cellular = ProfileTarget.profile("Personal"), ethernet = ProfileTarget.OFF)
                store.saveProfileSwitching(policy)
                check(store.profileSwitching() == policy)
                check(store.enabledOnDemandProfile() == null)
                check(store.onDemandPolicy("Personal") == legacy.copy(enabled = false))
                val revision = store.profileSwitchSnapshot().second
                store.putSetting("profile_switch_owned", "Work")
                store.manualProfileSwitchOverride("wifi:Office")
                check(store.setting("profile_switch_owned", "") == "")
                check(store.setting("profile_switch_pause", "") == "wifi:Office")
                check(store.profileSwitchSnapshot().second != revision)
                store.renameProfile("Work", "Business")
                check(store.profileSwitching().assignments.single().profile == "Business")
                check(store.setting("profile_switch_pause", "") == "wifi:Office")
                store.removeProfile("Business")
                check(store.profileSwitching().decide(OnDemandTransport.WIFI, "Office", setOf("Personal")) is ProfileSwitchDecision.Hold)
                store.putSetting("profile_switch_owned", "Personal")
                store.saveProfileSwitching(store.profileSwitching().copy(enabled = false))
                check(store.setting("profile_switch_owned", "") == "")
                check(store.enabledOnDemandProfile() == null) // Turning off must not rearm old rules.
                store.saveProfileSwitching(policy)
                store.saveOnDemandPolicy("Personal", legacy)
                check(!store.profileSwitching().enabled)
                check(store.profileSwitching().assignments == policy.assignments)
                check(store.enabledOnDemandProfile() == "Personal")
                // A rejected draft cannot partially disarm a working mode or overwrite settings.
                val before = store.profileSwitchSnapshot()
                check(runCatching { store.saveProfileSwitching(policy.copy(trustedSsids = listOf("Office"))) }.isFailure)
                check(store.profileSwitchSnapshot() == before)
                check(store.enabledOnDemandProfile() == "Personal")
            }
            if (runtime) runBlocking { runtimeChecks() }
            report.putString("stream", "PASS: SQLite persistence, legacy opt-in, mode exclusivity, saved rules, manual pause, rename, missing profiles, disable ownership, invalid-save atomicity.\n" +
                if (runtime) "PASS: emulator Wi-Fi/cellular profile handovers, manual disconnect pause, manual profile protection, disable preserves connection. No working endpoint/handshake tested.\n" else "")
            finish(Activity.RESULT_OK, report)
        } catch (error: Throwable) {
            report.putString("stream", "FAIL: ${error.stackTraceToString()}\n")
            finish(Activity.RESULT_CANCELED, report)
        }
    }

    /** Explicit opt-in emulator integration test. Never runs against a physical device. */
    private suspend fun runtimeChecks() {
        check(Build.HARDWARE in setOf("ranchu", "goldfish")) { "Runtime switching tests are emulator-only." }
        startActivitySync(Intent(targetContext, WireRouteActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val manager = Application.getTunnelManager()
        val store = Application.getWireRouteStore()
        val original = store.profileSwitching()
        check(!original.enabled && store.enabledOnDemandProfile() == null) { "Disable existing automation before this explicit runtime test." }
        check(manager.getTunnels().none { it.state == Tunnel.State.UP }) { "Disconnect existing emulator tunnels first." }
        check(VpnService.prepare(targetContext) == null) { "Grant the emulator app VPN consent before runtime tests." }
        val wifi = targetContext.getSystemService(WifiManager::class.java)
        val wifiWasEnabled = wifi.isWifiEnabled
        fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(uiAutomation.executeShellCommand(command)).use { it.readBytes(); Unit }
        suspend fun awaitState(label: String, check: () -> Boolean) {
            try { withTimeout(30_000) { while (!withContext(Dispatchers.Main) { check() }) delay(250) } }
            catch (error: Exception) { throw AssertionError("Timed out: $label. ${store.setting(WireRouteOnDemandService.STATUS_KEY, "")}", error) }
            sendStatus(0, Bundle().apply { putString("stream", "PASS: $label\n") })
        }
        fun fixture(): Config {
            val keys = KeyPair()
            val peer = KeyPair()
            return Config.parse(ByteArrayInputStream("""
                [Interface]
                PrivateKey = ${keys.privateKey.toBase64()}
                Address = 198.18.0.2/32
                [Peer]
                PublicKey = ${peer.publicKey.toBase64()}
                Endpoint = 127.0.0.1:51899
                AllowedIPs = 198.18.0.0/24
            """.trimIndent().toByteArray()))
        }
        val tunnels = manager.getTunnels()
        val first = tunnels["SwitchTestWiFi"] ?: manager.create("SwitchTestWiFi", fixture())
        val second = tunnels["SwitchTestCell"] ?: manager.create("SwitchTestCell", fixture())
        val policy = WireRouteProfileSwitching(enabled = true, defaultProfile = first.name, cellular = ProfileTarget.profile(second.name))
        try {
            shell("svc wifi enable")
            delay(2500)
            store.saveProfileSwitching(policy)
            withContext(Dispatchers.Main) { WireRouteOnDemandService.sync(targetContext) }
            awaitState("Wi-Fi selects Wi-Fi profile") { first.state == Tunnel.State.UP && second.state == Tunnel.State.DOWN }
            manager.setTunnelState(first, Tunnel.State.DOWN)
            delay(17_000) // Includes a periodic monitor evaluation, not only the immediate state.
            check(first.state == Tunnel.State.DOWN && second.state == Tunnel.State.DOWN) { "Manual disconnect was overridden." }
            sendStatus(0, Bundle().apply { putString("stream", "PASS: manual disconnect stays paused through periodic evaluation\n") })
            shell("svc wifi disable")
            awaitState("Cellular selects cellular profile") { second.state == Tunnel.State.UP && first.state == Tunnel.State.DOWN }
            shell("svc wifi enable")
            awaitState("Return to Wi-Fi switches back") { first.state == Tunnel.State.UP && second.state == Tunnel.State.DOWN }
            manager.setTunnelState(second, Tunnel.State.UP)
            shell("svc wifi disable")
            delay(2500)
            shell("svc wifi enable")
            delay(17_000)
            check(second.state == Tunnel.State.UP && first.state == Tunnel.State.DOWN) { "Manual profile was replaced." }
            store.saveProfileSwitching(policy.copy(enabled = false))
            withContext(Dispatchers.Main) { WireRouteOnDemandService.sync(targetContext) }
            check(second.state == Tunnel.State.UP) { "Disabling automation disconnected a manual profile." }
        } finally {
            store.saveProfileSwitching(original.copy(enabled = false))
            withContext(Dispatchers.Main) { WireRouteOnDemandService.sync(targetContext) }
            if (first.state == Tunnel.State.UP) manager.setTunnelState(first, Tunnel.State.DOWN)
            if (second.state == Tunnel.State.UP) manager.setTunnelState(second, Tunnel.State.DOWN)
            shell(if (wifiWasEnabled) "svc wifi enable" else "svc wifi disable")
        }
    }
}
