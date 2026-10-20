package com.steptracker.app.qa

import com.steptracker.app.ActiveSession
import com.steptracker.app.ActivityPeriod
import com.steptracker.app.ActivityType
import com.steptracker.app.BackupManager
import com.steptracker.app.BatteryPrompt
import com.steptracker.app.RunPlanCoach
import com.steptracker.app.SavedSchedule
import com.steptracker.app.ScheduleItem
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test

/**
 * Regressions for the fourth QA round (second external report, ST-QA-xx ids): backup
 * validation before any change, no silent History cap, interrupted-workout snapshots,
 * and the battery prompt re-offer rule.
 */
class QaRound4RegressionTest {

    @get:Rule val tz = TimeZoneRule("Asia/Jerusalem")

    private val start get() = localMidnight(2026, 10, 19)

    private fun workoutJson(id: Long, steps: Int = 1000): JSONObject = JSONObject()
        .put("id", id).put("dateMs", start + id * 86_400_000L)
        .put("walkSteps", steps).put("runSteps", 0)
        .put("walkDistM", steps * 0.7).put("runDistM", 0.0)
        .put("walkDurationMs", 600_000L).put("runDurationMs", 0L)
        .put("caloriesKcal", 50)

    private fun backup(
        workouts: JSONArray = JSONArray().put(workoutJson(1)).put(workoutJson(2)),
        plan: Any = buildPlan(profile(), RunPlanCoach.Goal(5.0, 9), start).second.toJson(),
        schedules: JSONArray = JSONArray().put(
            SavedSchedule(7, "Intervals", listOf(ScheduleItem(ActivityType.WALKING, 5))).toJson()),
        version: Int = BackupManager.FORMAT_VERSION
    ): JSONObject = JSONObject()
        .put("formatVersion", version)
        .put("appVersionName", "1.0.19")
        .put("workouts", workouts)
        .put("trainingPlan", plan)
        .put("schedules", schedules)
        .put("userPrefs", JSONObject().put("walk_stride", 0.52).put("run_stride", 0.75)
            .put("walk_calibrated", true).put("run_calibrated", false).put("height_cm", 175.0))
        .put("coachPrefs", JSONObject().put("weight", "72,5").put("age", 41))

    private fun assertRejected(text: String, messagePart: String? = null) {
        try {
            BackupManager.parse(text)
            fail("Backup should have been rejected")
        } catch (e: BackupManager.InvalidBackupException) {
            if (messagePart != null) assertTrue(e.message, e.message!!.contains(messagePart))
        }
    }

    // ── ST-QA-01: validate everything before changing anything ───────────────

    @Test
    fun qa01_validBackup_parsesEverySection() {
        val p = BackupManager.parse(backup().toString())
        assertEquals(2, p.workouts.size)
        assertNotNull(p.plan)
        assertEquals(listOf("Intervals"), p.schedules.map { it.name })
        assertEquals(0.52, p.prefs!!.walkStrideM!!, 1e-9)
        assertEquals("72,5", p.coach!!["weight"])
        assertEquals("41", p.coach!!["age"])
    }

    @Test
    fun qa01_damagedSecondWorkout_rejectedBeforeAnyChange() {
        val bad = JSONArray().put(workoutJson(1)).put(JSONObject().put("id", 2).put("dateMs", "yesterday"))
        assertRejected(backup(workouts = bad).toString(), "Workout 2")
    }

    @Test
    fun qa01_damagedPlan_rejected() {
        assertRejected(backup(plan = JSONObject().put("workouts", "nope")).toString(), "Training plan")
        assertRejected(backup(plan = "a string").toString(), "Training plan")
    }

    @Test
    fun qa01_damagedSchedule_rejected() {
        val bad = JSONArray().put(JSONObject().put("id", 1).put("name", "x"))
        assertRejected(backup(schedules = bad).toString(), "schedule 1")
    }

