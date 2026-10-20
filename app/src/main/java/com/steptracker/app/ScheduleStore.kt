package com.steptracker.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class ScheduleStore(context: Context) {
    private val prefs = context.getSharedPreferences("schedules", Context.MODE_PRIVATE)
    private val KEY = "saved_schedules"
    private val KEY_CORRUPT_BACKUP = "saved_schedules_corrupt"

    fun loadAll(): MutableList<SavedSchedule> = parse(raw()).toMutableList()

    fun save(schedule: SavedSchedule) {
        write(upsert(raw(), schedule))
    }

    fun delete(id: Long) {
        write(remove(raw(), id))
    }

    /** Replaces all saved schedules (used by backup restore). */
    fun replaceAll(list: List<SavedSchedule>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        write(arr.toString())
    }

    /** Stored JSON; if it isn't even an array, it is set aside once rather than overwritten. */
    private fun raw(): String {
        val json = prefs.getString(KEY, "[]") ?: "[]"
        if (rootOrNull(json) != null) return json
        if (!prefs.contains(KEY_CORRUPT_BACKUP)) prefs.edit().putString(KEY_CORRUPT_BACKUP, json).apply()
        return "[]"
    }

    private fun write(json: String) {
        prefs.edit().putString(KEY, json).apply()
    }

    /**
     * Pure JSON operations. Entries are handled one by one, and save/delete work on the raw
     * array, so a single unreadable schedule is skipped on load but never erased by a save.
     */
    companion object {
        fun parse(json: String): List<SavedSchedule> {
            val arr = rootOrNull(json) ?: return emptyList()
            return (0 until arr.length()).mapNotNull { i ->
                try { SavedSchedule.fromJson(arr.getJSONObject(i)) } catch (_: Exception) { null }
            }
        }

        fun upsert(json: String, schedule: SavedSchedule): String {
            val arr = rootOrNull(json) ?: JSONArray()
            val out = JSONArray()
            var replaced = false
            for (i in 0 until arr.length()) {
                val entry = arr.opt(i)
                if (!replaced && idOf(entry) == schedule.id) {
                    out.put(schedule.toJson()); replaced = true
                } else {
                    out.put(entry)
                }
            }
            if (!replaced) out.put(schedule.toJson())
            return out.toString()
        }

        fun remove(json: String, id: Long): String {
            val arr = rootOrNull(json) ?: JSONArray()
            val out = JSONArray()
            for (i in 0 until arr.length()) {
                val entry = arr.opt(i)
                if (idOf(entry) != id) out.put(entry)
            }
            return out.toString()
        }

        private fun idOf(entry: Any?): Long? =
            (entry as? JSONObject)?.takeIf { it.has("id") }?.let {
                try { it.getLong("id") } catch (_: Exception) { null }
            }

        private fun rootOrNull(json: String): JSONArray? =
            try { JSONArray(json) } catch (_: Exception) { null }
    }
}
