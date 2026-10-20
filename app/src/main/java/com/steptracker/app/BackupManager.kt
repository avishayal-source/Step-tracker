package com.steptracker.app

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
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
                put("caloriesKcal", w.caloriesKcal)
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
                put("walk_calibrated", userPrefs.walkCalibrated)
                put("run_calibrated", userPrefs.runCalibrated)
                put("height_cm", userPrefs.heightCm.toDouble())
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

    /** Calibration fields from a backup; null means "not in the file, leave as is". */
    data class PrefsPart(
        val walkStrideM: Double?,
        val runStrideM: Double?,
        val walkCalibrated: Boolean?,
        val runCalibrated: Boolean?,
        val heightCm: Float?
    )

    /** A fully parsed and validated backup. Building one never touches local data. */
    data class Parsed(
        val workouts: List<WorkoutRecord>,
        val plan: TrainingPlan?,
        val schedules: List<SavedSchedule>,
        val prefs: PrefsPart?,
        val coach: Map<String, String>?,
        val appVersionName: String
    ) {
        val summary get() = Summary(workouts.size, plan != null, schedules.size, appVersionName)
    }

    /** Parses and validates a backup without changing anything (used for the confirm dialog). */
    fun peekSummary(context: Context, uri: Uri): Summary = parse(readText(context, uri)).summary

    /**
     * Replaces local workouts, plan, schedules, calibration, and coach form fields.
     * Clears any in-progress schedule run. Re-schedules plan reminders.
     * Does not touch LegalConsent.
     *
     * The whole file is parsed and validated before anything local changes, and workouts
     * are swapped in one database transaction, so a bad backup leaves existing data intact.
     * Blocking: call off the main thread.
     */
    fun importFromUri(context: Context, uri: Uri): Summary {
        val parsed = parse(readText(context, uri))

        val history = WorkoutHistory(context)
        val planStore = TrainingPlanStore(context)
        val userPrefs = UserPrefs(context)

        history.replaceAll(parsed.workouts)

        planStore.load()?.let { WorkoutReminderReceiver.cancelAll(context, it) }
        if (parsed.plan != null) {
            planStore.save(parsed.plan)
            WorkoutReminderReceiver.scheduleAll(context, parsed.plan)
        } else {
            planStore.clear()
        }

        ScheduleStore(context).replaceAll(parsed.schedules)

        parsed.prefs?.let { p ->
            p.walkStrideM?.let { userPrefs.walkStrideM = it }
            p.runStrideM?.let { userPrefs.runStrideM = it }
            p.walkCalibrated?.let { userPrefs.walkCalibrated = it }
            p.runCalibrated?.let { userPrefs.runCalibrated = it }
            p.heightCm?.let { userPrefs.heightCm = it }
        }

        parsed.coach?.let { values ->
            val ed = SecurePrefs.open(context, "coach_prefs").edit()
            values.forEach { (k, v) -> ed.putString(k, v) }
            ed.apply()
        }

        // Don't resume a half-finished schedule from another device/session
        ScheduleRunPersistence(context).clear()

        return parsed.summary.copy(hasPlan = planStore.hasActivePlan())
    }

    /** Larger than any real backup (a year of daily workouts is ~150 KB). */
    const val MAX_BACKUP_BYTES = 10L * 1024 * 1024
    const val MAX_WORKOUTS = 20_000
    const val MAX_SCHEDULES = 1_000

    private fun readText(context: Context, uri: Uri): String {
        val input = context.contentResolver.openInputStream(uri) ?: error("Could not read backup file")
        return input.use { s ->
            val bytes = s.readNBytesCompat(MAX_BACKUP_BYTES + 1)
            if (bytes.size > MAX_BACKUP_BYTES) throw InvalidBackupException("Backup file is too large")
            String(bytes, Charsets.UTF_8)
        }
    }

    private fun java.io.InputStream.readNBytesCompat(limit: Long): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        var total = 0L
        while (total < limit) {
            val n = read(buf, 0, minOf(buf.size.toLong(), limit - total).toInt())
            if (n < 0) break
            out.write(buf, 0, n)
            total += n
        }
        return out.toByteArray()
    }

    /** The backup was rejected before anything local was changed. */
    class InvalidBackupException(message: String) : Exception(message)

    /** Throws [InvalidBackupException] with a readable message if any part is unusable. */
    fun parse(text: String): Parsed = try {
        parseUnchecked(text)
    } catch (e: InvalidBackupException) {
        throw e
    } catch (e: Exception) {
        throw InvalidBackupException(e.message ?: "Backup file is damaged")
    }

    private fun parseUnchecked(text: String): Parsed {
        val root = try { JSONObject(text) } catch (_: Exception) { error("Not a Y Walk backup file") }
        val v = root.optInt("formatVersion", -1)
        if (v < 1 || v > FORMAT_VERSION) error("Unsupported backup format (version $v)")

        val workoutsArr = optArray(root, "workouts")
        if (workoutsArr.length() > MAX_WORKOUTS) error("Backup has too many workouts")
        val workouts = (0 until workoutsArr.length()).map { i ->
            try { workoutOf(workoutsArr.getJSONObject(i), i) }
            catch (e: Exception) { error("Workout ${i + 1} is damaged") }
        }

        val planNode = root.opt("trainingPlan")
        val plan = when (planNode) {
            null, JSONObject.NULL -> null
            is JSONObject -> try { TrainingPlan.fromJson(planNode) } catch (_: Exception) {
                error("Training plan is damaged")
            }
            else -> error("Training plan is damaged")
        }

        val schedArr = optArray(root, "schedules")
        if (schedArr.length() > MAX_SCHEDULES) error("Backup has too many schedules")
        val schedules = (0 until schedArr.length()).map { i ->
            try { SavedSchedule.fromJson(schedArr.getJSONObject(i)) }
            catch (_: Exception) { error("Saved schedule ${i + 1} is damaged") }
        }

        val prefs = root.optJSONObject("userPrefs")?.let { up ->
            try {
                PrefsPart(
                    walkStrideM = up.positiveOrNull("walk_stride"),
                    runStrideM = up.positiveOrNull("run_stride"),
                    walkCalibrated = when {
                        up.has("walk_calibrated") -> up.getBoolean("walk_calibrated")
                        up.has("calibrated") -> up.getBoolean("calibrated")
                        else -> null
                    },
                    runCalibrated = if (up.has("run_calibrated")) up.getBoolean("run_calibrated") else null,
                    heightCm = if (up.has("height_cm")) up.getDouble("height_cm").toFloat()
                        .takeIf { it == 0f || it in 50f..300f } ?: error("height") else null
                )
            } catch (_: Exception) { error("Calibration settings are damaged") }
        }

        val coach = root.optJSONObject("coachPrefs")?.let { cp ->
            COACH_KEYS.filter { cp.has(it) }.associateWith { cp.get(it).toString() }
        }

        return Parsed(workouts, plan, schedules, prefs, coach, root.optString("appVersionName", "?"))
    }

    private fun optArray(root: JSONObject, key: String): JSONArray = when (val node = root.opt(key)) {
        null, JSONObject.NULL -> JSONArray()
        is JSONArray -> node
        else -> error("Backup section \"$key\" is damaged")
    }

    private fun JSONObject.positiveOrNull(key: String): Double? {
        if (!has(key)) return null
        val d = getDouble(key)
        require(d.isFinite() && d > 0)
        return d
    }

    private fun workoutOf(o: JSONObject, index: Int): WorkoutRecord {
        val r = WorkoutRecord(
            id = if (o.has("id")) o.getLong("id") else System.currentTimeMillis() + index,
            dateMs = o.getLong("dateMs"),
            walkSteps = o.getInt("walkSteps"),
            runSteps = o.getInt("runSteps"),
            walkDistM = o.getDouble("walkDistM"),
            runDistM = o.getDouble("runDistM"),
            walkDurationMs = o.getLong("walkDurationMs"),
            runDurationMs = o.getLong("runDurationMs"),
            caloriesKcal = o.optInt("caloriesKcal", 0)
        )
        require(r.dateMs > 0)
        require(r.walkSteps >= 0 && r.runSteps >= 0)
        require(r.walkDistM.isFinite() && r.runDistM.isFinite() && r.walkDistM >= 0 && r.runDistM >= 0)
        require(r.walkDurationMs >= 0 && r.runDurationMs >= 0)
        return r
    }

    private val COACH_KEYS = listOf(
        "distance", "weeks", "time", "age", "sex", "weight", "height", "currentRun", "days"
    )
}
