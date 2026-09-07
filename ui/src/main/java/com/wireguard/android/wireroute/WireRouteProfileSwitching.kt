/* SPDX-License-Identifier: Apache-2.0 */
package com.wireguard.android.wireroute

enum class ProfileTargetKind { OFF, DEFAULT, PROFILE }

data class ProfileTarget(val kind: ProfileTargetKind, val profile: String? = null) {
    fun label(): String = when (kind) {
        ProfileTargetKind.OFF -> "VPN off"
        ProfileTargetKind.DEFAULT -> "Use default profile"
        ProfileTargetKind.PROFILE -> profile.orEmpty()
    }

    fun renamed(old: String, new: String) = if (kind == ProfileTargetKind.PROFILE && profile == old) copy(profile = new) else this

    companion object {
        val OFF = ProfileTarget(ProfileTargetKind.OFF)
        val DEFAULT = ProfileTarget(ProfileTargetKind.DEFAULT)
        fun profile(name: String) = ProfileTarget(ProfileTargetKind.PROFILE, name)
    }
}

data class WifiProfileAssignment(val ssid: String, val profile: String)

sealed interface ProfileSwitchDecision {
    data class Connect(val profile: String) : ProfileSwitchDecision
    data object Disconnect : ProfileSwitchDecision
    data class Hold(val reason: String) : ProfileSwitchDecision
}

/** Network selection is deliberately independent of Android and the VPN backend. */
data class WireRouteProfileSwitching(
    val enabled: Boolean = false,
    val defaultProfile: String? = null,
    val wifi: ProfileTarget = ProfileTarget.DEFAULT,
    val cellular: ProfileTarget = ProfileTarget.DEFAULT,
    val ethernet: ProfileTarget = ProfileTarget.DEFAULT,
    val trustedSsids: List<String> = emptyList(),
    val assignments: List<WifiProfileAssignment> = emptyList()
) {
    val needsWifiNames: Boolean get() = trustedSsids.isNotEmpty() || assignments.isNotEmpty()

    fun validate() {
        val names = trustedSsids + assignments.map { it.ssid }
        require(names.size <= 64 && names.all { it.isNotEmpty() && it.toByteArray(Charsets.UTF_8).size <= 32 }) {
            "Enter up to 64 Wi-Fi names, each no longer than 32 UTF-8 bytes."
        }
        require(names.distinct().size == names.size) { "Each Wi-Fi name must appear once, either as trusted or assigned to a profile." }
        require(assignments.all { it.profile.isNotBlank() }) { "Choose a profile for every Wi-Fi assignment." }
        require(defaultProfile == null || defaultProfile.isNotBlank()) { "Choose a default profile or VPN off." }
        require(listOf(wifi, cellular, ethernet).all { it.kind != ProfileTargetKind.PROFILE || !it.profile.isNullOrBlank() }) {
            "Choose a profile for every network action."
        }
    }

    fun decide(transport: OnDemandTransport, ssid: String?, available: Set<String>): ProfileSwitchDecision {
        if (!enabled) return ProfileSwitchDecision.Hold("Automatic profile switching is off.")
        val target = when (transport) {
            OnDemandTransport.NONE -> return ProfileSwitchDecision.Hold("Waiting for a network.")
            OnDemandTransport.OTHER -> return ProfileSwitchDecision.Hold("This network type has no automatic rule.")
            OnDemandTransport.ETHERNET -> ethernet
            OnDemandTransport.CELLULAR -> cellular
            OnDemandTransport.WIFI -> {
                if (needsWifiNames && ssid == null) return ProfileSwitchDecision.Hold("Wi-Fi name unavailable. Check location permission and device Location settings.")
                if (ssid in trustedSsids) return ProfileSwitchDecision.Disconnect
                assignments.firstOrNull { it.ssid == ssid }?.let { ProfileTarget.profile(it.profile) } ?: wifi
            }
        }
        val name = when (target.kind) {
            ProfileTargetKind.OFF -> null
            ProfileTargetKind.DEFAULT -> defaultProfile
            ProfileTargetKind.PROFILE -> target.profile
        } ?: return ProfileSwitchDecision.Disconnect
        return if (name in available) ProfileSwitchDecision.Connect(name)
        else ProfileSwitchDecision.Hold("Assigned profile unavailable: $name. Review automatic profile switching.")
    }

    fun renamed(old: String, new: String) = copy(
        defaultProfile = if (defaultProfile == old) new else defaultProfile,
        wifi = wifi.renamed(old, new), cellular = cellular.renamed(old, new), ethernet = ethernet.renamed(old, new),
        assignments = assignments.map { if (it.profile == old) it.copy(profile = new) else it }
    )
}

/** Rechecked under the tunnel manager's state mutex before each half of a handover. */
data class AutomaticProfileSwitch(val revision: String, val networkGeneration: Long, val networkIdentity: String) {
    fun permits(enabled: Boolean, currentRevision: String, currentGeneration: Long, pausedNetwork: String,
                ownedProfile: String, activeProfiles: Set<String>, foreignVpn: Boolean, target: String, connecting: Boolean): Boolean =
        enabled && revision == currentRevision && networkGeneration == currentGeneration &&
            pausedNetwork != networkIdentity && !foreignVpn &&
            (if (connecting) activeProfiles.all { it == target } else ownedProfile == target)
}
