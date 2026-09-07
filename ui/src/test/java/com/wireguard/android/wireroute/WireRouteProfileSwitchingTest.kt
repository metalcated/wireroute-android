/* SPDX-License-Identifier: Apache-2.0 */
package com.wireguard.android.wireroute

import org.junit.Assert.*
import org.junit.Test

class WireRouteProfileSwitchingTest {
    private val names = setOf("Personal", "Work", "Wired")
    private val policy = WireRouteProfileSwitching(
        enabled = true, defaultProfile = "Personal", cellular = ProfileTarget.profile("Personal"),
        ethernet = ProfileTarget.profile("Wired"), trustedSsids = listOf("Home"),
        assignments = listOf(WifiProfileAssignment("Office", "Work"))
    )

    @Test fun networkAssignmentsChooseDifferentProfiles() {
        assertEquals(ProfileSwitchDecision.Connect("Work"), policy.decide(OnDemandTransport.WIFI, "Office", names))
        assertEquals(ProfileSwitchDecision.Connect("Personal"), policy.decide(OnDemandTransport.CELLULAR, null, names))
        assertEquals(ProfileSwitchDecision.Connect("Wired"), policy.decide(OnDemandTransport.ETHERNET, null, names))
    }

    @Test fun trustedWifiDisconnectsAndOtherWifiUsesDefault() {
        assertEquals(ProfileSwitchDecision.Disconnect, policy.decide(OnDemandTransport.WIFI, "Home", names))
        assertEquals(ProfileSwitchDecision.Connect("Personal"), policy.decide(OnDemandTransport.WIFI, "Cafe", names))
    }

    @Test fun disabledAndNetworkLossNeverDisconnect() {
        OnDemandTransport.entries.forEach { assertTrue(policy.copy(enabled = false).decide(it, "Office", names) is ProfileSwitchDecision.Hold) }
        assertTrue(policy.decide(OnDemandTransport.NONE, null, names) is ProfileSwitchDecision.Hold)
        assertTrue(policy.decide(OnDemandTransport.OTHER, null, names) is ProfileSwitchDecision.Hold)
    }

    @Test fun unavailableSsidNeverFallsBackOrTreatsWifiAsTrusted() {
        assertTrue(policy.decide(OnDemandTransport.WIFI, null, names) is ProfileSwitchDecision.Hold)
        assertTrue(policy.copy(wifi = ProfileTarget.OFF).decide(OnDemandTransport.WIFI, null, names) is ProfileSwitchDecision.Hold)
        assertTrue(policy.copy(trustedSsids = emptyList()).decide(OnDemandTransport.WIFI, null, names) is ProfileSwitchDecision.Hold)
    }

    @Test fun basicTransportRulesDoNotNeedLocation() {
        val basic = policy.copy(trustedSsids = emptyList(), assignments = emptyList())
        assertFalse(basic.needsWifiNames)
        assertEquals(ProfileSwitchDecision.Connect("Personal"), basic.decide(OnDemandTransport.WIFI, null, names))
    }

    @Test fun explicitWifiAssignmentOverridesOffFallback() {
        val specific = policy.copy(wifi = ProfileTarget.OFF)
        assertEquals(ProfileSwitchDecision.Connect("Work"), specific.decide(OnDemandTransport.WIFI, "Office", names))
        assertEquals(ProfileSwitchDecision.Disconnect, specific.decide(OnDemandTransport.WIFI, "Cafe", names))
    }

    @Test fun allTransportActionsSupportOffDefaultAndExplicitProfile() {
        for (transport in listOf(OnDemandTransport.WIFI, OnDemandTransport.CELLULAR, OnDemandTransport.ETHERNET)) {
            for (choice in listOf(ProfileTarget.OFF, ProfileTarget.DEFAULT, ProfileTarget.profile("Work"))) {
                val value = WireRouteProfileSwitching(enabled = true, defaultProfile = "Personal", wifi = choice, cellular = choice, ethernet = choice)
                val expected = when (choice.kind) {
                    ProfileTargetKind.OFF -> ProfileSwitchDecision.Disconnect
                    ProfileTargetKind.DEFAULT -> ProfileSwitchDecision.Connect("Personal")
                    ProfileTargetKind.PROFILE -> ProfileSwitchDecision.Connect("Work")
                }
                assertEquals(expected, value.decide(transport, null, names))
            }
        }
    }

