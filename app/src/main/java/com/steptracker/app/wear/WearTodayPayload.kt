package com.steptracker.app.wear

import android.content.Context
import com.steptracker.app.TrainingPlanStore
import com.steptracker.app.UserPrefs
import org.json.JSONArray
import org.json.JSONObject

/**
 * Builds the M2 phone→watch JSON: next due workout periods + calibration strides.
 */
object WearTodayPayload {

    fun buildJson(context: Context): String {
        val prefs = UserPrefs(context)
        val store = TrainingPlanStore(context)
        val plan = store.loadReconciled()?.plan
        val workout = plan?.let { store.nextDueWorkout(it) }
            ?: plan?.let { store.previewWorkout(it, maxDaysAhead = 1) }

        val periods = JSONArray()
        workout?.items?.forEach { item ->
            periods.put(
                JSONObject()
                    .put("type", item.type.name)
                    .put("mins", item.durationMinutes)
            )
        }

        return JSONObject()
            .put("syncedAtMs", System.currentTimeMillis())
            .put("dateMs", workout?.dateMs ?: 0L)
            .put("workoutId", workout?.id ?: 0L)
            .put("title", workout?.title ?: "")
            .put("dueLabel", workout?.dueLabel() ?: "")
            .put("periods", periods)
            .put("walk_stride", prefs.walkStrideM)
            .put("run_stride", prefs.runStrideM)
            .put("calibrated", prefs.isCalibrated)
            .toString()
    }
}
