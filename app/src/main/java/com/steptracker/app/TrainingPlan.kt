package com.steptracker.app

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Lifecycle of a planned workout. A workout leaves the schedule only when it is no longer PENDING. */
enum class WorkoutStatus { PENDING, DONE, SKIPPED }

/**
 * A single dated workout produced from a [RunPlanCoach] plan. It already contains
 * the full period list (warmup walk → main work → cooldown walk) ready to load
 * straight into the Schedule tab, plus the calendar day it's scheduled for and a
 * stable [requestCode] used as the AlarmManager / notification id for its reminder.
 *
 * A missed workout is *not* dropped when its date passes: it stays [WorkoutStatus.PENDING]
 * and overdue so it can still be done, until [PlanHygiene] retires it.
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
    var status: WorkoutStatus = WorkoutStatus.PENDING,
    var completedAtMs: Long = 0L
) {
    val dateLabel: String
        get() = SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(Date(dateMs))

    val done: Boolean get() = status == WorkoutStatus.DONE
    val pending: Boolean get() = status == WorkoutStatus.PENDING

    fun isSameDay(otherMs: Long): Boolean = dayIndex(dateMs) == dayIndex(otherMs)

    /** Still to do, but its day has already passed. */
    fun isOverdue(now: Long = System.currentTimeMillis()): Boolean =
        pending && dayIndex(dateMs) < dayIndex(now)

    /** Whole days between the scheduled day and [now]; 0 when scheduled for today. */
    fun daysLate(now: Long = System.currentTimeMillis()): Int =
        (dayIndex(now) - dayIndex(dateMs)).toInt()

    /** "Overdue from Sat 5 Sep (2 days late)" / "Today" / "Sat 5 Sep". */
    fun dueLabel(now: Long = System.currentTimeMillis()): String = when {
        isOverdue(now) -> {
            val late = daysLate(now)
            "Overdue from $dateLabel (${late} day${if (late == 1) "" else "s"} late)"
        }
        isSameDay(now) -> "Today"
        else -> dateLabel
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("rc", requestCode)
        put("date", dateMs)
        put("week", weekNumber)
        put("phase", phase)
        put("title", title)
        put("dist", distanceKm)
        put("long", isLongRun)
        put("status", status.name)
        put("completedAt", completedAtMs)
        // Legacy field kept so a plan written by this build still loads on older installs.
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
                status = readStatus(o),
                completedAtMs = o.optLong("completedAt", 0L)
            )
        }

        /** Plans saved before v1.0.9 only have the boolean `done` flag. */
        private fun readStatus(o: JSONObject): WorkoutStatus {
            o.optString("status", "").takeIf { it.isNotBlank() }?.let { raw ->
                return try { WorkoutStatus.valueOf(raw) } catch (_: Exception) { WorkoutStatus.PENDING }
            }
            return if (o.optBoolean("done", false)) WorkoutStatus.DONE else WorkoutStatus.PENDING
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
    val workouts: List<PlannedWorkout>,
    val startDateMs: Long = 0L,
    val summaryOneLiner: String = "",
    val milestoneTeaser: String = "",
    val totalWeeks: Int = 0
) {
    private val maxProgramWeek: Int
        get() = if (totalWeeks > 0) totalWeeks else workouts.maxOfOrNull { it.weekNumber } ?: 1

    /** Program week 1..N from plan start; before start date returns 1 (preview week 1). */
    fun displayWeek(now: Long = System.currentTimeMillis()): Int {
        val start = effectiveStartMs()
        if (now < start) return 1
        val days = ((now - start) / 86_400_000L).toInt()
        return (days / 7 + 1).coerceIn(1, maxProgramWeek)
    }

    fun workoutsForDisplayWeek(now: Long = System.currentTimeMillis()): List<PlannedWorkout> =
        workouts.filter { it.weekNumber == displayWeek(now) }.sortedBy { it.dateMs }

    /** Still-to-do workouts whose day has passed, oldest first. */
    fun overdueWorkouts(now: Long = System.currentTimeMillis()): List<PlannedWorkout> =
        workouts.filter { it.isOverdue(now) }.sortedBy { it.dateMs }

    fun pendingWorkouts(): List<PlannedWorkout> = workouts.filter { it.pending }.sortedBy { it.dateMs }

    fun findWorkout(id: Long): PlannedWorkout? = workouts.firstOrNull { it.id == id }

    /**
     * Moves every still-pending workout (and the plan start) forward by [days] so a user
     * who fell behind gets the remaining plan realigned instead of a growing backlog.
     * Completed and skipped workouts keep their original dates.
     */
    fun shiftedForward(days: Int): TrainingPlan {
        if (days <= 0) return this
        val delta = days * 86_400_000L
        return copy(
            startDateMs = if (startDateMs > 0L) startDateMs + delta else startDateMs,
            workouts = workouts.map { if (it.pending) it.copy(dateMs = it.dateMs + delta) else it }
        )
    }

    fun effectiveStartMs(): Long =
        if (startDateMs > 0L) startDateMs
        else workouts.minOfOrNull { it.dateMs } ?: System.currentTimeMillis()

    fun hasStarted(now: Long = System.currentTimeMillis()): Boolean = now >= effectiveStartMs()

    fun toJson(): JSONObject = JSONObject().apply {
        put("created", createdMs)
        put("goal", goalLabel)
        put("warmup", warmupMin)
        put("cooldown", cooldownMin)
        put("start", startDateMs)
        put("summary", summaryOneLiner)
        put("milestone", milestoneTeaser)
        put("totalWeeks", totalWeeks)
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
                workouts = workouts,
                startDateMs = o.optLong("start", 0L),
                summaryOneLiner = o.optString("summary", ""),
                milestoneTeaser = o.optString("milestone", ""),
                totalWeeks = o.optInt("totalWeeks", 0)
            )
        }
    }
}
