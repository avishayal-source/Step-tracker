package com.steptracker.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** What's needed to continue or save a workout after the process was killed mid-session. */
data class ActiveSession(
    val sessionStartMs: Long,
    /** Active (unpaused) time up to [savedAtMs]. */
    val activeElapsedMs: Long,
    val paused: Boolean,
    val totalSteps: Int,
    val periods: List<ActivityPeriod>,
    val savedAtMs: Long
) {
    companion object {
        /** A system restart within this window continues the workout; older ones are saved. */
        const val CONTINUE_WINDOW_MS = 10 * 60_000L

        fun toJson(s: ActiveSession): String = JSONObject().apply {
            put("start", s.sessionStartMs)
            put("active", s.activeElapsedMs)
            put("paused", s.paused)
            put("steps", s.totalSteps)
            put("saved", s.savedAtMs)
            put("periods", JSONArray().apply {
                s.periods.forEach { p ->
                    put(JSONObject().apply {
                        put("type", p.type.name)
                        put("start", p.startTime)
                        put("end", p.endTime)
                        put("steps", p.steps)
                        put("gps", p.gpsDistanceM)
                        put("gpsBase", p.stepDistanceAtGpsStartM)
                        put("gpsOn", p.gpsEngaged)
                        put("walkStride", p.walkStride)
                        put("runStride", p.runStride)
                    })
                }
            })
        }.toString()

        fun fromJson(json: String?): ActiveSession? {
            if (json.isNullOrBlank()) return null
            return try {
                val o = JSONObject(json)
                val arr = o.getJSONArray("periods")
                val periods = (0 until arr.length()).map { i ->
                    val p = arr.getJSONObject(i)
                    ActivityPeriod(
                        type = ActivityType.valueOf(p.getString("type")),
                        startTime = p.getLong("start"),
                        endTime = p.getLong("end"),
                        steps = p.getInt("steps"),
                        gpsDistanceM = p.getDouble("gps"),
                        stepDistanceAtGpsStartM = p.getDouble("gpsBase"),
                        gpsEngaged = p.getBoolean("gpsOn"),
                        walkStride = p.getDouble("walkStride"),
                        runStride = p.getDouble("runStride")
                    )
                }
                ActiveSession(
                    sessionStartMs = o.getLong("start"),
                    activeElapsedMs = o.getLong("active"),
                    paused = o.getBoolean("paused"),
                    totalSteps = o.getInt("steps"),
                    periods = periods,
                    savedAtMs = o.getLong("saved")
                )
            } catch (_: Exception) {
                null
            }
        }

        /** Closes the open period where the snapshot ended, so the gap isn't counted as activity. */
        fun closedAtSnapshot(s: ActiveSession): List<ActivityPeriod> = s.periods.map { p ->
            if (p.endTime > 0) p.copy()
            else p.copy(endTime = maxOf(s.savedAtMs, p.startTime + 1000))
        }
    }
}

class ActiveSessionStore(context: Context) {
    private val prefs = context.getSharedPreferences("active_session", Context.MODE_PRIVATE)

    fun save(s: ActiveSession) {
        prefs.edit().putString(KEY, ActiveSession.toJson(s)).apply()
    }

    fun load(): ActiveSession? = ActiveSession.fromJson(prefs.getString(KEY, null))

    fun clear() {
        prefs.edit().remove(KEY).apply()
    }

    /** Steps of a workout the service saved on its own while the app was closed (0 = none). */
    var autoSavedSteps: Int
        get() = prefs.getInt(KEY_AUTO_SAVED, 0)
        set(v) { prefs.edit().putInt(KEY_AUTO_SAVED, v).apply() }

    private companion object {
        const val KEY = "session"
        const val KEY_AUTO_SAVED = "auto_saved_steps"
    }
}
