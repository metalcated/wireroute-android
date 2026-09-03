/* SPDX-License-Identifier: Apache-2.0 */
package com.wireguard.android.wireroute

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.wireguard.android.backend.Tunnel
import com.wireguard.android.model.TunnelManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max

data class WireRouteActivityPoint(
    val sampledAt: Long,
    val receivedBytesPerSecond: Double,
    val sentBytesPerSecond: Double,
    val totalReceivedBytes: Long,
    val totalSentBytes: Long,
    val lastHandshake: Long?
)

data class WireRouteActivitySession(
    val id: Long,
    val profileName: String,
    val startedAt: Long,
    val endedAt: Long?,
    val receivedBytes: Long,
    val sentBytes: Long,
    val lastHandshake: Long?
)

/** Device-local WireRoute metadata and activity history. Private tunnel keys remain in the
 * existing protected configuration store and are never copied into this database. */
class WireRouteStore(context: Context) : SQLiteOpenHelper(
    context.applicationContext,
    "wireroute.db",
    null,
    DATABASE_VERSION
) {
    override fun onCreate(database: SQLiteDatabase) {
        database.execSQL("CREATE TABLE settings (key TEXT PRIMARY KEY NOT NULL, value TEXT NOT NULL)")
        database.execSQL(
            """CREATE TABLE profile_metadata (
                profile_name TEXT PRIMARY KEY NOT NULL,
                routing_mode TEXT NOT NULL DEFAULT 'split',
                split_routes TEXT,
                dns_mode TEXT NOT NULL DEFAULT 'profile',
                dns_label TEXT NOT NULL DEFAULT 'Profile DNS'
            )"""
        )
        database.execSQL(
            """CREATE TABLE activity_sessions (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                profile_name TEXT NOT NULL,
                started_at INTEGER NOT NULL,
                ended_at INTEGER,
                last_sample_at INTEGER NOT NULL,
                received_bytes INTEGER NOT NULL DEFAULT 0,
                sent_bytes INTEGER NOT NULL DEFAULT 0,
                last_handshake INTEGER
            )"""
        )
        database.execSQL(
            """CREATE TABLE activity_samples (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                session_id INTEGER NOT NULL,
                sampled_at INTEGER NOT NULL,
                received_per_second REAL NOT NULL,
                sent_per_second REAL NOT NULL,
                total_received INTEGER NOT NULL,
                total_sent INTEGER NOT NULL,
                last_handshake INTEGER,
                FOREIGN KEY(session_id) REFERENCES activity_sessions(id) ON DELETE CASCADE
            )"""
        )
        database.execSQL("CREATE INDEX activity_samples_session_time ON activity_samples(session_id, sampled_at)")
        database.execSQL("CREATE INDEX activity_sessions_profile_time ON activity_sessions(profile_name, started_at)")
    }

    override fun onConfigure(database: SQLiteDatabase) {
        database.setForeignKeyConstraintsEnabled(true)
    }

    override fun onUpgrade(database: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    @Synchronized
    fun setting(key: String, fallback: String): String {
        readableDatabase.query("settings", arrayOf("value"), "key = ?", arrayOf(key), null, null, null).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getString(0) else fallback
        }
    }

    @Synchronized
    fun putSetting(key: String, value: String) {
        writableDatabase.insertWithOnConflict(
            "settings",
            null,
            ContentValues().apply {
                put("key", key)
                put("value", value)
            },
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    fun selectedProfile(): String? = setting(SETTING_SELECTED_PROFILE, "").ifBlank { null }

    fun setSelectedProfile(profileName: String?) = putSetting(SETTING_SELECTED_PROFILE, profileName.orEmpty())

    fun appearance(): String = setting(SETTING_APPEARANCE, APPEARANCE_NORDIC)

    fun setAppearance(value: String) = putSetting(SETTING_APPEARANCE, value)

    fun retentionDays(): Int = setting(SETTING_RETENTION_DAYS, "7").toIntOrNull()?.takeIf { it in setOf(1, 7, 30) } ?: 7

    fun setRetentionDays(days: Int) {
        require(days in setOf(1, 7, 30))
        putSetting(SETTING_RETENTION_DAYS, days.toString())
        pruneActivity()
    }

    @Synchronized
    fun routingMode(profileName: String, detectedFullTunnel: Boolean): String {
        readableDatabase.query(
            "profile_metadata", arrayOf("routing_mode"), "profile_name = ?", arrayOf(profileName), null, null, null
        ).use { cursor ->
            if (cursor.moveToFirst()) return cursor.getString(0)
        }
        val detected = if (detectedFullTunnel) ROUTING_FULL else ROUTING_SPLIT
        saveRouting(profileName, detected, null)
        return detected
    }

    @Synchronized
    fun saveRouting(profileName: String, mode: String, splitRoutes: Map<String, List<String>>?) {
        val encodedRoutes = splitRoutes?.let(::encodeRoutes)
        val values = ContentValues().apply {
            put("profile_name", profileName)
            put("routing_mode", mode)
            if (encodedRoutes == null) putNull("split_routes") else put("split_routes", encodedRoutes)
        }
        writableDatabase.insertWithOnConflict("profile_metadata", null, values, SQLiteDatabase.CONFLICT_IGNORE)
        writableDatabase.update("profile_metadata", values, "profile_name = ?", arrayOf(profileName))
    }

    @Synchronized
    fun splitRoutes(profileName: String): Map<String, List<String>>? {
        readableDatabase.query(
            "profile_metadata", arrayOf("split_routes"), "profile_name = ?", arrayOf(profileName), null, null, null
        ).use { cursor ->
            if (!cursor.moveToFirst() || cursor.isNull(0)) return null
            return decodeRoutes(cursor.getString(0))
        }
    }

    @Synchronized
    fun renameProfile(oldName: String, newName: String) {
        val values = ContentValues().apply { put("profile_name", newName) }
        writableDatabase.update("profile_metadata", values, "profile_name = ?", arrayOf(oldName))
        writableDatabase.update("activity_sessions", values, "profile_name = ?", arrayOf(oldName))
        if (selectedProfile() == oldName) setSelectedProfile(newName)
    }

    @Synchronized
    fun removeProfile(profileName: String) {
        writableDatabase.delete("profile_metadata", "profile_name = ?", arrayOf(profileName))
        if (selectedProfile() == profileName) setSelectedProfile(null)
    }

    @Synchronized
    fun recoverInterruptedSessions() {
        writableDatabase.execSQL("UPDATE activity_sessions SET ended_at = last_sample_at WHERE ended_at IS NULL")
    }

    @Synchronized
    fun startSession(profileName: String, now: Long): Long {
        return writableDatabase.insertOrThrow(
            "activity_sessions", null, ContentValues().apply {
                put("profile_name", profileName)
                put("started_at", now)
                put("last_sample_at", now)
            }
        )
    }

    @Synchronized
    fun addSample(
        sessionId: Long,
        now: Long,
        receivedPerSecond: Double,
        sentPerSecond: Double,
        totalReceived: Long,
        totalSent: Long,
        lastHandshake: Long?
    ) {
        writableDatabase.beginTransaction()
        try {
            writableDatabase.insertOrThrow(
                "activity_samples", null, ContentValues().apply {
                    put("session_id", sessionId)
                    put("sampled_at", now)
                    put("received_per_second", receivedPerSecond)
                    put("sent_per_second", sentPerSecond)
                    put("total_received", totalReceived)
                    put("total_sent", totalSent)
                    if (lastHandshake == null) putNull("last_handshake") else put("last_handshake", lastHandshake)
                }
            )
            writableDatabase.update(
                "activity_sessions",
                ContentValues().apply {
                    put("last_sample_at", now)
                    put("received_bytes", totalReceived)
                    put("sent_bytes", totalSent)
                    if (lastHandshake == null) putNull("last_handshake") else put("last_handshake", lastHandshake)
                },
                "id = ?",
                arrayOf(sessionId.toString())
            )
            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
        }
    }

    @Synchronized
    fun endSession(sessionId: Long, now: Long) {
        writableDatabase.update(
            "activity_sessions",
            ContentValues().apply { put("ended_at", now) },
            "id = ? AND ended_at IS NULL",
            arrayOf(sessionId.toString())
        )
    }

    @Synchronized
    fun activityPoints(profileName: String, limit: Int = 90): List<WireRouteActivityPoint> {
        val points = mutableListOf<WireRouteActivityPoint>()
        readableDatabase.rawQuery(
            """SELECT s.sampled_at, s.received_per_second, s.sent_per_second,
                      s.total_received, s.total_sent, s.last_handshake
               FROM activity_samples s
               JOIN activity_sessions a ON a.id = s.session_id
               WHERE a.profile_name = ?
               ORDER BY s.sampled_at DESC LIMIT ?""",
            arrayOf(profileName, limit.toString())
        ).use { cursor ->
            while (cursor.moveToNext()) {
                points += WireRouteActivityPoint(
                    sampledAt = cursor.getLong(0),
                    receivedBytesPerSecond = cursor.getDouble(1),
                    sentBytesPerSecond = cursor.getDouble(2),
                    totalReceivedBytes = cursor.getLong(3),
                    totalSentBytes = cursor.getLong(4),
                    lastHandshake = if (cursor.isNull(5)) null else cursor.getLong(5)
                )
            }
        }
        return points.asReversed()
    }

    @Synchronized
    fun sessions(profileName: String, limit: Int = 24): List<WireRouteActivitySession> {
        val result = mutableListOf<WireRouteActivitySession>()
        readableDatabase.query(
            "activity_sessions",
            arrayOf("id", "profile_name", "started_at", "ended_at", "received_bytes", "sent_bytes", "last_handshake"),
            "profile_name = ?", arrayOf(profileName), null, null, "started_at DESC", limit.toString()
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result += WireRouteActivitySession(
                    id = cursor.getLong(0),
                    profileName = cursor.getString(1),
                    startedAt = cursor.getLong(2),
                    endedAt = if (cursor.isNull(3)) null else cursor.getLong(3),
                    receivedBytes = cursor.getLong(4),
                    sentBytes = cursor.getLong(5),
                    lastHandshake = if (cursor.isNull(6)) null else cursor.getLong(6)
                )
            }
        }
        return result
    }

    @Synchronized
    fun clearCompletedActivity(profileName: String) {
        writableDatabase.delete(
            "activity_sessions",
            "profile_name = ? AND ended_at IS NOT NULL",
            arrayOf(profileName)
        )
    }

    @Synchronized
    fun pruneActivity() {
        val cutoff = System.currentTimeMillis() - retentionDays() * DAY_MILLIS
        writableDatabase.delete("activity_sessions", "ended_at IS NOT NULL AND ended_at < ?", arrayOf(cutoff.toString()))
    }

    private fun encodeRoutes(routes: Map<String, List<String>>): String {
        val root = JSONObject()
        routes.forEach { (key, values) -> root.put(key, JSONArray(values)) }
        return root.toString()
    }

    private fun decodeRoutes(raw: String): Map<String, List<String>> {
        val root = JSONObject(raw)
        return root.keys().asSequence().associateWith { key ->
            val array = root.getJSONArray(key)
            (0 until array.length()).map(array::getString)
        }
    }

    companion object {
        private const val DATABASE_VERSION = 1
        private const val DAY_MILLIS = 24L * 60 * 60 * 1000
        const val APPEARANCE_NORDIC = "blueNordic"
        const val APPEARANCE_SYSTEM = "system"
        const val ROUTING_SPLIT = "split"
        const val ROUTING_FULL = "full"
        private const val SETTING_SELECTED_PROFILE = "selected_profile"
        private const val SETTING_APPEARANCE = "appearance"
        private const val SETTING_RETENTION_DAYS = "activity_retention_days"
    }
}

class WireRouteActivitySampler(
    private val manager: TunnelManager,
    private val store: WireRouteStore,
    private val scope: CoroutineScope
) {
    private data class ActiveSample(
        val sessionId: Long,
        var sampledAt: Long,
        var received: Long,
        var sent: Long
    )

    private val active = ConcurrentHashMap<String, ActiveSample>()
    private var job: Job? = null

    fun start() {
        if (job != null) return
        job = scope.launch(Dispatchers.IO) {
            store.recoverInterruptedSessions()
            store.pruneActivity()
            while (isActive) {
                sample()
                delay(1_000)
            }
        }
    }

    private suspend fun sample() {
        val tunnels = manager.getTunnels().toList()
        val names = tunnels.mapTo(mutableSetOf()) { it.name }
        active.keys.filterNot(names::contains).forEach { name ->
            active.remove(name)?.let { store.endSession(it.sessionId, System.currentTimeMillis()) }
        }

        for (tunnel in tunnels) {
            if (tunnel.state != Tunnel.State.UP) {
                active.remove(tunnel.name)?.let { store.endSession(it.sessionId, System.currentTimeMillis()) }
                continue
            }
            try {
                val now = System.currentTimeMillis()
                val statistics = tunnel.getStatisticsAsync()
                val received = statistics.totalRx()
                val sent = statistics.totalTx()
                val handshakes = statistics.peers().mapNotNull { statistics.peer(it)?.latestHandshakeEpochMillis() }
                val lastHandshake = handshakes.maxOrNull()?.takeIf { it > 0 }
                val previous = active[tunnel.name]
                val state = previous ?: ActiveSample(
                    sessionId = store.startSession(tunnel.name, now),
                    sampledAt = now,
                    received = received,
                    sent = sent
                ).also { active[tunnel.name] = it }
                val elapsedSeconds = max(0.001, (now - state.sampledAt) / 1000.0)
                val receivedRate = max(0L, received - state.received) / elapsedSeconds
                val sentRate = max(0L, sent - state.sent) / elapsedSeconds
                store.addSample(
                    state.sessionId,
                    now,
                    receivedRate,
                    sentRate,
                    received,
                    sent,
                    lastHandshake
                )
                state.sampledAt = now
                state.received = received
                state.sent = sent
            } catch (_: Throwable) {
                // The backend can be briefly unavailable during activation. The next sample retries.
            }
        }
    }
}