    @Test
    fun qa01_impossibleValues_rejected() {
        val negative = JSONArray().put(workoutJson(1, steps = -5))
        assertRejected(backup(workouts = negative).toString())
        val nanDist = JSONArray().put(workoutJson(1).put("walkDistM", "NaN"))
        assertRejected(backup(workouts = nanDist).toString())
    }

    @Test
    fun qa01_wrongShapesAndVersions_rejected() {
        assertRejected(backup(version = 99).toString(), "version")
        assertRejected(backup().put("workouts", JSONObject()).toString(), "workouts")
        assertRejected(backup().toString().dropLast(40))
        assertRejected("not json at all")
        assertRejected(backup().put("userPrefs", JSONObject().put("height_cm", 9999)).toString(), "Calibration")
    }

    @Test
    fun qa01_missingOptionalSections_areFine() {
        val minimal = JSONObject().put("formatVersion", 1).toString()
        val p = BackupManager.parse(minimal)
        assertTrue(p.workouts.isEmpty())
        assertNull(p.plan)
        assertNull(p.prefs)
    }

    @Test
    fun legacyCalibratedFlag_restoresWalkingOnly() {
        val root = backup().put("userPrefs", JSONObject().put("calibrated", true))
        val prefs = BackupManager.parse(root.toString()).prefs!!
        assertEquals(true, prefs.walkCalibrated)
        assertNull(prefs.runCalibrated)
    }

    // ── ST-QA-03 / ST-QA-04: no silent cap, bounded input ───────────────────

    @Test
    fun qa03_moreThan365Workouts_allSurviveParsing() {
        val many = JSONArray().apply { for (i in 1L..500L) put(workoutJson(i)) }
        assertEquals(500, BackupManager.parse(backup(workouts = many).toString()).workouts.size)
    }

    @Test
    fun qa04_absurdWorkoutCount_rejected() {
        val tooMany = JSONArray().apply {
            val w = workoutJson(1)
            repeat(BackupManager.MAX_WORKOUTS + 1) { put(w) }
        }
        assertRejected(backup(workouts = tooMany).toString(), "too many")
    }

    // ── ST-QA-02: interrupted-workout snapshot ───────────────────────────────

    private fun session(): ActiveSession {
        val walk = ActivityPeriod(ActivityType.WALKING, 1_000L, endTime = 61_000L, steps = 80, walkStride = 0.5)
        val run = ActivityPeriod(ActivityType.RUNNING, 61_000L, steps = 120, runStride = 0.8).apply {
            engageGps(); gpsDistanceM = 55.0
        }
        return ActiveSession(1_000L, 300_000L, paused = false, totalSteps = 200,
            periods = listOf(walk, run), savedAtMs = 301_000L)
    }

    @Test
    fun qa02_snapshotRoundTrip_keepsDistanceInputs() {
        val back = ActiveSession.fromJson(ActiveSession.toJson(session()))!!
        assertEquals(session(), back)
        assertEquals(96.0 + 55.0, back.periods[1].distanceMeters, 1e-9)
    }

    @Test
    fun qa02_openPeriodClosedAtSnapshot_soDeadTimeIsNotActivity() {
        val closed = ActiveSession.closedAtSnapshot(session())
        assertEquals(61_000L, closed[0].endTime)
        assertEquals(301_000L, closed[1].endTime)
    }

    @Test
    fun qa02_corruptSnapshot_isIgnored() {
        assertNull(ActiveSession.fromJson("{\"start\":1}"))
        assertNull(ActiveSession.fromJson(""))
        assertNull(ActiveSession.fromJson(null))
    }

    // ── ST-QA-06: battery prompt ────────────────────────────────────────────

    @Test
    fun qa06_notNow_reoffersLater_butNotForever() {
        val day = 86_400_000L
        assertTrue(BatteryPrompt.shouldOffer(0, 0L, 10 * day))
        assertFalse(BatteryPrompt.shouldOffer(1, 10 * day, 11 * day))
        assertTrue(BatteryPrompt.shouldOffer(1, 10 * day, 24 * day))
        assertFalse(BatteryPrompt.shouldOffer(BatteryPrompt.MAX_DECLINES, 10 * day, 100 * day))
    }
}
