package com.steptracker.wear.workout

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class WatchSession(
    val id: String,
    val startedAtMs: Long,
    val endedAtMs: Long,
    val mode: String,              // FREE | SCHEDULED
    val workoutId: Long,
    val title: String,
    val steps: Int,
    val distM: Double,
    val durationMs: Long,
    val walkSteps: Int = 0,
    val runSteps: Int = 0,
    val walkDistM: Double = 0.0,
    val runDistM: Double = 0.0,
    val walkDurationMs: Long = 0L,
    val runDurationMs: Long = 0L,
    val syncedToPhone: Boolean = false
)

/**
 * Offline finished sessions on the watch until the phone imports them.
 */
object SessionStore {
    private const val PREFS = "ywalk_sessions"
    private const val KEY = "list"
    private const val KEY_LIVE = "live"

    fun add(context: Context, session: WatchSession) {
        val all = loadAll(context).toMutableList()
        all.removeAll { it.id == session.id }
        all.add(0, session)
        saveAll(context, all.take(50))
    }

    fun loadAll(context: Context): List<WatchSession> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
            ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i -> fromJson(arr.getJSONObject(i)) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun unsynced(context: Context): List<WatchSession> =
        loadAll(context).filter { !it.syncedToPhone }

    fun markSynced(context: Context, id: String) {
        val all = loadAll(context).map { if (it.id == id) it.copy(syncedToPhone = true) else it }
        saveAll(context, all)
    }

    fun saveLive(context: Context, session: WatchSession) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LIVE, toJson(session).toString())
            .apply()
    }

    fun loadLive(context: Context): WatchSession? {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_LIVE, null)
            ?: return null
        return try { fromJson(JSONObject(raw)) } catch (_: Exception) { null }
    }

    fun clearLive(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_LIVE)
            .apply()
    }

    /** If the service died mid-workout, keep the captured session instead of dropping it. */
    fun finalizeOrphanIfNeeded(context: Context) {
        if (WorkoutService.isActive) return
        val live = loadLive(context) ?: return
        add(
            context,
            live.copy(
                endedAtMs = System.currentTimeMillis(),
                syncedToPhone = false
            )
        )
        clearLive(context)
    }

    private fun saveAll(context: Context, sessions: List<WatchSession>) {
        val arr = JSONArray()
        sessions.forEach { arr.put(toJson(it)) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, arr.toString())
            .apply()
    }

    fun newId(): String = UUID.randomUUID().toString()

    fun toJson(s: WatchSession) = JSONObject()
        .put("id", s.id)
        .put("startedAtMs", s.startedAtMs)
        .put("endedAtMs", s.endedAtMs)
        .put("mode", s.mode)
        .put("workoutId", s.workoutId)
        .put("title", s.title)
        .put("steps", s.steps)
        .put("distM", s.distM)
        .put("durationMs", s.durationMs)
        .put("walkSteps", s.walkSteps)
        .put("runSteps", s.runSteps)
        .put("walkDistM", s.walkDistM)
        .put("runDistM", s.runDistM)
        .put("walkDurationMs", s.walkDurationMs)
        .put("runDurationMs", s.runDurationMs)
        .put("syncedToPhone", s.syncedToPhone)

    private fun fromJson(o: JSONObject): WatchSession {
        val steps = o.optInt("steps", 0)
        val distM = o.optDouble("distM", 0.0)
        val durationMs = o.optLong("durationMs", 0L)
        return WatchSession(
            id = o.getString("id"),
            startedAtMs = o.getLong("startedAtMs"),
            endedAtMs = o.getLong("endedAtMs"),
            mode = o.getString("mode"),
            workoutId = o.optLong("workoutId", 0L),
            title = o.optString("title", ""),
            steps = steps,
            distM = distM,
            durationMs = durationMs,
            walkSteps = o.optInt("walkSteps", steps),
            runSteps = o.optInt("runSteps", 0),
            walkDistM = o.optDouble("walkDistM", distM),
            runDistM = o.optDouble("runDistM", 0.0),
            walkDurationMs = o.optLong("walkDurationMs", durationMs),
            runDurationMs = o.optLong("runDurationMs", 0L),
            syncedToPhone = o.optBoolean("syncedToPhone", false)
        )
    }
}
