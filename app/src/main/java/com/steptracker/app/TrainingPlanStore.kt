package com.steptracker.app

import android.content.Context
import org.json.JSONObject

/**
 * Persists the single active [TrainingPlan] (JSON in SharedPreferences) and offers
 * helpers the Schedule tab uses to find / complete the next due workout.
 *
 * A workout is only removed from the schedule when it is completed or explicitly
 * retired — never because its date passed. See [PlanHygiene].
 */
class TrainingPlanStore(context: Context) {
    private val prefs = SecurePrefs.open(context, "training_plan")
    private val KEY = "active_plan"

    fun load(): TrainingPlan? {
        val json = prefs.getString(KEY, null) ?: return null
        return try { TrainingPlan.fromJson(JSONObject(json)) } catch (_: Exception) { null }
    }

    fun save(plan: TrainingPlan) {
        prefs.edit().putString(KEY, plan.toJson().toString()).apply()
    }

    fun clear() { prefs.edit().remove(KEY).apply() }

    fun hasActivePlan(): Boolean = load() != null

    /**
     * Loads the plan and applies the grace window / backlog cap, persisting any change.
     * Returns null when there is no active plan.
     */
    fun loadReconciled(now: Long = System.currentTimeMillis()): PlanHygiene.Result? {
        val plan = load() ?: return null
        val result = PlanHygiene.reconcile(plan, now)
        if (result.changed) save(result.plan)
        return result
    }

    /**
     * The workout the user should do next: today's, otherwise the oldest still-pending
     * one whose day has passed. This is what makes a missed run reachable tomorrow.
     */
    fun nextDueWorkout(plan: TrainingPlan, now: Long = System.currentTimeMillis()): PlannedWorkout? =
        plan.workouts.filter { it.pending && it.isSameDay(now) }.minByOrNull { it.dateMs }
            ?: plan.overdueWorkouts(now).firstOrNull()

    /** The earliest still-pending workout, overdue ones included. */
    fun nextWorkout(plan: TrainingPlan, now: Long = System.currentTimeMillis()): PlannedWorkout? =
        plan.pendingWorkouts().minByOrNull { it.dateMs }

    /** Marks a specific workout done and persists. Returns the updated plan. */
    fun markDone(workoutId: Long, now: Long = System.currentTimeMillis()): TrainingPlan? {
        val plan = load() ?: return null
        val target = plan.findWorkout(workoutId) ?: return plan
        target.status = WorkoutStatus.DONE
        target.completedAtMs = now
        save(plan)
        return plan
    }

    /** User chose to drop a workout rather than do it late. */
    fun markSkipped(workoutId: Long): TrainingPlan? {
        val plan = load() ?: return null
        val target = plan.findWorkout(workoutId) ?: return plan
        target.status = WorkoutStatus.SKIPPED
        save(plan)
        return plan
    }

    /**
     * Realigns the remaining plan so the oldest overdue workout falls on today,
     * pushing every other pending session forward by the same number of days.
     * Returns the shifted plan, or null when there is nothing overdue to realign.
     */
    fun shiftPlanToToday(now: Long = System.currentTimeMillis()): TrainingPlan? {
        val plan = load() ?: return null
        val oldest = plan.overdueWorkouts(now).firstOrNull() ?: return null
        val days = oldest.daysLate(now)
        if (days <= 0) return null
        val shifted = plan.shiftedForward(days)
        save(shifted)
        return shifted
    }
}
