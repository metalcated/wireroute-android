/* SPDX-License-Identifier: Apache-2.0 */
package com.wireguard.android.activity

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.text.format.DateUtils
import android.text.format.Formatter
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.zxing.BarcodeFormat
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.wireguard.android.Application
import com.wireguard.android.BuildConfig
import com.wireguard.android.R
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.android.model.ObservableTunnel
import com.wireguard.android.util.ErrorMessages
import com.wireguard.android.util.TunnelImporter
import com.wireguard.android.wireroute.WireRouteActivityPoint
import com.wireguard.android.wireroute.WireRouteActivitySession
import com.wireguard.android.wireroute.WireRouteEndpointLocation
import com.wireguard.android.wireroute.WireRouteEndpointLocator
import com.wireguard.android.wireroute.WireRouteGlyphView
import com.wireguard.android.wireroute.WireRouteIcon
import com.wireguard.android.wireroute.WireRouteIconView
import com.wireguard.android.wireroute.WireRoutePalette
import com.wireguard.android.wireroute.WireRouteRouting
import com.wireguard.android.wireroute.WireRouteMissingSplitRoutesException
import com.wireguard.android.wireroute.WireRouteStore
import com.wireguard.android.wireroute.WireRouteTrafficChartView
import com.wireguard.android.wireroute.alphaColor
import com.wireguard.android.wireroute.roundedBackground
import com.wireguard.android.wireroute.roundedTypeface
import com.wireguard.config.Config
import com.wireguard.config.InetEndpoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.maplibre.android.MapLibre
import org.maplibre.android.annotations.Marker
import org.maplibre.android.annotations.MarkerOptions
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.annotations.IconFactory
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.roundToLong

/** iPhone-parity WireRoute shell backed by the existing Android WireGuard engine. */
class WireRouteActivity : AppCompatActivity() {
    private enum class Tab { HOME, PROFILES, ACTIVITY, SETTINGS }
    private data class InitialSettings(
        val selectedProfile: String?,
        val appearance: String,
        val retentionDays: Int,
        val privacyAcknowledged: Boolean
    )

