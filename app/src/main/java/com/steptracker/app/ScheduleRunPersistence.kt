package com.steptracker.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persists an in-progress schedule so it survives activity recreation
 * (e.g. user switches to another app and the system reclaims the UI).
 */
class ScheduleRunPersistence(context: Context) {
    private val prefs = context.getSharedPreferences("schedule_run", Context.MODE_PRIVATE)

    data class Snapshot(
        val items: List<ScheduleItem>,
        val currentIndex: Int,
        val periodStartMs: Long,
        val paused: Boolean = false,
        val remainingMs: Long = 0L
    )

    fun save(snapshot: Snapshot) {
        val arr = JSONArray()
        snapshot.items.forEach { item ->
            arr.put(JSONObject().apply {
                put("type", item.type.name)
                put("mins", item.durationMinutes)
                put("state", item.state.name)
                put("start", item.actualStartTime)
                put("end", item.actualEndTime)
            })
        }
        prefs.edit()
            .putString("items", arr.toString())
            .putInt("index", snapshot.currentIndex)
            .putLong("period_start", snapshot.periodStartMs)
            .putBoolean("running", true)
            .putBoolean("paused", snapshot.paused)
            .putLong("remaining_ms", snapshot.remainingMs)
            .apply()
    }

    fun load(): Snapshot? {
        if (!prefs.getBoolean("running", false)) return null
        return try {
            val arr = JSONArray(prefs.getString("items", "[]") ?: "[]")
            val items = (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                ScheduleItem(
                    type = ActivityType.valueOf(o.getString("type")),
                    durationMinutes = o.getInt("mins"),
                    state = ScheduleState.valueOf(o.getString("state")),
                    actualStartTime = o.optLong("start", 0L),
                    actualEndTime = o.optLong("end", 0L)
                )
            }
            Snapshot(
                items = items,
                currentIndex = prefs.getInt("index", 0),
                periodStartMs = prefs.getLong("period_start", 0L),
                paused = prefs.getBoolean("paused", false),
                remainingMs = prefs.getLong("remaining_ms", 0L)
            )
        } catch (_: Exception) {
            clear()
            null
        }
    }

    fun clear() {
        prefs.edit().clear().apply()
    }
}
