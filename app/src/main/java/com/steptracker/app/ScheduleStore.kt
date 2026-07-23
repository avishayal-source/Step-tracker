package com.steptracker.app

import android.content.Context
import org.json.JSONArray

class ScheduleStore(context: Context) {
    private val prefs = context.getSharedPreferences("schedules", Context.MODE_PRIVATE)
    private val KEY = "saved_schedules"

    fun loadAll(): MutableList<SavedSchedule> {
        val json = prefs.getString(KEY, "[]") ?: "[]"
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { SavedSchedule.fromJson(arr.getJSONObject(it)) }.toMutableList()
        } catch (e: Exception) { mutableListOf() }
    }

    fun save(schedule: SavedSchedule) {
        val all = loadAll()
        val idx = all.indexOfFirst { it.id == schedule.id }
        if (idx >= 0) all[idx] = schedule else all.add(schedule)
        persist(all)
    }

    fun delete(id: Long) {
        val all = loadAll().filter { it.id != id }
        persist(all)
    }

    /** Replaces all saved schedules (used by backup restore). */
    fun replaceAll(list: List<SavedSchedule>) {
        persist(list)
    }

    private fun persist(list: List<SavedSchedule>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }
}
