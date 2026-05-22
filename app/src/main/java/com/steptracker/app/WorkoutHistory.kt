package com.steptracker.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

data class WorkoutRecord(
    val id: Long = System.currentTimeMillis(),
    val dateMs: Long,                   // session start wall-clock ms
    val walkSteps: Int,
    val runSteps: Int,
    val walkDistM: Double,
    val runDistM: Double,
    val walkDurationMs: Long,
    val runDurationMs: Long
) {
    val totalDistM   get() = walkDistM + runDistM
    val totalSteps   get() = walkSteps + runSteps
    val totalDurMs   get() = walkDurationMs + runDurationMs
    val dateLabel    get() = SimpleDateFormat("dd MMM yyyy  HH:mm", Locale.getDefault()).format(Date(dateMs))
}

class WorkoutHistory(context: Context) {
    private val prefs = context.getSharedPreferences("workout_history", Context.MODE_PRIVATE)
    private val KEY   = "records"

    fun loadAll(): List<WorkoutRecord> {
        val json = prefs.getString(KEY, "[]") ?: "[]"
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { fromJson(arr.getJSONObject(it)) }
                .sortedByDescending { it.dateMs }
        } catch (_: Exception) { emptyList() }
    }

    fun save(r: WorkoutRecord) {
        val all = loadAll().toMutableList()
        all.add(0, r)
        // Keep last 365 sessions
        val trimmed = if (all.size > 365) all.take(365) else all
        val arr = JSONArray()
        trimmed.forEach { arr.put(toJson(it)) }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    fun clearAll() {
        prefs.edit().remove(KEY).apply()
    }

    private fun toJson(r: WorkoutRecord) = JSONObject().apply {
        put("id",        r.id)
        put("date",      r.dateMs)
        put("wSteps",    r.walkSteps)
        put("rSteps",    r.runSteps)
        put("wDist",     r.walkDistM)
        put("rDist",     r.runDistM)
        put("wDur",      r.walkDurationMs)
        put("rDur",      r.runDurationMs)
    }

    private fun fromJson(o: JSONObject) = WorkoutRecord(
        id             = o.getLong("id"),
        dateMs         = o.getLong("date"),
        walkSteps      = o.getInt("wSteps"),
        runSteps       = o.getInt("rSteps"),
        walkDistM      = o.getDouble("wDist"),
        runDistM       = o.getDouble("rDist"),
        walkDurationMs = o.getLong("wDur"),
        runDurationMs  = o.getLong("rDur")
    )
}
