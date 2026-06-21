package com.steptracker.app

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * A single dated workout produced from a [RunPlanCoach] plan. It already contains
 * the full period list (warmup walk → main work → cooldown walk) ready to load
 * straight into the Schedule tab, plus the calendar day it's scheduled for and a
 * stable [requestCode] used as the AlarmManager / notification id for its reminder.
 */
data class PlannedWorkout(
    val id: Long,
    val requestCode: Int,
    val dateMs: Long,            // local midnight of the workout day
    val weekNumber: Int,
    val phase: String,
    val title: String,
    val distanceKm: Double,
    val isLongRun: Boolean,
    val items: List<ScheduleItem>,
    var done: Boolean = false
) {
    val dateLabel: String
        get() = SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(Date(dateMs))

    fun isSameDay(otherMs: Long): Boolean = dayIndex(dateMs) == dayIndex(otherMs)

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("rc", requestCode)
        put("date", dateMs)
        put("week", weekNumber)
        put("phase", phase)
        put("title", title)
        put("dist", distanceKm)
        put("long", isLongRun)
        put("done", done)
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
        fun fromJson(o: JSONObject): PlannedWorkout {
            val arr = o.getJSONArray("items")
            val items = (0 until arr.length()).map { i ->
                val it = arr.getJSONObject(i)
                ScheduleItem(
                    type = ActivityType.valueOf(it.getString("type")),
                    durationMinutes = it.getInt("mins")
                )
            }
            return PlannedWorkout(
                id = o.getLong("id"),
                requestCode = o.getInt("rc"),
                dateMs = o.getLong("date"),
                weekNumber = o.getInt("week"),
                phase = o.optString("phase", ""),
                title = o.getString("title"),
                distanceKm = o.optDouble("dist", 0.0),
                isLongRun = o.optBoolean("long", false),
                items = items,
                done = o.optBoolean("done", false)
            )
        }

        /** Local-calendar day number; equal for two timestamps on the same date. */
        fun dayIndex(ms: Long): Long {
            val cal = Calendar.getInstance().apply {
                timeInMillis = ms
                set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }
            return cal.timeInMillis / 86_400_000L
        }
    }
}

/**
 * The user's active training plan: an ordered list of dated [PlannedWorkout]s plus
 * the goal label and the chosen warmup/cooldown durations.
 */
data class TrainingPlan(
    val createdMs: Long,
    val goalLabel: String,
    val warmupMin: Int,
    val cooldownMin: Int,
    val workouts: List<PlannedWorkout>
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("created", createdMs)
        put("goal", goalLabel)
        put("warmup", warmupMin)
        put("cooldown", cooldownMin)
        val arr = JSONArray()
        workouts.forEach { arr.put(it.toJson()) }
        put("workouts", arr)
    }

    companion object {
        fun fromJson(o: JSONObject): TrainingPlan {
            val arr = o.getJSONArray("workouts")
            val workouts = (0 until arr.length()).map { PlannedWorkout.fromJson(arr.getJSONObject(it)) }
            return TrainingPlan(
                createdMs = o.getLong("created"),
                goalLabel = o.getString("goal"),
                warmupMin = o.optInt("warmup", 3),
                cooldownMin = o.optInt("cooldown", 3),
                workouts = workouts
            )
        }
    }
}
