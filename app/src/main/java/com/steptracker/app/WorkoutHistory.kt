package com.steptracker.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Facade over the Room `workouts` table. Keeps the original synchronous API
 * (loadAll / save / clearAll) so existing callers need no changes, and adds:
 *   - delete(id)            : remove a single workout cleanly
 *   - analytics helpers     : avg speed, frequency, totals (power AI features)
 *   - one-time migration    : imports legacy SharedPreferences JSON into Room
 */
class WorkoutHistory(context: Context) {

    private val dao = AppDatabase.get(context).workoutDao()
    private val legacyPrefs = context.getSharedPreferences("workout_history", Context.MODE_PRIVATE)

    init {
        migrateLegacyJsonIfNeeded()
    }

    // ── CRUD (unchanged public API) ──────────────────────────────────────────
    fun loadAll(): List<WorkoutRecord> = dao.getAll()

    fun save(r: WorkoutRecord) {
        dao.insert(r)
        dao.trimToMostRecent(365)   // keep last 365 sessions, as before
    }

    fun delete(id: Long) = dao.deleteById(id)

    fun clearAll() = dao.clearAll()

    // ── Analytics (new — for insights / NL queries) ─────────────────────────
    /** Average running speed over the last N workouts, in km/h (null if no run data). */
    fun avgRunningSpeedKmh(lastN: Int = 10): Double? =
        dao.avgRunningSpeedMps(lastN)?.let { it * 3.6 }

    /** Average walking speed over the last N workouts, in km/h (null if no walk data). */
    fun avgWalkingSpeedKmh(lastN: Int = 10): Double? =
        dao.avgWalkingSpeedMps(lastN)?.let { it * 3.6 }

    /** How many workouts in the last [months] months. */
    fun workoutCountLastMonths(months: Int): Int {
        val sinceMs = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(30L * months)
        return dao.countSince(sinceMs)
    }

    fun totalDistanceM(): Double = dao.totalDistanceM()
    fun longestDistanceM(): Double = dao.longestDistanceM()
    fun runDistanceLastMonths(months: Int): Double {
        val sinceMs = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(30L * months)
        return dao.runDistanceSince(sinceMs)
    }

    // ── One-time migration from the old SharedPreferences JSON ───────────────
    private fun migrateLegacyJsonIfNeeded() {
        val json = legacyPrefs.getString(LEGACY_KEY, null) ?: return
        try {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                dao.insert(fromLegacyJson(arr.getJSONObject(i)))
            }
        } catch (_: Exception) {
            // Corrupt legacy data — drop it rather than crash.
        } finally {
            // Remove the legacy blob so migration runs exactly once.
            legacyPrefs.edit().remove(LEGACY_KEY).apply()
        }
    }

    private fun fromLegacyJson(o: JSONObject) = WorkoutRecord(
        id             = o.optLong("id", System.currentTimeMillis()),
        dateMs         = o.getLong("date"),
        walkSteps      = o.getInt("wSteps"),
        runSteps       = o.getInt("rSteps"),
        walkDistM      = o.getDouble("wDist"),
        runDistM       = o.getDouble("rDist"),
        walkDurationMs = o.getLong("wDur"),
        runDurationMs  = o.getLong("rDur")
    )

    companion object {
        private const val LEGACY_KEY = "records"
    }
}
