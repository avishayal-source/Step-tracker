package com.steptracker.app

import android.content.Context
import org.json.JSONObject

/**
 * Persists the single active [TrainingPlan] (JSON in SharedPreferences) and offers
 * helpers the Schedule tab uses to find / complete today's workout.
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

    /** The earliest not-done workout scheduled for [now]'s calendar day, if any. */
    fun todaysPendingWorkout(plan: TrainingPlan, now: Long = System.currentTimeMillis()): PlannedWorkout? =
        plan.workouts
            .filter { !it.done && it.isSameDay(now) }
            .minByOrNull { it.dateMs }

    /** The next not-done workout from [now] onward (today included). */
    fun nextWorkout(plan: TrainingPlan, now: Long = System.currentTimeMillis()): PlannedWorkout? =
        plan.workouts
            .filter { !it.done && PlannedWorkout.dayIndex(it.dateMs) >= PlannedWorkout.dayIndex(now) }
            .minByOrNull { it.dateMs }

    /** Marks today's pending workout done and persists. Returns the updated plan. */
    fun markTodayDone(now: Long = System.currentTimeMillis()): TrainingPlan? {
        val plan = load() ?: return null
        val target = todaysPendingWorkout(plan, now) ?: return plan
        target.done = true
        save(plan)
        return plan
    }
}
