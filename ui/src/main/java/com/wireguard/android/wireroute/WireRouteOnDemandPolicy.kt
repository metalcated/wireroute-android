/* SPDX-License-Identifier: Apache-2.0 */
package com.wireguard.android.wireroute

enum class OnDemandWifiRule { ANY, ONLY, EXCEPT }
enum class OnDemandTransport { NONE, WIFI, CELLULAR, OTHER }
enum class OnDemandDecision { CONNECT, DISCONNECT, HOLD }

data class WireRouteOnDemandPolicy(
    val enabled: Boolean = false,
    val wifi: Boolean = true,
    val cellular: Boolean = true,
    val wifiRule: OnDemandWifiRule = OnDemandWifiRule.ANY,
    val ssids: List<String> = emptyList()
) {
    val needsWifiNames: Boolean get() = wifi && wifiRule != OnDemandWifiRule.ANY

    fun validate() {
        require(!enabled || wifi || cellular) { "Choose Wi-Fi, cellular, or both." }
        require(!enabled || !needsWifiNames || ssids.isNotEmpty()) { "Enter at least one Wi-Fi network name." }
        require(ssids.size <= 64 && ssids.all { it.isNotEmpty() && it.toByteArray(Charsets.UTF_8).size <= 32 }) {
            "Enter up to 64 Wi-Fi names, each no longer than 32 UTF-8 bytes."
        }
    }

    fun decide(transport: OnDemandTransport, ssid: String?): OnDemandDecision {
        if (!enabled || transport == OnDemandTransport.NONE) return OnDemandDecision.HOLD
        val connect = when (transport) {
            OnDemandTransport.WIFI -> {
                if (!wifi) false
                else if (wifiRule == OnDemandWifiRule.ANY) true
                else {
                    // Redacted SSIDs must never be interpreted as a trusted/untrusted match.
                    if (ssid == null) return OnDemandDecision.HOLD
                    if (wifiRule == OnDemandWifiRule.ONLY) ssid in ssids else ssid !in ssids
                }
            }
            OnDemandTransport.CELLULAR -> cellular
            else -> false
        }
        return if (connect) OnDemandDecision.CONNECT else OnDemandDecision.DISCONNECT
    }

    fun summary(): String = if (!enabled) "Off" else when {
        wifi && cellular -> "Wi-Fi and cellular"
        wifi -> "Wi-Fi only"
        else -> "Cellular only"
    } + when {
        !needsWifiNames -> ""
        wifiRule == OnDemandWifiRule.ONLY -> " · selected networks"
        else -> " · except trusted networks"
    }
}
