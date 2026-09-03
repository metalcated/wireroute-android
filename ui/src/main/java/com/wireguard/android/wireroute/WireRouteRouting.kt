/* SPDX-License-Identifier: Apache-2.0 */
package com.wireguard.android.wireroute

import com.wireguard.android.model.ObservableTunnel
import com.wireguard.config.Config
import com.wireguard.config.InetNetwork
import com.wireguard.config.Peer
import java.net.Inet4Address
import java.net.Inet6Address

open class WireRouteRoutingException(message: String) : IllegalArgumentException(message)
class WireRouteMissingSplitRoutesException : WireRouteRoutingException(
    "Enter at least one network route before switching this profile to Split tunnel."
)

object WireRouteRouting {
    fun isFullTunnel(config: Config): Boolean = config.peers
        .flatMap { it.allowedIps }
        .any { it.mask == 0 }

    fun mode(tunnel: ObservableTunnel, store: WireRouteStore): String {
        val config = tunnel.config ?: return WireRouteStore.ROUTING_SPLIT
        return store.routingMode(tunnel.name, isFullTunnel(config))
    }

    suspend fun setMode(
        tunnel: ObservableTunnel,
        store: WireRouteStore,
        requestedMode: String,
        enteredSplitRoutes: List<String>? = null
    ) {
        require(requestedMode == WireRouteStore.ROUTING_SPLIT || requestedMode == WireRouteStore.ROUTING_FULL)
        val configuration = tunnel.getConfigAsync()
        val currentMode = store.routingMode(tunnel.name, isFullTunnel(configuration))
        if (currentMode == requestedMode) return

        val savedRoutes = store.splitRoutes(tunnel.name)
        val derivedRoutes = configuration.peers.associate { peer ->
            peer.publicKey.toBase64() to peer.allowedIps.filterNot(::isDefaultRoute).map(InetNetwork::toString)
        }
        var splitRoutes = savedRoutes ?: derivedRoutes
        if (requestedMode == WireRouteStore.ROUTING_SPLIT && splitRoutes.values.flatten().isEmpty()) {
            splitRoutes = enteredSplitRoutes
                ?.takeIf { it.isNotEmpty() }
                ?.let { assignSplitRoutes(configuration, splitRoutes, it) }
                ?: throw WireRouteMissingSplitRoutesException()
        }

        val replacementPeers = when (requestedMode) {
            WireRouteStore.ROUTING_FULL -> makeFullTunnelPeers(configuration, splitRoutes)
            else -> makeSplitTunnelPeers(configuration, splitRoutes)
        }
        val replacement = Config.Builder()
            .setInterface(configuration.`interface`)
            .addPeers(replacementPeers)
            .build()

        tunnel.setConfigAsync(replacement)
        store.saveRouting(tunnel.name, requestedMode, splitRoutes)
    }

    private fun makeFullTunnelPeers(
        configuration: Config,
        splitRoutes: Map<String, List<String>>
    ): List<Peer> {
        if (configuration.peers.isEmpty()) {
            throw WireRouteRoutingException("This profile has no peer to use as the Full tunnel gateway.")
        }
        val defaultRoutePeers = configuration.peers.indices.filter { index ->
            configuration.peers[index].allowedIps.any(::isDefaultRoute)
        }
        val gatewayIndex = when {
            configuration.peers.size == 1 -> 0
            defaultRoutePeers.size == 1 -> defaultRoutePeers.first()
            else -> throw WireRouteRoutingException(
                "Choose a single gateway peer by editing the profile before enabling Full tunnel."
            )
        }

        val addresses = configuration.`interface`.addresses
        val defaults = buildList {
            if (addresses.any { it.address is Inet4Address }) add(InetNetwork.parse("0.0.0.0/0"))
            if (addresses.any { it.address is Inet6Address }) add(InetNetwork.parse("::/0"))
        }
        if (defaults.isEmpty()) {
            throw WireRouteRoutingException("Add an IPv4 or IPv6 interface address before enabling Full tunnel.")
        }

        return configuration.peers.mapIndexed { index, peer ->
            val allowed = if (index == gatewayIndex) defaults else parseRoutes(splitRoutes[peer.publicKey.toBase64()].orEmpty())
            copyPeer(peer, allowed)
        }
    }

    private fun makeSplitTunnelPeers(
        configuration: Config,
        splitRoutes: Map<String, List<String>>
    ): List<Peer> {
        val allRoutes = splitRoutes.values.flatten()
        if (allRoutes.isEmpty()) {
            throw WireRouteMissingSplitRoutesException()
        }
        if (allRoutes.any { isDefaultRoute(InetNetwork.parse(it)) }) {
            throw WireRouteRoutingException("Saved Split tunnel routes cannot contain a default route.")
        }
        return configuration.peers.map { peer ->
            copyPeer(peer, parseRoutes(splitRoutes[peer.publicKey.toBase64()].orEmpty()))
        }
    }

    private fun parseRoutes(routes: List<String>): List<InetNetwork> = routes.map(InetNetwork::parse)

    private fun assignSplitRoutes(
        configuration: Config,
        existingRoutes: Map<String, List<String>>,
        enteredRoutes: List<String>
    ): Map<String, List<String>> {
        val parsed = parseRoutes(enteredRoutes)
        if (parsed.any(::isDefaultRoute)) {
            throw WireRouteRoutingException("Split tunnel routes cannot contain 0.0.0.0/0 or ::/0.")
        }
        val defaultRoutePeers = configuration.peers.filter { peer -> peer.allowedIps.any(::isDefaultRoute) }
        val gateway = when {
            configuration.peers.size == 1 -> configuration.peers.single()
            defaultRoutePeers.size == 1 -> defaultRoutePeers.single()
            else -> throw WireRouteRoutingException(
                "Choose a single gateway peer in the profile editor before adding Split tunnel routes."
            )
        }
        return existingRoutes.toMutableMap().apply {
            put(gateway.publicKey.toBase64(), parsed.map(InetNetwork::toString))
        }
    }

    private fun copyPeer(peer: Peer, allowedIps: Collection<InetNetwork>): Peer {
        val builder = Peer.Builder()
            .setPublicKey(peer.publicKey)
            .addAllowedIps(allowedIps)
        peer.endpoint.ifPresent(builder::setEndpoint)
        peer.persistentKeepalive.ifPresent(builder::setPersistentKeepalive)
        peer.preSharedKey.ifPresent(builder::setPreSharedKey)
        return builder.build()
    }

    private fun isDefaultRoute(network: InetNetwork): Boolean = network.mask == 0
}
