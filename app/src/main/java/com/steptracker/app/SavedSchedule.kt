package com.steptracker.app

import org.json.JSONArray
import org.json.JSONObject

data class SavedSchedule(
    val id: Long = System.currentTimeMillis(),
    val name: String,
    val items: List<ScheduleItem>
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        val arr = JSONArray()
        items.forEach { item ->
            arr.put(JSONObject().apply {
                put("type", item.type.name)
                put("mins", item.durationMinutes)
            })
        }
        put("items", arr)
    }

    companion object {
        fun fromJson(obj: JSONObject): SavedSchedule {
            val arr = obj.getJSONArray("items")
            val items = (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                ScheduleItem(
                    type = ActivityType.valueOf(o.getString("type")),
                    durationMinutes = o.getInt("mins")
                )
            }
            return SavedSchedule(
                id   = obj.getLong("id"),
                name = obj.getString("name"),
                items = items
            )
        }
    }
}
