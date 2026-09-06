/* SPDX-License-Identifier: Apache-2.0 */
package com.wireguard.android.wireroute

import org.junit.Assert.*
import org.junit.Test

class WireRouteOnDemandPolicyTest {
    private val both = WireRouteOnDemandPolicy(enabled = true)

    @Test fun disabledNeverChangesAConnection() {
        OnDemandTransport.entries.forEach { assertEquals(OnDemandDecision.HOLD, WireRouteOnDemandPolicy().decide(it, "Home")) }
    }

    @Test fun wifiAndCellularAreIndependent() {
        for (wifi in listOf(false, true)) for (cellular in listOf(false, true)) {
            val policy = both.copy(wifi = wifi, cellular = cellular)
            assertEquals(if (wifi) OnDemandDecision.CONNECT else OnDemandDecision.DISCONNECT, policy.decide(OnDemandTransport.WIFI, null))
            assertEquals(if (cellular) OnDemandDecision.CONNECT else OnDemandDecision.DISCONNECT, policy.decide(OnDemandTransport.CELLULAR, null))
        }
    }

    @Test fun onlyNetworksMatchExactly() {
        val policy = both.copy(wifiRule = OnDemandWifiRule.ONLY, ssids = listOf("Home", "Office"))
        assertEquals(OnDemandDecision.CONNECT, policy.decide(OnDemandTransport.WIFI, "Home"))
        assertEquals(OnDemandDecision.CONNECT, policy.decide(OnDemandTransport.WIFI, "Office"))
        assertEquals(OnDemandDecision.DISCONNECT, policy.decide(OnDemandTransport.WIFI, "home"))
        assertEquals(OnDemandDecision.DISCONNECT, policy.decide(OnDemandTransport.WIFI, " Home"))
    }

    @Test fun trustedNetworksDisconnectOnlyOnMatch() {
        val policy = both.copy(wifiRule = OnDemandWifiRule.EXCEPT, ssids = listOf("Home"))
        assertEquals(OnDemandDecision.DISCONNECT, policy.decide(OnDemandTransport.WIFI, "Home"))
        assertEquals(OnDemandDecision.CONNECT, policy.decide(OnDemandTransport.WIFI, "Cafe"))
        assertEquals(OnDemandDecision.CONNECT, policy.decide(OnDemandTransport.CELLULAR, null))
    }

    @Test fun hiddenNetworkNameNeverBecomesAnExceptionMatch() {
        for (rule in listOf(OnDemandWifiRule.ONLY, OnDemandWifiRule.EXCEPT)) {
            assertEquals(OnDemandDecision.HOLD, both.copy(wifiRule = rule, ssids = listOf("Home")).decide(OnDemandTransport.WIFI, null))
        }
        assertEquals(OnDemandDecision.DISCONNECT, both.copy(wifi = false, wifiRule = OnDemandWifiRule.EXCEPT).decide(OnDemandTransport.WIFI, null))
    }

    @Test fun networkLossHoldsExistingConnection() {
        assertEquals(OnDemandDecision.HOLD, both.decide(OnDemandTransport.NONE, null))
        assertEquals(OnDemandDecision.DISCONNECT, both.decide(OnDemandTransport.OTHER, null))
    }

    @Test fun validatesConfigurationWithoutChangingDisabledDrafts() {
        both.validate()
        both.copy(enabled = false, wifi = false, cellular = false).validate()
        assertThrows(IllegalArgumentException::class.java) { both.copy(wifi = false, cellular = false).validate() }
        assertThrows(IllegalArgumentException::class.java) { both.copy(wifiRule = OnDemandWifiRule.ONLY).validate() }
        assertThrows(IllegalArgumentException::class.java) { both.copy(ssids = listOf("é".repeat(17))).validate() }
        assertThrows(IllegalArgumentException::class.java) { both.copy(ssids = List(65) { "Network$it" }).validate() }
    }

    @Test fun summaryFollowsArmedState() {
        assertEquals("Off", WireRouteOnDemandPolicy().summary())
        assertEquals("Off", both.copy(enabled = false, wifiRule = OnDemandWifiRule.EXCEPT).summary())
        assertEquals("Cellular only", both.copy(wifi = false).summary())
        assertEquals("Wi-Fi only · except trusted networks", both.copy(cellular = false, wifiRule = OnDemandWifiRule.EXCEPT).summary())
    }
}
