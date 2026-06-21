package com.steptracker.app

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Turns a [RunPlanCoach.Result] into a concrete, dated [TrainingPlan]: one workout
 * per training day, spread across each week from a chosen start date, with a warmup
 * walk and cooldown walk wrapped around the main work.
 *
 * Distance is converted to time using a conservative easy-pace estimate (the plan is
 * built on easy, conversational running). Beginners get run/walk intervals instead of
 * continuous running, matching the engine's safety design.
 */
object PlanScheduler {

    private const val DAY_MS = 86_400_000L
    private const val REQ_BASE = 80_000   // base AlarmManager request code for reminders

    /** Even weekday offsets within a 7-day week for [daysPerWeek] sessions. */
    fun weekdayOffsets(daysPerWeek: Int): List<Int> {
        val d = daysPerWeek.coerceIn(2, 6)
        return (0 until d).map { (it * 7.0 / d).roundToInt() }.distinct()
    }

    fun generate(
        result: RunPlanCoach.Result,
        profile: RunPlanCoach.Profile,
        goal: RunPlanCoach.Goal,
        startMidnightMs: Long,
        warmupMin: Int,
        cooldownMin: Int
    ): TrainingPlan {
        val beginner = profile.currentLongestRunKm < 1.0
        val easyPaceMinPerKm = if (beginner) 9.0 else 7.5
        val offsets = weekdayOffsets(profile.daysPerWeekAvailable)

        val workouts = ArrayList<PlannedWorkout>()
        var seq = 0

        for (wp in result.plan) {
            val k = wp.runningDays.coerceAtLeast(1)
            val dayOffsets = offsets.take(k)
            dayOffsets.forEachIndexed { j, off ->
                val dateMs = startMidnightMs + ((wp.week - 1) * 7 + off) * DAY_MS
                val isLong = j == 0
                val distanceKm = if (isLong) wp.longRunKm
                    else max(1.0, min(wp.longRunKm, (wp.weeklyVolumeKm - wp.longRunKm) / max(1, k - 1)))
                val mainMin = max(8, (distanceKm * easyPaceMinPerKm).roundToInt())

                val items = ArrayList<ScheduleItem>()
                if (warmupMin > 0) items.add(ScheduleItem(ActivityType.WALKING, warmupMin))
                if (beginner && distanceKm < 3.0) {
                    // Run/walk intervals: run 2 min, walk 1 min, repeated to fill the session.
                    val reps = max(4, (mainMin / 3.0).roundToInt())
                    repeat(reps) {
                        items.add(ScheduleItem(ActivityType.RUNNING, 2))
                        items.add(ScheduleItem(ActivityType.WALKING, 1))
                    }
                } else {
                    items.add(ScheduleItem(ActivityType.RUNNING, mainMin))
                }
                if (cooldownMin > 0) items.add(ScheduleItem(ActivityType.WALKING, cooldownMin))

                val kind = if (isLong) "Long run" else "Easy run"
                val recovery = if (wp.isCutback) " (recovery)" else ""
                val title = "Week ${wp.week} · $kind · ${fmt(distanceKm)} km$recovery"

                workouts.add(
                    PlannedWorkout(
                        id = startMidnightMs + seq,
                        requestCode = REQ_BASE + seq,
                        dateMs = dateMs,
                        weekNumber = wp.week,
                        phase = wp.phase,
                        title = title,
                        distanceKm = round1(distanceKm),
                        isLongRun = isLong,
                        items = items
                    )
                )
                seq++
            }
        }

        workouts.sortBy { it.dateMs }
        val goalLabel = "${fmt(goal.targetDistanceKm)} km in ${goal.horizonWeeks} weeks"
        return TrainingPlan(
            createdMs = System.currentTimeMillis(),
            goalLabel = goalLabel,
            warmupMin = warmupMin,
            cooldownMin = cooldownMin,
            workouts = workouts
        )
    }

    private fun round1(v: Double): Double = Math.round(v * 10.0) / 10.0
    private fun fmt(v: Double): String = if (v >= 10) "%.0f".format(v) else "%.1f".format(v)
}