    private lateinit var store: WireRouteStore
    private lateinit var palette: WireRoutePalette
    private lateinit var root: FrameLayout
    private lateinit var contentHost: FrameLayout
    private lateinit var tabBar: LinearLayout
    private var selectedTab = Tab.HOME
    private var showingProfileDetail = false
    private var selectedTunnel: ObservableTunnel? = null
    private var tunnels: List<ObservableTunnel> = emptyList()
    private var selectedProfileName: String? = null
    private var selectedProfileLoaded = false
    private var appearanceMode = WireRouteStore.APPEARANCE_NORDIC
    private var activityRetentionDays = 7
    private var routingModes: Map<String, String> = emptyMap()
    private val pendingTunnels = mutableSetOf<String>()
    private var refreshJob: Job? = null
    private var lastTunnelFingerprint = ""
    private var statusInset = 0
    private var navigationInset = 0
    private var permissionTunnel: ObservableTunnel? = null
    private var mapView: MapView? = null
    private var map: MapLibreMap? = null
    private var mapStarted = false
    private var mapResumed = false
    private var mapMarker: Marker? = null
    private var mapDetailed = false
    private var endpointLocationApproved = false
    private var endpointLocation: WireRouteEndpointLocation? = null
    private var endpointKey: String? = null
    private var mapLocationLabel: TextView? = null
    private var mapLocateButton: LinearLayout? = null
    private var activityChart: WireRouteTrafficChartView? = null
    private var activityDownload: TextView? = null
    private var activityUpload: TextView? = null
    private var activityTotal: TextView? = null
    private var activityHandshake: TextView? = null
    private var activityHistory: LinearLayout? = null
    private var exportPayload: List<Pair<String, Config>> = emptyList()
    private val backCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            if (showingProfileDetail) {
                showingProfileDetail = false
                selectedTab = Tab.PROFILES
                render()
            } else {
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
            }
        }
    }

    private val vpnPermissionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        permissionTunnel?.let(::setTunnelStateAfterPermission)
        permissionTunnel = null
    }

    private val importLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@registerForActivityResult
        lifecycleScope.launch {
            TunnelImporter.importTunnel(contentResolver, uri) { message ->
                if (message.isNotBlank()) showMessage(message)
            }
            refreshTunnels(forceRender = true)
        }
    }

    private val qrLauncher = registerForActivityResult(ScanContract()) { result ->
        val contents = result.contents ?: return@registerForActivityResult
        TunnelImporter.importTunnel(supportFragmentManager, contents) { message ->
            if (message.isNotBlank()) showMessage(message)
        }
    }

    private val exportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        uri ?: return@registerForActivityResult
        val payload = exportPayload
        exportPayload = emptyList()
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching {
                contentResolver.openOutputStream(uri, "w")!!.use { stream ->
                    ZipOutputStream(stream).use { zip ->
                        payload.forEach { (name, config) ->
                            zip.putNextEntry(ZipEntry("$name.conf"))
                            zip.write(config.toWgQuickString().toByteArray(StandardCharsets.UTF_8))
                            zip.closeEntry()
                        }
                    }
                }
            }.onSuccess { withContext(Dispatchers.Main) { showMessage("WireRoute profiles exported.") } }
                .onFailure { withContext(Dispatchers.Main) { showError(it) } }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Application.getWireRouteStore()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        onBackPressedDispatcher.addCallback(this, backCallback)
        lifecycleScope.launch {
            val settings = withContext(Dispatchers.IO) {
                InitialSettings(
                    selectedProfile = store.selectedProfile(),
                    appearance = store.appearance(),
                    retentionDays = store.retentionDays(),
                    privacyAcknowledged = store.setting("vpn_privacy_acknowledged", "false") == "true"
                )
            }
            selectedProfileName = settings.selectedProfile
            selectedProfileLoaded = true
            appearanceMode = settings.appearance
            activityRetentionDays = settings.retentionDays
            palette = WireRoutePalette.resolve(this@WireRouteActivity, appearanceMode)
            window.statusBarColor = palette.background
            window.navigationBarColor = palette.background
            WindowCompat.getInsetsController(window, window.decorView).apply {
                isAppearanceLightStatusBars = palette.label == Color.rgb(0x1C, 0x1C, 0x1E)
                isAppearanceLightNavigationBars = isAppearanceLightStatusBars
            }
            MapLibre.getInstance(this@WireRouteActivity)
            buildRoot()
            refreshTunnels(forceRender = true)
            if (!settings.privacyAcknowledged) showPrivacyDisclosure()
        }
    }

    override fun onPostResume() {
        super.onPostResume()
        lifecycleScope.launch { refreshTunnels(forceRender = true) }
    }

    override fun onStart() {
        super.onStart()
        startMapIfNeeded()
        refreshJob = lifecycleScope.launch {
            while (isActive) {
                refreshTunnels(forceRender = false)
                if (selectedTab == Tab.ACTIVITY) updateActivityPanel()
                delay(1_000)
            }
        }
    }

    override fun onResume() { super.onResume(); resumeMapIfNeeded() }
    override fun onPause() {
        if (mapResumed) {
            mapView?.onPause()
            mapResumed = false
        }
        super.onPause()
    }
    override fun onStop() {
        refreshJob?.cancel()
        refreshJob = null
        if (mapStarted) {
            mapView?.onStop()
            mapStarted = false
        }
        super.onStop()
    }
    override fun onLowMemory() { super.onLowMemory(); mapView?.onLowMemory() }
    override fun onDestroy() { mapView?.onDestroy(); super.onDestroy() }
    override fun onSaveInstanceState(outState: Bundle) { super.onSaveInstanceState(outState); mapView?.onSaveInstanceState(outState) }

    private fun buildRoot() {
        root = FrameLayout(this).apply { setBackgroundColor(palette.background) }
        contentHost = FrameLayout(this)
        tabBar = LinearLayout(this)
        root.addView(contentHost, frameMatch())
        root.addView(tabBar)
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            if (statusInset != bars.top || navigationInset != bars.bottom) {
                statusInset = bars.top
                navigationInset = bars.bottom
                render()
            }
            insets
        }
        render()
    }

    private suspend fun refreshTunnels(forceRender: Boolean) {
        val updated = Application.getTunnelManager().getTunnels().toList()
        if (!selectedProfileLoaded) {
            selectedProfileName = withContext(Dispatchers.IO) { store.selectedProfile() }
            selectedProfileLoaded = true
        }
        routingModes = withContext(Dispatchers.IO) {
            updated.associate { tunnel ->
                tunnel.name to store.routingMode(
                    tunnel.name,
                    tunnel.config?.let(WireRouteRouting::isFullTunnel) == true
                )
            }
        }
        selectedTunnel = selectedTunnel?.let { current -> updated.firstOrNull { it.name == current.name } }
            ?: updated.firstOrNull { it.state == Tunnel.State.UP }
            ?: selectedProfileName?.let { saved -> updated.firstOrNull { it.name == saved } }
            ?: updated.firstOrNull()
        selectedTunnel?.let {
            if (selectedProfileName != it.name) {
                selectedProfileName = it.name
                withContext(Dispatchers.IO) { store.setSelectedProfile(it.name) }
            }
        }
        tunnels = updated
        val fingerprint = updated.joinToString("|") {
            "${it.name}:${it.state}:${it.config?.hashCode() ?: 0}:${pendingTunnels.contains(it.name)}"
        }
        if (forceRender || fingerprint != lastTunnelFingerprint) {
            lastTunnelFingerprint = fingerprint
            render()
        }
    }

    private fun selectTunnel(tunnel: ObservableTunnel) {
        selectedTunnel = tunnel
        selectedProfileName = tunnel.name
        lifecycleScope.launch(Dispatchers.IO) { store.setSelectedProfile(tunnel.name) }
        endpointLocation = null
        endpointKey = null
        render()
    }

    private fun render() {
        if (!::contentHost.isInitialized) return
        contentHost.removeAllViews()
        activityChart = null
        activityDownload = null
        activityUpload = null
        activityTotal = null
        activityHandshake = null
        activityHistory = null
        if (showingProfileDetail) renderProfileDetail() else when (selectedTab) {
            Tab.HOME -> renderHome()
            Tab.PROFILES -> renderProfiles()
            Tab.ACTIVITY -> renderActivity()
            Tab.SETTINGS -> renderSettings()
        }
        renderTabBar()
    }

    private fun renderHome() {
        val (scroll, column) = scrollColumn(20)
        column.addView(horizontalRow(
            text("WireRoute", 30f, palette.label, bold = true),
            spacer(),
            statusPill(selectedTunnel)
        ), matchWrap().bottom(18))

        val tunnel = selectedTunnel
        if (tunnel == null) {
            val empty = vertical().apply {
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(22), dp(72), dp(22), dp(22))
                addView(icon(WireRouteIcon.ROUTE, palette.signalBlue, 56), fixed(64, 64))
                addView(text("No profiles yet", 26f, palette.label, true, Gravity.CENTER), matchWrap().top(18))
                addView(text("Add, import, or scan a WireGuard profile to get connected.", 17f, palette.secondaryLabel, gravity = Gravity.CENTER), matchWrap().top(10))
                addView(primaryButton("Add profile", WireRouteIcon.PLUS) { showAddProfileDialog() }, matchWrap().top(24))
            }
            column.addView(empty, matchWrap())
            contentHost.addView(scroll, frameMatch())
            return
        }

        column.addView(homeMap(tunnel), matchFixed(365).bottom(16))
        column.addView(homeConnectionCard(tunnel), matchWrap().bottom(26))
        contentHost.addView(scroll, frameMatch())
    }

    private fun homeMap(tunnel: ObservableTunnel): View {
        val container = FrameLayout(this).apply {
            background = roundedBackground(palette.card, dp(28).toFloat(), alphaColor(palette.border, 0.65f), dp(1))
            clipToOutline = true
            elevation = dp(5).toFloat()
        }
        val mapView = ensureMapView()
        (mapView.parent as? ViewGroup)?.removeView(mapView)
        container.addView(mapView, frameMatch())

        mapLocationLabel = text("Your endpoint can appear here", 15f, Color.WHITE, bold = true, gravity = Gravity.CENTER).apply {
            setPadding(dp(16), dp(6), dp(16), dp(6))
        }
        container.addView(mapLocationLabel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(52), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(12) })
        val style = iconButton(WireRouteIcon.LAYERS, palette.label, palette.raised, 44) { toggleMapStyle() }
        container.addView(style, FrameLayout.LayoutParams(dp(44), dp(44), Gravity.TOP or Gravity.END).apply { topMargin = dp(14); marginEnd = dp(14) })
        val locate = capsuleButton("Locate endpoint", WireRouteIcon.LOCATION, Color.WHITE, alphaColor(palette.signalBlue, 0.86f)) { locateEndpoint(tunnel) }
        mapLocateButton = locate
        container.addView(locate, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(16) })
        refreshEndpointDisplay(tunnel)
        return container
    }

    private fun homeConnectionCard(tunnel: ObservableTunnel): View {
        val card = card(elevated = true)
        val profileName = horizontalRow(
            text(tunnel.name, 20f, palette.label),
            icon(WireRouteIcon.CHEVRON_DOWN, palette.label, 20),
            spacer(),
            iconButton(WireRouteIcon.SLIDERS, palette.signalBlue, alphaColor(palette.signalBlue, 0.12f), 42) {
                showProfileDetail(tunnel)
            }
        ).apply { setOnClickListener { showProfilePicker() } }
        card.addView(profileName, matchWrap())
        val config = tunnel.config
        val endpoint = config?.peers?.firstNotNullOfOrNull { it.endpoint.orElse(null) }
        card.addView(text("Endpoint", 13f, palette.secondaryLabel), matchWrap().top(24))
        card.addView(text(endpoint?.toString() ?: "Not configured", 15f, palette.secondaryLabel, monospace = true), matchWrap().top(4))
        val mode = routingMode(tunnel)
        val summary = horizontalRow(
            summaryBlock("Routing", if (mode == WireRouteStore.ROUTING_FULL) "Full tunnel" else "Split tunnel"),
            summaryBlock("DNS Protection", if (config?.`interface`?.dnsServers?.isEmpty() != false) "Not configured" else "Profile DNS")
        )
        card.addView(summary, matchWrap().top(22))
        card.addView(connectionButton(tunnel), matchFixed(58).top(22))
        return card
    }

    private fun renderProfiles() {
        val (scroll, column) = scrollColumn(16)
        val add = iconButton(WireRouteIcon.PLUS, palette.label, palette.inset, 50) { showAddProfileDialog() }
        column.addView(horizontalRow(spacer(), add), matchWrap().bottom(22))
        column.addView(text("Connect a profile, review its routing, or open it to manage the full configuration.", 18f, palette.secondaryLabel), matchWrap().horizontal(4).bottom(24))
        column.addView(horizontalRow(
            text("My profiles", 24f, palette.label, true),
            spacer(),
            icon(WireRouteIcon.ROUTE, palette.signalBlue, 25)
        ), matchWrap().horizontal(4).bottom(10))
        if (tunnels.isEmpty()) {
            column.addView(text("No profiles yet. Add, import, or scan a profile to get connected.", 17f, palette.secondaryLabel, gravity = Gravity.CENTER), matchWrap().top(50))
        } else {
            tunnels.forEach { tunnel -> column.addView(profileCard(tunnel), matchFixed(122).vertical(7)) }
        }
        contentHost.addView(scroll, frameMatch())
    }

    private fun profileCard(tunnel: ObservableTunnel): View {
        val active = tunnel.state == Tunnel.State.UP
        val pending = pendingTunnels.contains(tunnel.name)
        val mode = routingMode(tunnel)
        val stateColor = when { active -> palette.liveTeal; pending -> palette.warningAmber; else -> palette.secondaryLabel }
        val outer = FrameLayout(this).apply {
            background = roundedBackground(palette.card, dp(20).toFloat(), alphaColor(if (active) palette.liveTeal else palette.border, 0.72f), dp(1))
            elevation = dp(7).toFloat()
            isClickable = true
            setOnClickListener { showProfileDetail(tunnel) }
            setOnLongClickListener { showProfileActions(tunnel); true }
        }
        outer.addView(View(this).apply { background = roundedBackground(stateColor, dp(2).toFloat()) }, FrameLayout.LayoutParams(dp(4), dp(76), Gravity.START or Gravity.CENTER_VERTICAL))
        val glyph = WireRouteGlyphView(this).apply {
            fullTunnel = mode == WireRouteStore.ROUTING_FULL
            this.stateColor = if (active) palette.liveTeal else palette.signalBlue
            inactive = !active
            background = roundedBackground(alphaColor(this.stateColor, 0.14f), dp(12).toFloat())
        }
        outer.addView(glyph, FrameLayout.LayoutParams(dp(54), dp(54), Gravity.START or Gravity.CENTER_VERTICAL).apply { marginStart = dp(18) })
        val labels = vertical().apply {
            addView(text(tunnel.name, 18f, palette.label, true), matchWrap())
            addView(text(if (pending) "Changing…" else if (active) "Active" else "Inactive", 16f, stateColor), matchWrap().top(3))
            addView(modePill(mode), wrapFixed(27).top(7))
        }
        outer.addView(labels, FrameLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL).apply { marginStart = dp(86); marginEnd = dp(86); width = dp(205) })
        outer.addView(iconButton(WireRouteIcon.POWER, if (active) palette.liveTeal else palette.secondaryLabel, palette.raised, 54) { toggleTunnel(tunnel) }, FrameLayout.LayoutParams(dp(54), dp(54), Gravity.END or Gravity.CENTER_VERTICAL).apply { marginEnd = dp(18) })
        return outer
    }

    private fun showProfileDetail(tunnel: ObservableTunnel) {
        selectTunnel(tunnel)
        selectedTab = Tab.PROFILES
        showingProfileDetail = true
        render()
    }

    private fun renderProfileDetail() {
        val tunnel = selectedTunnel ?: run { showingProfileDetail = false; renderProfiles(); return }
        val (scroll, column) = scrollColumn(20)
        val top = horizontalRow(
            iconButton(WireRouteIcon.BACK, palette.label, palette.inset, 50) { onBackPressedDispatcher.onBackPressed() },
            spacer(),
            text(tunnel.name, 21f, palette.label, bold = true, gravity = Gravity.CENTER),
            spacer(),
            textButton("Edit") { editTunnel(tunnel) }
        )
        column.addView(top, matchWrap().bottom(44))
        column.addView(detailHero(tunnel), matchFixed(130).bottom(38))
        column.addView(actionCard(WireRouteIcon.ACTIVITY, "Activity", "Live traffic and connection history") {
            showingProfileDetail = false; selectedTab = Tab.ACTIVITY; render()
        }, matchFixed(104).bottom(38))
        column.addView(routingCard(tunnel), matchWrap().bottom(38))
        column.addView(actionCard(WireRouteIcon.DNS, "DNS Protection", dnsDetail(tunnel)) { showDNSProtection(tunnel) }, matchFixed(104))
        column.addView(text("Use the DNS servers saved in this WireGuard profile.", 15f, palette.secondaryLabel), matchWrap().horizontal(20).top(10).bottom(30))
        column.addView(text("Interface", 16f, palette.secondaryLabel, true), matchWrap().horizontal(20).bottom(10))
        column.addView(interfaceCard(tunnel), matchWrap().bottom(34))
        contentHost.addView(scroll, frameMatch())
    }

    private fun detailHero(tunnel: ObservableTunnel): View {
        val active = tunnel.state == Tunnel.State.UP
        val mode = routingMode(tunnel)
        val outer = FrameLayout(this).apply { background = roundedBackground(palette.raised, dp(24).toFloat()); elevation = dp(8).toFloat() }
        val glyph = WireRouteGlyphView(this).apply {
            fullTunnel = mode == WireRouteStore.ROUTING_FULL
            stateColor = if (active) palette.liveTeal else palette.signalBlue
            inactive = !active
            background = roundedBackground(alphaColor(stateColor, 0.15f), dp(14).toFloat())
        }
        outer.addView(glyph, FrameLayout.LayoutParams(dp(64), dp(64), Gravity.START or Gravity.CENTER_VERTICAL).apply { marginStart = dp(26) })
        val labels = vertical().apply {
            addView(text(if (active) "Active" else "Inactive", 26f, if (active) palette.liveTeal else palette.secondaryLabel, true), matchWrap())
            addView(modePill(mode), wrapFixed(28).top(9))
        }
        outer.addView(labels, FrameLayout.LayoutParams(dp(190), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL).apply { marginStart = dp(106) })
        outer.addView(iconButton(WireRouteIcon.POWER, if (active) palette.liveTeal else palette.secondaryLabel, palette.inset, 62) { toggleTunnel(tunnel) }, FrameLayout.LayoutParams(dp(62), dp(62), Gravity.END or Gravity.CENTER_VERTICAL).apply { marginEnd = dp(26) })
        return outer
    }

    private fun routingCard(tunnel: ObservableTunnel): View {
        val mode = routingMode(tunnel)
        return card().apply {
            addView(horizontalRow(iconTile(WireRouteIcon.ROUTE), text("Routing", 20f, palette.label, true).apply { setPadding(dp(14), 0, 0, 0) }), matchWrap())
            addView(text(
                if (mode == WireRouteStore.ROUTING_FULL) "Full tunnel sends all supported network traffic through the VPN."
                else "Split tunnel sends only the profile’s saved AllowedIPs through the VPN.",
                16f, palette.secondaryLabel
            ), matchWrap().top(18))
            val segments = LinearLayout(this@WireRouteActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                background = roundedBackground(palette.tertiaryLabel, dp(22).toFloat())
                setPadding(dp(2), dp(2), dp(2), dp(2))
            }
            segments.addView(segment("Split tunnel", mode == WireRouteStore.ROUTING_SPLIT) { setRoutingMode(tunnel, WireRouteStore.ROUTING_SPLIT) }, weighted(40, 1f))
            segments.addView(segment("Full tunnel", mode == WireRouteStore.ROUTING_FULL) { setRoutingMode(tunnel, WireRouteStore.ROUTING_FULL) }, weighted(40, 1f))
            addView(segments, matchFixed(44).top(17))
        }
    }

    private fun interfaceCard(tunnel: ObservableTunnel): View {
        val config = tunnel.config
        val interfaceConfig = config?.`interface`
        val publicKey = interfaceConfig?.keyPair?.publicKey?.toBase64() ?: "Unavailable"
        return card().apply {
            addView(keyValue("Addresses", interfaceConfig?.addresses?.joinToString(", ") ?: "Not configured"), matchWrap())
            addView(divider(), matchFixed(1).vertical(14))
            addView(keyValue("DNS servers", interfaceConfig?.dnsServers?.joinToString(", ") { it.hostAddress ?: it.toString() } ?: "Not configured"), matchWrap())
            addView(divider(), matchFixed(1).vertical(14))
            addView(keyValue("Public key", publicKey, monospace = true), matchWrap())
        }
    }

    private fun renderActivity() {
        val (scroll, column) = scrollColumn(20)
        column.addView(horizontalRow(
            text("Activity", 38f, palette.label, true), spacer(),
            iconButton(WireRouteIcon.MORE, palette.label, palette.inset, 50) { showActivityMenu() }
        ), matchWrap().bottom(24))
        val tunnel = selectedTunnel
        if (tunnel == null) {
            column.addView(text("Add a profile to see live transfer rates and connection history.", 18f, palette.secondaryLabel, gravity = Gravity.CENTER), matchWrap().top(60))
            contentHost.addView(scroll, frameMatch())
            return
        }
        column.addView(profileSelector(tunnel), matchFixed(54).bottom(24))
        column.addView(text("See live transfer rates and recent connection history for this profile. Activity stays on this device.", 18f, palette.secondaryLabel), matchWrap().bottom(14))
        column.addView(text(
            if (tunnel.state == Tunnel.State.UP) "Recording this connection in the private WireRoute app database."
            else "This profile is inactive. Previous connections remain available below.",
            15f, palette.secondaryLabel
        ), matchWrap().bottom(22))
        val chartCard = card(padding = 18).apply {
            activityChart = WireRouteTrafficChartView(this@WireRouteActivity).apply {
                palette = this@WireRouteActivity.palette
                setPadding(dp(8), dp(10), dp(8), dp(10))
            }
            addView(activityChart, matchFixed(220))
            addView(horizontalRow(legendDot(palette.signalBlue), text("Download", 16f, palette.secondaryLabel), fixedSpacer(22), legendDot(palette.liveTeal), text("Upload", 16f, palette.secondaryLabel)), matchWrap().top(12))
        }
        column.addView(chartCard, matchWrap().bottom(16))
        val metrics = vertical()
        val firstRow = horizontalRow(
            metricCard("Download", palette.signalBlue).also { activityDownload = it.getChildAt(1) as TextView },
            fixedSpacer(14),
            metricCard("Upload", palette.liveTeal).also { activityUpload = it.getChildAt(1) as TextView }
        )
        val secondRow = horizontalRow(
            metricCard("Session total", palette.secondaryLabel).also { activityTotal = it.getChildAt(1) as TextView },
            fixedSpacer(14),
            metricCard("Last handshake", palette.secondaryLabel).also { activityHandshake = it.getChildAt(1) as TextView }
        )
        metrics.addView(firstRow, matchFixed(92))
        metrics.addView(secondRow, matchFixed(92).top(14))
        column.addView(metrics, matchWrap().bottom(24))
        column.addView(text("Recent connections", 22f, palette.label, true), matchWrap().bottom(14))
        activityHistory = vertical().apply { background = roundedBackground(palette.card, dp(20).toFloat(), alphaColor(palette.border, 0.65f), dp(1)) }
        column.addView(activityHistory, matchWrap().bottom(26))
        contentHost.addView(scroll, frameMatch())
        lifecycleScope.launch { updateActivityPanel() }
    }

    private suspend fun updateActivityPanel() {
        val tunnel = selectedTunnel ?: return
        val (points, sessions) = withContext(Dispatchers.IO) {
            store.activityPoints(tunnel.name) to store.sessions(tunnel.name, 8)
        }
        if (selectedTunnel?.name != tunnel.name || selectedTab != Tab.ACTIVITY) return
        activityChart?.points = points
        val latest = points.lastOrNull()
        activityDownload?.text = formatRate(latest?.receivedBytesPerSecond ?: 0.0)
        activityUpload?.text = formatRate(latest?.sentBytesPerSecond ?: 0.0)
        val current = sessions.firstOrNull { it.endedAt == null }
        activityTotal?.text = current?.let { Formatter.formatShortFileSize(this, it.receivedBytes + it.sentBytes) } ?: "—"
        activityHandshake?.text = current?.lastHandshake?.let {
            DateUtils.getRelativeTimeSpanString(it, System.currentTimeMillis(), DateUtils.SECOND_IN_MILLIS).toString()
        } ?: "—"
        activityHistory?.let { history ->
            history.removeAllViews()
            val completed = sessions.filter { it.endedAt != null }
            if (completed.isEmpty()) {
                history.addView(text("No connection history yet.", 15f, palette.secondaryLabel, gravity = Gravity.CENTER).apply { setPadding(dp(18), dp(18), dp(18), dp(18)) }, matchWrap())
            } else completed.forEachIndexed { index, session ->
                if (index > 0) history.addView(divider(), matchFixed(1).horizontal(18))
                history.addView(historyRow(session), matchFixed(74))
            }
        }
    }

    private fun renderSettings() {
        val (scroll, column) = scrollColumn(20)
        column.addView(text("Settings", 22f, palette.label, true, Gravity.CENTER), matchWrap().bottom(28))
        column.addView(horizontalRow(
            icon(WireRouteIcon.SHIELD, palette.signalBlue, 26),
            text("Manage how WireRoute looks, stores local activity, and handles diagnostics and support.", 18f, palette.secondaryLabel).apply { setPadding(dp(14), 0, 0, 0) }
        ), matchWrap().bottom(48))
        column.addView(sectionTitle("About"), matchWrap().horizontal(20).bottom(10))
        val about = settingsGroup()
        about.addView(settingsRow(WireRouteIcon.APP, "WireRoute for Android", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"), matchFixed(78))
        about.addView(insetDivider())
        about.addView(settingsRow(WireRouteIcon.ENGINE, "WireRoute VPN Engine", "Loading…").also { row ->
            lifecycleScope.launch { (row.getChildAt(1) as LinearLayout).let { labels -> (labels.getChildAt(1) as TextView).text = runCatching { Application.getBackend().version }.getOrDefault("Unknown") } }
        }, matchFixed(78))
        column.addView(about, matchWrap().bottom(46))

        column.addView(sectionTitle("Preferences"), matchWrap().horizontal(20).bottom(10))
        val preferences = settingsGroup()
        preferences.addView(settingsRow(WireRouteIcon.PALETTE, "Appearance", if (appearanceMode == WireRouteStore.APPEARANCE_NORDIC) "Nordic Blue" else "System", WireRouteIcon.CHEVRON_DOWN) { chooseAppearance() }, matchFixed(78))
        preferences.addView(insetDivider())
        preferences.addView(settingsRow(WireRouteIcon.HISTORY, "Activity retention", retentionTitle(activityRetentionDays), WireRouteIcon.CHEVRON_DOWN) { chooseRetention() }, matchFixed(78))
        column.addView(preferences, matchWrap().bottom(46))

        column.addView(sectionTitle("Data and diagnostics"), matchWrap().horizontal(20).bottom(10))
        val data = settingsGroup()
        data.addView(settingsRow(WireRouteIcon.EXPORT, "Export zip archive", "Save all profiles in a portable zip archive.", WireRouteIcon.CHEVRON_RIGHT) { requestExport(tunnels) }, matchFixed(92))
        data.addView(insetDivider())
        data.addView(settingsRow(WireRouteIcon.LOG, "View log", "Review app and tunnel diagnostics.", WireRouteIcon.CHEVRON_RIGHT) { startActivity(Intent(this, LogViewerActivity::class.java)) }, matchFixed(82))
        column.addView(data, matchWrap().bottom(46))

        column.addView(sectionTitle("Help and policies"), matchWrap().horizontal(20).bottom(10))
        val help = settingsGroup()
        val documents = listOf(
            Triple("Support", "Setup guidance and ways to get help.", "SUPPORT.md"),
            Triple("Privacy", "See what WireRoute stores and never uploads.", "PRIVACY.md"),
            Triple("Security", "Review security practices and report vulnerabilities.", "SECURITY.md"),
            Triple("Legal and open-source notices", "Licenses, notices, and project terms.", "LEGAL.md"),
            Triple("RouterOS setup", "Configure secure RouterOS access and peer management.", "ROUTEROS_SETUP.md")
        )
        documents.forEachIndexed { index, (title, detail, path) ->
            if (index > 0) help.addView(insetDivider())
            help.addView(settingsRow(WireRouteIcon.SHIELD, title, detail, WireRouteIcon.CHEVRON_RIGHT) { showDocument(title, path) }, matchFixed(84))
        }
        column.addView(help, matchWrap().bottom(34))
        contentHost.addView(scroll, frameMatch())
    }

    private fun renderTabBar() {
        if (showingProfileDetail) {
            tabBar.visibility = View.GONE
            return
        }
        tabBar.visibility = View.VISIBLE
        tabBar.removeAllViews()
        tabBar.orientation = LinearLayout.HORIZONTAL
        tabBar.gravity = Gravity.CENTER
        tabBar.setPadding(dp(4), dp(4), dp(4), dp(4))
        tabBar.background = roundedBackground(palette.sidebar, dp(36).toFloat(), alphaColor(palette.signalBlue, 0.35f), dp(1))
        val activeTab = if (showingProfileDetail) Tab.PROFILES else selectedTab
        listOf(
            Triple(Tab.HOME, "Home", WireRouteIcon.HOME),
            Triple(Tab.PROFILES, "Profiles", WireRouteIcon.PROFILES),
            Triple(Tab.ACTIVITY, "Activity", WireRouteIcon.ACTIVITY),
            Triple(Tab.SETTINGS, "Settings", WireRouteIcon.SETTINGS)
        ).forEach { (tab, title, icon) ->
            val selected = tab == activeTab
            val item = vertical().apply {
                gravity = Gravity.CENTER
                if (selected) background = roundedBackground(alphaColor(palette.secondaryLabel, 0.35f), dp(31).toFloat())
                addView(icon(icon, if (selected) palette.signalBlue else palette.label, 29), fixed(32, 32))
                addView(text(title, 12f, if (selected) palette.signalBlue else palette.label, selected, Gravity.CENTER), matchWrap().top(1))
                setOnClickListener {
                    showingProfileDetail = false
                    selectedTab = tab
                    render()
                }
            }
            tabBar.addView(item, weighted(66, 1f))
        }
        tabBar.layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(74), Gravity.BOTTOM).apply {
            marginStart = dp(20); marginEnd = dp(20); bottomMargin = navigationInset + dp(8)
        }
        tabBar.bringToFront()
    }

    private fun toggleTunnel(tunnel: ObservableTunnel) {
        if (pendingTunnels.contains(tunnel.name)) return
        lifecycleScope.launch {
            val turningOn = tunnel.state != Tunnel.State.UP
            if (turningOn && Application.getBackend() is GoBackend) {
                try {
                    val intent = GoBackend.VpnService.prepare(this@WireRouteActivity)
                    if (intent != null) {
                        permissionTunnel = tunnel
                        vpnPermissionLauncher.launch(intent)
                        return@launch
                    }
                } catch (error: Throwable) {
                    showError(error)
                    return@launch
                }
            }
            setTunnelStateAfterPermission(tunnel)
        }
    }

    private fun setTunnelStateAfterPermission(tunnel: ObservableTunnel) {
        lifecycleScope.launch {
            pendingTunnels += tunnel.name
            render()
            runCatching { tunnel.setStateAsync(if (tunnel.state == Tunnel.State.UP) Tunnel.State.DOWN else Tunnel.State.UP) }
                .onFailure(::showError)
            pendingTunnels -= tunnel.name
            refreshTunnels(forceRender = true)
        }
    }

    private fun setRoutingMode(tunnel: ObservableTunnel, mode: String) {
        lifecycleScope.launch {
            runCatching { withContext(Dispatchers.IO) { WireRouteRouting.setMode(tunnel, store, mode) } }
                .onFailure { error ->
                    if (error is WireRouteMissingSplitRoutesException) showSplitRoutesDialog(tunnel) else showError(error)
                }
            refreshTunnels(forceRender = true)
        }
    }

    private fun showSplitRoutesDialog(tunnel: ObservableTunnel) {
        val field = EditText(this).apply {
            hint = "10.0.0.0/8, 192.168.0.0/16"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            typeface = android.graphics.Typeface.MONOSPACE
            minLines = 3
            setTextColor(palette.label)
            setHintTextColor(palette.tertiaryLabel)
        }
        val body = vertical().apply {
            setPadding(dp(24), dp(6), dp(24), 0)
            addView(text(
                "Enter the IPv4 or IPv6 networks that should use this VPN. Separate routes with commas or new lines.",
                15f,
                palette.secondaryLabel
            ), matchWrap().bottom(14))
            addView(field, matchWrap())
        }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("Split tunnel routes")
            .setView(body)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save and use", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val routes = field.text.toString().split(Regex("[,\\n]"))
                    .map(String::trim)
                    .filter(String::isNotEmpty)
                if (routes.isEmpty()) {
                    field.error = "Enter at least one network route."
                    return@setOnClickListener
                }
                lifecycleScope.launch {
                    runCatching {
                        withContext(Dispatchers.IO) {
                            WireRouteRouting.setMode(tunnel, store, WireRouteStore.ROUTING_SPLIT, routes)
                        }
                    }.onSuccess {
                        dialog.dismiss()
                        refreshTunnels(forceRender = true)
                    }.onFailure { error ->
                        field.error = error.message ?: "These routes could not be saved."
                    }
                }
            }
        }
        dialog.show()
    }

    private fun showAddProfileDialog() {
        val choices = arrayOf("Create from scratch", "Import from file or archive", "Scan QR code")
        MaterialAlertDialogBuilder(this).setTitle("Add profile").setItems(choices) { _, which ->
            when (which) {
                0 -> startActivity(Intent(this, TunnelCreatorActivity::class.java))
                1 -> importLauncher.launch(arrayOf("application/zip", "text/plain", "application/octet-stream", "application/x-wireguard-profile"))
                2 -> qrLauncher.launch(ScanOptions().apply {
                    setPrompt("Scan a WireGuard profile QR code")
                    setBeepEnabled(false)
                    setOrientationLocked(false)
                    setDesiredBarcodeFormats(BarcodeFormat.QR_CODE.toString())
                })
            }
        }.setNegativeButton("Cancel", null).show()
    }

    private fun showProfilePicker() {
        if (tunnels.isEmpty()) return
        val names = tunnels.map { it.name }.toTypedArray()
        val checked = tunnels.indexOfFirst { it.name == selectedTunnel?.name }
        MaterialAlertDialogBuilder(this).setTitle("Choose a profile")
            .setSingleChoiceItems(names, checked) { dialog, which -> selectTunnel(tunnels[which]); dialog.dismiss() }
            .setNegativeButton("Cancel", null).show()
    }

    private fun showProfileActions(tunnel: ObservableTunnel) {
        MaterialAlertDialogBuilder(this).setTitle(tunnel.name)
            .setItems(arrayOf("Open profile", "Edit", "Export", "Delete")) { _, which ->
                when (which) {
                    0 -> showProfileDetail(tunnel)
                    1 -> editTunnel(tunnel)
                    2 -> requestExport(listOf(tunnel))
                    3 -> confirmDelete(tunnel)
                }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun confirmDelete(tunnel: ObservableTunnel) {
        MaterialAlertDialogBuilder(this).setTitle("Delete profile?")
            .setMessage("Delete “${tunnel.name}” from this device? This cannot be undone.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ ->
                lifecycleScope.launch {
                    runCatching { tunnel.deleteAsync() }.onFailure(::showError)
                    refreshTunnels(forceRender = true)
                }
            }.show()
    }

    private fun editTunnel(tunnel: ObservableTunnel) {
        startActivity(Intent(this, TunnelCreatorActivity::class.java).putExtra("selected_tunnel", tunnel.name))
    }

    private fun requestExport(requested: List<ObservableTunnel>) {
        if (requested.isEmpty()) { showMessage("There are no profiles to export."); return }
        MaterialAlertDialogBuilder(this).setTitle("Export private profiles?")
            .setMessage("The archive contains private WireGuard keys. Store and share it securely.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Export") { _, _ ->
                lifecycleScope.launch {
                    runCatching { requested.map { it.name to it.getConfigAsync() } }
                        .onSuccess { exportPayload = it; exportLauncher.launch("wireroute-export.zip") }
                        .onFailure(::showError)
                }
            }.show()
    }

    private fun showDNSProtection(tunnel: ObservableTunnel) {
        val config = tunnel.config
        val servers = config?.`interface`?.dnsServers?.joinToString(", ") { it.hostAddress ?: it.toString() }.orEmpty()
        val domains = config?.`interface`?.dnsSearchDomains?.joinToString(", ").orEmpty()
        val message = buildString {
            append("Use the DNS servers saved in this WireGuard profile.\n\n")
            append("Configured DNS servers\n")
            append(servers.ifBlank { "No DNS servers are configured." })
            if (domains.isNotBlank()) append("\n\nSearch domains\n$domains")
        }
        MaterialAlertDialogBuilder(this).setTitle("DNS Protection")
            .setMessage(message)
            .setNegativeButton("Done", null)
            .setPositiveButton("Edit Profile DNS") { _, _ -> editTunnel(tunnel) }
            .show()
    }

    private fun showActivityMenu() {
        val tunnel = selectedTunnel ?: return
        MaterialAlertDialogBuilder(this).setTitle("Activity")
            .setItems(arrayOf("Keep history: ${retentionTitle(activityRetentionDays)}", "Clear previous activity…")) { _, which ->
                if (which == 0) chooseRetention() else MaterialAlertDialogBuilder(this)
                    .setTitle("Clear previous activity?")
                    .setMessage("WireRoute will remove completed connection history for this profile. The current connection will continue recording.")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Clear Activity") { _, _ ->
                        lifecycleScope.launch {
                            withContext(Dispatchers.IO) { store.clearCompletedActivity(tunnel.name) }
                            updateActivityPanel()
                        }
                    }
                    .show()
            }.setNegativeButton("Cancel", null).show()
    }

    private fun chooseRetention() {
        val values = intArrayOf(1, 7, 30)
        val labels = values.map(::retentionTitle).toTypedArray()
        val selected = values.indexOf(activityRetentionDays)
        MaterialAlertDialogBuilder(this).setTitle("Activity retention")
            .setSingleChoiceItems(labels, selected) { dialog, which ->
                activityRetentionDays = values[which]
                dialog.dismiss()
                lifecycleScope.launch { withContext(Dispatchers.IO) { store.setRetentionDays(activityRetentionDays) } }
                render()
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun chooseAppearance() {
        val choices = arrayOf("Nordic Blue", "System")
        val selected = if (appearanceMode == WireRouteStore.APPEARANCE_NORDIC) 0 else 1
        MaterialAlertDialogBuilder(this).setTitle("Appearance")
            .setSingleChoiceItems(choices, selected) { dialog, which ->
                dialog.dismiss()
                appearanceMode = if (which == 0) WireRouteStore.APPEARANCE_NORDIC else WireRouteStore.APPEARANCE_SYSTEM
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { store.setAppearance(appearanceMode) }
                    recreate()
                }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun showPrivacyDisclosure() {
        MaterialAlertDialogBuilder(this)
            .setTitle("VPN Privacy")
            .setMessage("WireRoute does not collect, inspect, sell, or share VPN traffic or browsing activity. Profiles, activity history, and diagnostic logs remain on this device unless you explicitly export them. When connected, encrypted traffic goes directly to the WireGuard endpoint and DNS service configured in the selected profile.")
            .setPositiveButton("Continue") { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) { store.putSetting("vpn_privacy_acknowledged", "true") }
            }
            .setCancelable(false)
            .show()
    }

    private fun showDocument(title: String, path: String) {
        val webView = WebView(this).apply {
            settings.javaScriptEnabled = false
            settings.domStorageEnabled = false
            setBackgroundColor(palette.background)
            loadUrl("${getString(R.string.wireroute_help_base_url).trimEnd('/')}/$path")
        }
        MaterialAlertDialogBuilder(this).setTitle(title).setView(webView).setPositiveButton("Done", null).show()
    }

    private fun locateEndpoint(tunnel: ObservableTunnel) {
        val endpoint = tunnel.config?.peers?.firstNotNullOfOrNull { it.endpoint.orElse(null) } ?: return
        if (!endpointLocationApproved) {
            MaterialAlertDialogBuilder(this).setTitle("Show approximate endpoint location?")
                .setMessage("WireRoute will send the selected profile’s public endpoint IP address to ipwho.is for an approximate city, state or region, and country. The result is kept only for this app session. The map uses OpenFreeMap.")
                .setNegativeButton("Not now", null)
                .setPositiveButton("Continue") { _, _ -> endpointLocationApproved = true; performEndpointLookup(endpoint) }
                .show()
        } else if (endpointLocation != null) {
            centerMap(endpointLocation!!)
        } else performEndpointLookup(endpoint)
    }

    private fun performEndpointLookup(endpoint: InetEndpoint) {
        mapLocationLabel?.text = "Finding the approximate endpoint location…"
        setLocateButtonText("Locating…")
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching { WireRouteEndpointLocator.locate(endpoint, getString(R.string.wireroute_endpoint_location_url)) }
                .onSuccess { location -> withContext(Dispatchers.Main) {
                    endpointLocation = location
                    mapLocationLabel?.text = "${location.displayName.ifBlank { "Endpoint" }}\nApproximate endpoint location"
                    setLocateButtonText("Recenter")
                    centerMap(location)
                } }
                .onFailure { error -> withContext(Dispatchers.Main) {
                    mapLocationLabel?.text = error.message ?: "The endpoint location could not be determined."
                    setLocateButtonText("Try again")
                } }
        }
    }

    private fun refreshEndpointDisplay(tunnel: ObservableTunnel) {
        val endpoint = tunnel.config?.peers?.firstNotNullOfOrNull { it.endpoint.orElse(null) }
        val key = endpoint?.toString()
        if (endpointKey != key) {
            endpointKey = key
            endpointLocation = null
            mapMarker?.remove(); mapMarker = null
            showWorld()
        }
        mapLocationLabel?.text = if (endpoint == null) "Your endpoint can appear here" else "World view · endpoint not located"
        mapLocateButton?.alpha = if (endpoint == null) 0.45f else 1f
        mapLocateButton?.isEnabled = endpoint != null
    }

    private fun ensureMapView(): MapView {
        mapView?.let { return it }
        return MapView(this).also { view ->
            mapView = view
            view.onCreate(null)
            view.getMapAsync { ready ->
                map = ready
                ready.uiSettings.apply {
                    isCompassEnabled = false
                    isRotateGesturesEnabled = false
                    isTiltGesturesEnabled = false
                }
                applyMapStyle()
            }
            // Initial settings are loaded asynchronously, so this view is normally created
            // after Activity.onResume(). Wait until it is attached before forwarding the
            // lifecycle; starting a detached SurfaceView leaves MapLibre permanently blank.
            view.post {
                startMapIfNeeded()
                resumeMapIfNeeded()
            }
        }
    }

    private fun startMapIfNeeded() {
        val view = mapView ?: return
        if (!mapStarted && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            view.onStart()
            mapStarted = true
        }
    }

    private fun resumeMapIfNeeded() {
        val view = mapView ?: return
        startMapIfNeeded()
        if (!mapResumed && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            view.onResume()
            mapResumed = true
        }
    }

    private fun toggleMapStyle() { mapDetailed = !mapDetailed; applyMapStyle() }

    private fun applyMapStyle() {
        val ready = map ?: return
        val builder = if (mapDetailed) {
            Style.Builder().fromUri(getString(R.string.wireroute_map_detailed_style_url))
        } else {
            val style = assets.open("wireroute_nordic_map.json").bufferedReader().use { it.readText() }
                .replace("__WIREROUTE_TILEJSON_URL__", getString(R.string.wireroute_map_tilejson_url))
                .replace("__WIREROUTE_SPRITE_URL__", getString(R.string.wireroute_map_sprite_url))
                .replace("__WIREROUTE_GLYPHS_URL__", getString(R.string.wireroute_map_glyphs_url))
            Style.Builder().fromJson(style)
        }
        ready.setStyle(builder) {
            endpointLocation?.let(::centerMap) ?: showWorld()
        }
    }

    private fun showWorld() {
        map?.cameraPosition = CameraPosition.Builder().target(LatLng(18.0, 0.0)).zoom(0.35).build()
    }

    private fun centerMap(location: WireRouteEndpointLocation) {
        val ready = map ?: return
        mapMarker?.remove()
        mapMarker = ready.addMarker(
            MarkerOptions().position(LatLng(location.latitude, location.longitude))
                .icon(IconFactory.getInstance(this).fromBitmap(markerBitmap()))
                .title("Approximate endpoint location")
        )
        ready.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(location.latitude, location.longitude), 5.6))
    }

    private fun markerBitmap(): Bitmap {
        val size = dp(42)
        return Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also { bitmap ->
            val canvas = Canvas(bitmap)
            val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (selectedTunnel?.state == Tunnel.State.UP) palette.liveTeal else palette.signalBlue }
            val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = dp(3).toFloat() }
            canvas.drawCircle(size / 2f, size / 2f, size / 2f - dp(2), fill)
            canvas.drawCircle(size / 2f, size / 2f, size / 2f - dp(2), stroke)
        }
    }

    private fun statusPill(tunnel: ObservableTunnel?): View {
        val active = tunnel?.state == Tunnel.State.UP
        return horizontalRow(
            icon(if (active) WireRouteIcon.LOCKED else WireRouteIcon.LOCK_OPEN, if (active) palette.liveTeal else palette.secondaryLabel, 19),
            text(if (active) "Active" else "Inactive", 15f, palette.label, true).apply { setPadding(dp(7), 0, 0, 0) }
        ).apply {
            gravity = Gravity.CENTER
            setPadding(dp(13), dp(8), dp(13), dp(8))
            background = roundedBackground(palette.card, dp(20).toFloat(), alphaColor(palette.border, 0.7f), dp(1))
        }
    }

    private fun profileSelector(tunnel: ObservableTunnel): View = horizontalRow(
        text(tunnel.name, 18f, palette.label), spacer(), icon(WireRouteIcon.CHEVRON_DOWN, palette.label, 22)
    ).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(18), 0, dp(18), 0)
        background = roundedBackground(palette.inset, dp(14).toFloat())
        setOnClickListener { showProfilePicker() }
    }

    private fun connectionButton(tunnel: ObservableTunnel): View {
        val active = tunnel.state == Tunnel.State.UP
        return horizontalRow(
            icon(WireRouteIcon.POWER, if (active) palette.liveTeal else Color.WHITE, 27),
            text(if (active) "Disconnect" else "Connect", 20f, if (active) palette.liveTeal else Color.WHITE).apply { setPadding(dp(10), 0, 0, 0) }
        ).apply {
            gravity = Gravity.CENTER
            background = roundedBackground(if (active) palette.raised else palette.signalBlue, dp(15).toFloat())
            setOnClickListener { toggleTunnel(tunnel) }
        }
    }

    private fun modePill(mode: String): View = horizontalRow(
        icon(if (mode == WireRouteStore.ROUTING_FULL) WireRouteIcon.GLOBE else WireRouteIcon.ROUTE, palette.signalBlue, 14),
        text(if (mode == WireRouteStore.ROUTING_FULL) "Full tunnel" else "Split tunnel", 13f, palette.secondaryLabel).apply { setPadding(dp(6), 0, 0, 0) }
    ).apply {
        gravity = Gravity.CENTER
        setPadding(dp(9), dp(3), dp(9), dp(3))
        background = roundedBackground(palette.inset, dp(12).toFloat(), alphaColor(palette.border, 0.55f), dp(1))
    }

    private fun actionCard(icon: WireRouteIcon, title: String, detail: String, action: () -> Unit): View = horizontalRow(
        iconTile(icon),
        vertical().apply {
            setPadding(dp(14), 0, 0, 0)
            addView(text(title, 20f, palette.label, true), matchWrap())
            addView(text(detail, 16f, palette.secondaryLabel), matchWrap().top(4))
        },
        spacer(), icon(WireRouteIcon.CHEVRON_RIGHT, palette.tertiaryLabel, 22)
    ).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(22), dp(18), dp(22), dp(18))
        background = roundedBackground(palette.card, dp(22).toFloat())
        elevation = dp(6).toFloat()
        setOnClickListener { action() }
    }

    private fun segment(title: String, selected: Boolean, action: () -> Unit): TextView = text(title, 16f, Color.WHITE, selected, Gravity.CENTER).apply {
        if (selected) background = roundedBackground(palette.signalBlue, dp(20).toFloat())
        setOnClickListener { action() }
    }

    private fun settingsGroup(): LinearLayout = vertical().apply { background = roundedBackground(palette.card, dp(22).toFloat()); clipToOutline = true; elevation = dp(5).toFloat() }

    private fun settingsRow(icon: WireRouteIcon, title: String, detail: String, trailing: WireRouteIcon? = null, action: (() -> Unit)? = null): LinearLayout {
        val row = horizontalRow(
            iconTile(icon),
            vertical().apply {
                setPadding(dp(14), 0, 0, 0)
                addView(text(title, 17f, palette.label, true), matchWrap())
                addView(text(detail, 16f, palette.secondaryLabel), matchWrap().top(3))
            },
            spacer()
        )
        if (trailing != null) row.addView(icon(trailing, palette.tertiaryLabel, 20), fixed(22, 22))
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(dp(8), dp(8), dp(18), dp(8))
        if (action != null) row.setOnClickListener { action() }
        return row
    }

    private fun metricCard(title: String, color: Int): LinearLayout = vertical().apply {
        setPadding(dp(14), dp(12), dp(14), dp(10))
        background = roundedBackground(palette.card, dp(18).toFloat(), alphaColor(palette.border, 0.65f), dp(1))
        addView(text(title, 14f, color, true), matchWrap())
        addView(text(if (title == "Download" || title == "Upload") "Zero KB/s" else "—", 22f, palette.label, true), matchWrap().top(7))
    }

    private fun historyRow(session: WireRouteActivitySession): View {
        val formatter = DateTimeFormatter.ofPattern("MMM d, h:mm a").withZone(ZoneId.systemDefault())
        val duration = session.endedAt?.let { formatDuration(it - session.startedAt) } ?: "Active"
        return horizontalRow(
            vertical().apply {
                addView(text(formatter.format(Instant.ofEpochMilli(session.startedAt)), 15f, palette.label), matchWrap())
                addView(text("↓ ${Formatter.formatShortFileSize(this@WireRouteActivity, session.receivedBytes)}   ↑ ${Formatter.formatShortFileSize(this@WireRouteActivity, session.sentBytes)}", 13f, palette.secondaryLabel, monospace = true), matchWrap().top(4))
            }, spacer(), text(duration, 14f, palette.secondaryLabel)
        ).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(18), dp(12), dp(18), dp(12)) }
    }

    private fun keyValue(key: String, value: String, monospace: Boolean = false): View = vertical().apply {
        addView(text(key, 13f, palette.secondaryLabel), matchWrap())
        addView(text(value, 15f, palette.label, monospace = monospace), matchWrap().top(4))
    }

    private fun summaryBlock(title: String, value: String): View = vertical().apply {
        addView(text(title, 13f, palette.secondaryLabel), matchWrap())
        addView(text(value, 16f, palette.label), matchWrap().top(5))
    }.also { it.layoutParams = weighted(ViewGroup.LayoutParams.WRAP_CONTENT, 1f) }

    private fun iconTile(icon: WireRouteIcon): FrameLayout = FrameLayout(this).apply {
        background = roundedBackground(alphaColor(palette.signalBlue, 0.14f), dp(12).toFloat())
        addView(icon(icon, palette.signalBlue, 24), FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER))
        layoutParams = fixed(52, 52)
    }

    private fun sectionTitle(title: String): TextView = text(title, 16f, palette.secondaryLabel, true)

    private fun card(elevated: Boolean = false, padding: Int = 20): LinearLayout = vertical().apply {
        setPadding(dp(padding), dp(padding), dp(padding), dp(padding))
        background = roundedBackground(if (elevated) palette.raised else palette.card, dp(22).toFloat(), alphaColor(palette.border, 0.62f), dp(1))
        elevation = dp(if (elevated) 10 else 6).toFloat()
    }

    private fun primaryButton(title: String, icon: WireRouteIcon, action: () -> Unit): View = capsuleButton(title, icon, Color.WHITE, palette.signalBlue, action)

    private fun capsuleButton(title: String, icon: WireRouteIcon, textColor: Int, backgroundColor: Int, action: () -> Unit): LinearLayout = horizontalRow(
        icon(icon, textColor, 24), text(title, 17f, textColor).apply { setPadding(dp(10), 0, 0, 0) }
    ).apply {
        gravity = Gravity.CENTER
        setPadding(dp(17), dp(8), dp(17), dp(8))
        background = roundedBackground(backgroundColor, dp(24).toFloat())
        setOnClickListener { action() }
    }

    private fun textButton(title: String, action: () -> Unit): TextView = text(title, 19f, palette.label, gravity = Gravity.CENTER).apply {
        setPadding(dp(18), 0, dp(18), 0)
        background = roundedBackground(palette.inset, dp(25).toFloat(), alphaColor(palette.signalBlue, 0.45f), dp(1))
        layoutParams = fixed(72, 50)
        setOnClickListener { action() }
    }

    private fun iconButton(icon: WireRouteIcon, tint: Int, backgroundColor: Int, size: Int, action: () -> Unit): FrameLayout = FrameLayout(this).apply {
        background = roundedBackground(backgroundColor, dp(size / 2).toFloat(), alphaColor(palette.border, 0.72f), dp(1))
        addView(icon(icon, tint, (size * 0.52f).roundToLong().toInt()), FrameLayout.LayoutParams(dp((size * 0.52f).toInt()), dp((size * 0.52f).toInt()), Gravity.CENTER))
        layoutParams = fixed(size, size)
        setOnClickListener { action() }
    }

    private fun icon(icon: WireRouteIcon, tint: Int, size: Int): WireRouteIconView = WireRouteIconView(this).apply {
        this.icon = icon
        this.tint = tint
        contentDescription = icon.name.lowercase().replace('_', ' ')
        layoutParams = fixed(size, size)
    }

    private fun text(value: String, size: Float, color: Int, bold: Boolean = false, gravity: Int = Gravity.START, monospace: Boolean = false): TextView = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        this.gravity = gravity
        includeFontPadding = false
        typeface = if (monospace) android.graphics.Typeface.MONOSPACE else roundedTypeface(if (bold) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
    }

    private fun vertical(): LinearLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun horizontalRow(vararg views: View): LinearLayout = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; views.forEach(::addView) }
    private fun spacer(): View = View(this).apply { layoutParams = weighted(0, 1f) }
    private fun fixedSpacer(width: Int): View = View(this).apply { layoutParams = fixed(width, 1) }
    private fun divider(): View = View(this).apply { setBackgroundColor(alphaColor(palette.border, 0.55f)) }
    private fun insetDivider(): View = divider().apply { layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply { marginStart = dp(70); marginEnd = dp(20) } }
    private fun legendDot(color: Int): View = View(this).apply { background = roundedBackground(color, dp(5).toFloat()); layoutParams = fixed(10, 10).apply { marginEnd = dp(8) } }

    private fun scrollColumn(horizontalPadding: Int): Pair<ScrollView, LinearLayout> {
        val scroll = ScrollView(this).apply { isFillViewport = true; clipToPadding = false; setPadding(0, statusInset + dp(16), 0, navigationInset + dp(104)) }
        val column = vertical().apply { setPadding(dp(horizontalPadding), 0, dp(horizontalPadding), 0) }
        scroll.addView(column, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return scroll to column
    }

    private fun frameMatch() = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    private fun matchWrap() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    private fun matchFixed(height: Int) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(height))
    private fun wrapFixed(height: Int) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(height))
    private fun fixed(width: Int, height: Int) = LinearLayout.LayoutParams(dp(width), dp(height))
    private fun weighted(height: Int, weight: Float) = LinearLayout.LayoutParams(0, if (height < 0) height else dp(height), weight)
    private fun LinearLayout.LayoutParams.top(value: Int) = apply { topMargin = dp(value) }
    private fun LinearLayout.LayoutParams.bottom(value: Int) = apply { bottomMargin = dp(value) }
    private fun LinearLayout.LayoutParams.vertical(value: Int) = apply { topMargin = dp(value); bottomMargin = dp(value) }
    private fun LinearLayout.LayoutParams.horizontal(value: Int) = apply { marginStart = dp(value); marginEnd = dp(value) }
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToLong().toInt()

    private fun routingMode(tunnel: ObservableTunnel): String {
        val config = tunnel.config ?: return WireRouteStore.ROUTING_SPLIT
        return routingModes[tunnel.name]
            ?: if (WireRouteRouting.isFullTunnel(config)) WireRouteStore.ROUTING_FULL else WireRouteStore.ROUTING_SPLIT
    }

    private fun dnsDetail(tunnel: ObservableTunnel): String = if (tunnel.config?.`interface`?.dnsServers?.isEmpty() != false) "Not configured" else "Profile DNS"
    private fun retentionTitle(days: Int) = if (days == 1) "1 day" else "$days days"
    private fun formatRate(rate: Double): String = if (rate < 1) "Zero KB/s" else Formatter.formatShortFileSize(this, rate.roundToLong()) + "/s"
    private fun formatDuration(milliseconds: Long): String {
        val minutes = milliseconds / 60_000
        return when { minutes < 1 -> "<1m"; minutes < 60 -> "${minutes}m"; else -> "${minutes / 60}h ${minutes % 60}m" }
    }

    private fun setLocateButtonText(value: String) {
        mapLocateButton?.let { button -> (button.getChildAt(1) as? TextView)?.text = value }
    }

    private fun showMessage(message: CharSequence) { Snackbar.make(root, message, Snackbar.LENGTH_LONG).setAnchorView(tabBar).show() }
    private fun showError(error: Throwable) { showMessage(ErrorMessages[error].ifBlank { error.message ?: "WireRoute could not complete that action." }) }
}
