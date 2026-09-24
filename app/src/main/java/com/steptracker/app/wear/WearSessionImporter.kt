package com.steptracker.app.wear

import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.wearable.Wearable
import com.steptracker.app.CaloriesCalculator
import com.steptracker.app.CoachProfile
import com.steptracker.app.TrainingPlanStore
import com.steptracker.app.WorkoutHistory
import com.steptracker.app.WorkoutRecord
import kotlinx.coroutines.tasks.await
import org.json.JSONObject
import kotlin.math.abs

/**
 * Imports a finished watch session into phone History and marks the plan item done.
 */
object WearSessionImporter {
    const val ACTION_IMPORTED = "com.steptracker.app.WATCH_SESSION_IMPORTED"

    private const val TAG = "WearSync"
    private const val PREFS = "wear_imported_sessions"
    private const val KEY_IDS = "ids"

    fun importJson(context: Context, json: String): Result? {
        val o = try {
            JSONObject(json)
        } catch (e: Exception) {
            Log.w(TAG, "bad session json", e)
            return null
        }
        val sid = o.optString("id")
        if (sid.isBlank()) return null
        if (alreadyImported(context, sid)) return Result(sid, newlyImported = false)

        val started = o.optLong("startedAtMs", System.currentTimeMillis())
        val steps = o.optInt("steps", 0)
        val distM = o.optDouble("distM", 0.0)
        val durationMs = o.optLong("durationMs", 0L)
        val walkSteps = o.optInt("walkSteps", steps)
        val runSteps = o.optInt("runSteps", 0)
        val walkDistM = o.optDouble("walkDistM", distM)
        val runDistM = o.optDouble("runDistM", 0.0)
        val walkDurationMs = o.optLong("walkDurationMs", durationMs)
        val runDurationMs = o.optLong("runDurationMs", 0L)

        val kcal = CaloriesCalculator.estimateKcal(
            weightKg = CoachProfile.weightKg(context),
            walkDurationMs = walkDurationMs,
            runDurationMs = runDurationMs,
            walkDistM = walkDistM,
            runDistM = runDistM
        )
        WorkoutHistory(context).save(
            WorkoutRecord(
                id = recordId(sid, started),
                dateMs = started,
                walkSteps = walkSteps,
                runSteps = runSteps,
                walkDistM = walkDistM,
                runDistM = runDistM,
                walkDurationMs = walkDurationMs,
                runDurationMs = runDurationMs,
                caloriesKcal = kcal
            )
        )

        val workoutId = o.optLong("workoutId", 0L)
        if (workoutId != 0L) {
            TrainingPlanStore(context).markDone(workoutId)
        }
        markImported(context, sid)
        context.sendBroadcast(
            Intent(ACTION_IMPORTED).setPackage(context.packageName)
        )
        Log.d(TAG, "Imported watch session $sid")
        return Result(sid, newlyImported = true)
    }

    data class Result(val id: String, val newlyImported: Boolean)

    suspend fun ack(context: Context, nodeId: String, sessionId: String) {
        if (nodeId.isBlank() || sessionId.isBlank()) return
        try {
            Wearable.getMessageClient(context).sendMessage(
                nodeId,
                WearSyncPaths.PATH_SESSION_ACK,
                sessionId.toByteArray(Charsets.UTF_8)
            ).await()
        } catch (e: Exception) {
            Log.w(TAG, "session ack failed", e)
        }
    }

    private fun recordId(sessionId: String, startedAtMs: Long): Long {
        val h = sessionId.hashCode().toLong()
        val mixed = (h shl 32) xor startedAtMs
        return if (mixed == 0L) abs(startedAtMs) else mixed
    }

    private fun alreadyImported(context: Context, id: String): Boolean {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_IDS, "") ?: ""
        return raw.split('\n').contains(id)
    }

    private fun markImported(context: Context, id: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_IDS, "") ?: ""
        val ids = (raw.split('\n').filter { it.isNotBlank() } + id).distinct().takeLast(200)
        prefs.edit().putString(KEY_IDS, ids.joinToString("\n")).apply()
    }
}