    @Test fun absentDefaultMeansOffNotFirstProfile() {
        assertEquals(ProfileSwitchDecision.Disconnect, WireRouteProfileSwitching(enabled = true).decide(OnDemandTransport.CELLULAR, null, names))
    }

    @Test fun missingAssignmentOrDefaultNeverUsesAnotherProfile() {
        assertTrue(policy.decide(OnDemandTransport.WIFI, "Office", names - "Work") is ProfileSwitchDecision.Hold)
        assertTrue(policy.decide(OnDemandTransport.WIFI, "Cafe", names - "Personal") is ProfileSwitchDecision.Hold)
        assertTrue(policy.decide(OnDemandTransport.ETHERNET, null, names - "Wired") is ProfileSwitchDecision.Hold)
    }

    @Test fun namesAreExactAndCaseSensitive() {
        for (ssid in listOf("office", " Office", "Office ", "Office*", "home")) {
            assertEquals(ProfileSwitchDecision.Connect("Personal"), policy.decide(OnDemandTransport.WIFI, ssid, names))
        }
    }

    @Test fun preventsAmbiguousAssignmentsAndInvalidNames() {
        policy.validate()
        assertThrows(IllegalArgumentException::class.java) { policy.copy(assignments = policy.assignments + WifiProfileAssignment("Office", "Personal")).validate() }
        assertThrows(IllegalArgumentException::class.java) { policy.copy(trustedSsids = listOf("Office")).validate() }
        assertThrows(IllegalArgumentException::class.java) { policy.copy(trustedSsids = listOf("Home", "Home")).validate() }
        assertThrows(IllegalArgumentException::class.java) { policy.copy(trustedSsids = listOf("é".repeat(17))).validate() }
        assertThrows(IllegalArgumentException::class.java) { policy.copy(trustedSsids = List(65) { "Wifi$it" }).validate() }
        assertThrows(IllegalArgumentException::class.java) { policy.copy(assignments = listOf(WifiProfileAssignment("Office", ""))).validate() }
        assertThrows(IllegalArgumentException::class.java) { policy.copy(cellular = ProfileTarget(ProfileTargetKind.PROFILE)).validate() }
    }

    @Test fun handoverGuardRejectsStaleNetworkSettingsAndManualPause() {
        val request = AutomaticProfileSwitch("revision1", 10, "wifi:Home")
        fun permits(enabled: Boolean = true, revision: String = "revision1", generation: Long = 10, pause: String = "") =
            request.permits(enabled, revision, generation, pause, "Work", emptySet(), false, "Personal", true)
        assertTrue(permits())
        assertFalse(permits(enabled = false))
        assertFalse(permits(revision = "revision2"))
        assertFalse(permits(generation = 11))
        assertFalse(permits(pause = "wifi:Home"))
        assertTrue(permits(pause = "cellular:Old"))
    }

    @Test fun handoverGuardNeverStopsManualProfilesOrReplacesAnotherVpn() {
        val request = AutomaticProfileSwitch("r", 1, "network")
        assertTrue(request.permits(true, "r", 1, "", "Work", setOf("Work"), false, "Work", false))
        assertFalse(request.permits(true, "r", 1, "", "", setOf("Work"), false, "Work", false))
        assertFalse(request.permits(true, "r", 1, "", "Work", setOf("Personal"), false, "Work", true))
        assertFalse(request.permits(true, "r", 1, "", "Work", emptySet(), true, "Work", true))
        assertTrue(request.permits(true, "r", 1, "", "", emptySet(), false, "Work", true))
    }

    @Test fun renamePreservesEveryReferenceAndNetworkRule() {
        val same = policy.copy(defaultProfile = "Work", cellular = ProfileTarget.profile("Work"), ethernet = ProfileTarget.profile("Work"), wifi = ProfileTarget.profile("Work"))
        val renamed = same.renamed("Work", "Business")
        assertEquals("Business", renamed.defaultProfile)
        assertEquals(ProfileTarget.profile("Business"), renamed.cellular)
        assertEquals(ProfileTarget.profile("Business"), renamed.ethernet)
        assertEquals(ProfileTarget.profile("Business"), renamed.wifi)
        assertEquals(listOf(WifiProfileAssignment("Office", "Business")), renamed.assignments)
        assertEquals(same.trustedSsids, renamed.trustedSsids)
        assertEquals(same, same.renamed("Unknown", "Other"))
    }
}
