package com.steptracker.app

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * User-owned JSON backup: export/import via the system file picker.
 * Does not transmit data to any server. Legal consent is never restored —
 * onboarding still applies on a fresh install.
 */
object BackupManager {

    const val FORMAT_VERSION = 1
    private const val MIME = "application/json"

    val mimeType: String get() = MIME

    fun suggestedFileName(): String {
        val day = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        return "YWalk-backup-$day.json"
    }

    data class Summary(
        val workoutCount: Int,
        val hasPlan: Boolean,
        val scheduleCount: Int,
        val appVersionName: String
    )

    fun buildExportJson(context: Context): String {
        val history = WorkoutHistory(context)
        val planStore = TrainingPlanStore(context)
        val schedules = ScheduleStore(context)
        val userPrefs = UserPrefs(context)
        val coach = SecurePrefs.open(context, "coach_prefs")

        val workoutsArr = JSONArray()
        for (w in history.loadAll()) {
            workoutsArr.put(JSONObject().apply {
                put("id", w.id)
                put("dateMs", w.dateMs)
                put("walkSteps", w.walkSteps)
                put("runSteps", w.runSteps)
                put("walkDistM", w.walkDistM)
                put("runDistM", w.runDistM)
                put("walkDurationMs", w.walkDurationMs)
                put("runDurationMs", w.runDurationMs)
            })
        }

        val coachObj = JSONObject()
        for (key in COACH_KEYS) {
            coach.getString(key, null)?.let { coachObj.put(key, it) }
        }

        val root = JSONObject().apply {
            put("formatVersion", FORMAT_VERSION)
            put("appVersionName", BuildConfig.VERSION_NAME)
            put("appVersionCode", BuildConfig.VERSION_CODE)
            put("exportedAtMs", System.currentTimeMillis())
            put("workouts", workoutsArr)
            put("trainingPlan", planStore.load()?.toJson() ?: JSONObject.NULL)
            val schedArr = JSONArray()
            schedules.loadAll().forEach { schedArr.put(it.toJson()) }
            put("schedules", schedArr)
            put("userPrefs", JSONObject().apply {
                put("walk_stride", userPrefs.walkStrideM)
                put("run_stride", userPrefs.runStrideM)
                put("calibrated", userPrefs.isCalibrated)
            })
            put("coachPrefs", coachObj)
        }
        return root.toString(2)
    }

    fun exportToUri(context: Context, uri: Uri) {
        val json = buildExportJson(context)
        context.contentResolver.openOutputStream(uri)?.use { out ->
            out.write(json.toByteArray(Charsets.UTF_8))
            out.flush()
        } ?: error("Could not open export location")
    }

    fun peekSummary(context: Context, uri: Uri): Summary {
        val root = readJson(context, uri)
        requireFormat(root)
        val workouts = root.optJSONArray("workouts") ?: JSONArray()
        val schedules = root.optJSONArray("schedules") ?: JSONArray()
        val hasPlan = root.opt("trainingPlan") != null &&
            root.opt("trainingPlan") != JSONObject.NULL
        return Summary(
            workoutCount = workouts.length(),
            hasPlan = hasPlan,
            scheduleCount = schedules.length(),
            appVersionName = root.optString("appVersionName", "?")
        )
    }

    /**
     * Replaces local workouts, plan, schedules, calibration, and coach form fields.
     * Clears any in-progress schedule run. Re-schedules plan reminders.
     * Does not touch LegalConsent.
     */
    fun importFromUri(context: Context, uri: Uri): Summary {
        val root = readJson(context, uri)
        requireFormat(root)

        val history = WorkoutHistory(context)
        val planStore = TrainingPlanStore(context)
        val scheduleStore = ScheduleStore(context)
        val userPrefs = UserPrefs(context)
        val coach = SecurePrefs.open(context, "coach_prefs")

        // Cancel reminders for the plan we're about to replace
        planStore.load()?.let { WorkoutReminderReceiver.cancelAll(context, it) }

        // Workouts
        history.clearAll()
        val workouts = root.optJSONArray("workouts") ?: JSONArray()
        for (i in 0 until workouts.length()) {
            val o = workouts.getJSONObject(i)
            history.save(
                WorkoutRecord(
                    id = o.optLong("id", System.currentTimeMillis() + i),
                    dateMs = o.getLong("dateMs"),
                    walkSteps = o.getInt("walkSteps"),
                    runSteps = o.getInt("runSteps"),
                    walkDistM = o.getDouble("walkDistM"),
                    runDistM = o.getDouble("runDistM"),
                    walkDurationMs = o.getLong("walkDurationMs"),
                    runDurationMs = o.getLong("runDurationMs")
                )
            )
        }

        // Training plan
        planStore.clear()
        val planNode = root.opt("trainingPlan")
        if (planNode != null && planNode != JSONObject.NULL) {
            val plan = TrainingPlan.fromJson(planNode as JSONObject)
            planStore.save(plan)
            WorkoutReminderReceiver.scheduleAll(context, plan)
        }

        // Saved schedules
        val schedArr = root.optJSONArray("schedules") ?: JSONArray()
        val restoredSchedules = (0 until schedArr.length()).map {
            SavedSchedule.fromJson(schedArr.getJSONObject(it))
        }
        scheduleStore.replaceAll(restoredSchedules)

        // Calibration
        val up = root.optJSONObject("userPrefs")
        if (up != null) {
            if (up.has("walk_stride")) userPrefs.walkStrideM = up.getDouble("walk_stride")
            if (up.has("run_stride")) userPrefs.runStrideM = up.getDouble("run_stride")
            if (up.has("calibrated")) userPrefs.isCalibrated = up.getBoolean("calibrated")
        }

        // Coach form
        val cp = root.optJSONObject("coachPrefs")
        if (cp != null) {
            val ed = coach.edit()
            for (key in COACH_KEYS) {
                if (cp.has(key)) ed.putString(key, cp.getString(key))
            }
            ed.apply()
        }

        // Don't resume a half-finished schedule from another device/session
        ScheduleRunPersistence(context).clear()

        return Summary(
            workoutCount = workouts.length(),
            hasPlan = planStore.hasActivePlan(),
            scheduleCount = restoredSchedules.size,
            appVersionName = root.optString("appVersionName", "?")
        )
    }

    private fun readJson(context: Context, uri: Uri): JSONObject {
        val text = context.contentResolver.openInputStream(uri)?.use { input ->
            BufferedReader(InputStreamReader(input, Charsets.UTF_8)).readText()
        } ?: error("Could not read backup file")
        return JSONObject(text)
    }

    private fun requireFormat(root: JSONObject) {
        val v = root.optInt("formatVersion", -1)
        if (v < 1 || v > FORMAT_VERSION) {
            error("Unsupported backup format (version $v)")
        }
    }

    private val COACH_KEYS = listOf(
        "distance", "weeks", "time", "age", "sex", "weight", "height", "currentRun", "days"
    )
}
