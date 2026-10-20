package com.steptracker.app.qa

import com.steptracker.app.ActivityPeriod
import com.steptracker.app.ActivityType
import com.steptracker.app.CalibrationMath
import com.steptracker.app.Format
import com.steptracker.app.SavedSchedule
import com.steptracker.app.ScheduleItem
import com.steptracker.app.ScheduleStore
import com.steptracker.app.ScheduleTiming
import com.steptracker.app.SensorClock
import com.steptracker.app.WorkoutHistory
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regressions for the third QA round (external deep-research report, ST-xx ids). Each
 * test pins the corrected behaviour of logic that was pulled out of the Android classes.
 */
class QaReportRegressionTest {

    private val min = 60_000L

    // ── ST-08: snapshot saved during the 3-2-1 countdown ─────────────────────

    @Test
    fun st08_restoreDuringCountdown_startsFirstPeriodInFull() {
        // Countdown saved with the first period due to start 2 s in the future.
        val pos = ScheduleTiming.positionAfter(listOf(5 * min, 3 * min), 0, elapsedInPeriodMs = -2_000L)
        assertEquals(ScheduleTiming.Position(0, 5 * min), pos)
    }

    @Test
    fun st08_oldZeroStart_wouldHaveCompletedTheRun() {
        // What a periodStartMs of 0 looked like: "elapsed" ≈ 56 years.
        val pos = ScheduleTiming.positionAfter(listOf(5 * min, 3 * min), 0, System.currentTimeMillis())
        assertNull(pos)
    }

    @Test
    fun resume_carriesOverflowIntoLaterPeriods() {
        val pos = ScheduleTiming.positionAfter(listOf(5 * min, 3 * min, 4 * min), 0, 6 * min)
        assertEquals(ScheduleTiming.Position(1, 2 * min), pos)
    }

    // ── ST-04: live edits of the current period ──────────────────────────────

    @Test
    fun st04_editAfterEarlierEdit_countsAllElapsedTime() {
        // 10-min period, 4 min done → edited to 12 min (8 left). Two minutes later the
        // countdown shows 6:00. Editing to 11 min must leave 11 − 6 = 5 min, not 9.
        val afterFirst = ScheduleTiming.remainingAfterEdit(10 * min, 6 * min, 12 * min)
        assertEquals(8 * min, afterFirst)
        val afterSecond = ScheduleTiming.remainingAfterEdit(12 * min, afterFirst - 2 * min, 11 * min)
        assertEquals(5 * min, afterSecond)
    }

    @Test
    fun st04_editWhilePaused_keepsTimeAlreadyDone() {
        // Paused with 3 min left of 10 → stretched to 12: 5 min left, not a fresh 12.
        assertEquals(5 * min, ScheduleTiming.remainingAfterEdit(10 * min, 3 * min, 12 * min))
    }

    @Test
    fun st04_shrinkingBelowElapsed_leavesShortGrace() {
        assertEquals(ScheduleTiming.MIN_REMAINING_AFTER_EDIT_MS,
            ScheduleTiming.remainingAfterEdit(10 * min, 2 * min, 5 * min))
    }

    @Test
    fun st04_savedStart_ignoresPausedTime() {
        // 10-min period with 4 min left: restore must think it started 6 min ago,
        // however long the user was paused in between.
        val now = 1_000_000_000L
        val start = ScheduleTiming.effectiveStart(now, 10 * min, 4 * min)
        assertEquals(ScheduleTiming.Position(0, 4 * min),
            ScheduleTiming.positionAfter(listOf(10 * min), 0, now - start))
    }

    // ── ST-15: saved schedules ───────────────────────────────────────────────

    private fun sched(id: Long, name: String) =
        SavedSchedule(id, name, listOf(ScheduleItem(ActivityType.WALKING, 5)))

    private fun storeWithOneBadEntry(): String = JSONArray()
        .put(sched(1, "Good A").toJson())
        .put(org.json.JSONObject().put("id", 2).put("name", "Broken"))   // no items
        .put(sched(3, "Good B").toJson())
        .toString()

    @Test
    fun st15_oneCorruptSchedule_doesNotHideTheOthers() {
        val names = ScheduleStore.parse(storeWithOneBadEntry()).map { it.name }
        assertEquals(listOf("Good A", "Good B"), names)
    }

    @Test
    fun st15_savingNewSchedule_keepsUnreadableEntries() {
        val json = ScheduleStore.upsert(storeWithOneBadEntry(), sched(4, "New"))
        assertEquals(4, JSONArray(json).length())
        assertEquals(listOf("Good A", "Good B", "New"), ScheduleStore.parse(json).map { it.name })
    }

    @Test
    fun st15_deleting_onlyRemovesThatSchedule() {
        val json = ScheduleStore.remove(storeWithOneBadEntry(), 1)
        assertEquals(2, JSONArray(json).length())
        assertEquals(listOf("Good B"), ScheduleStore.parse(json).map { it.name })
    }

    @Test
    fun st15_upsertReplacesById() {
        val json = ScheduleStore.upsert(storeWithOneBadEntry(), sched(3, "Renamed"))
        assertEquals(listOf("Good A", "Renamed"), ScheduleStore.parse(json).map { it.name })
    }

    @Test
    fun st15_garbageRoot_parsesToEmptyWithoutThrowing() {
        assertTrue(ScheduleStore.parse("{not json").isEmpty())
    }

    @Test
    fun st15_legacyHistory_skipsOnlyTheBadRecord() {
        val good = """{"id":1,"date":1000,"wSteps":10,"rSteps":0,"wDist":7.5,"rDist":0,"wDur":60000,"rDur":0}"""
        val bad = """{"id":2,"date":"yesterday"}"""
        val good2 = good.replace("\"id\":1", "\"id\":3")
        val records = WorkoutHistory.parseLegacy("[$good,$bad,$good2]")
        assertEquals(listOf(1L, 3L), records.map { it.id })
    }

    // ── ST-05: GPS baseline ──────────────────────────────────────────────────

    @Test
    fun st05_baselineTakenAtAnchorFix_noDoubleCount() {
        val p = ActivityPeriod(ActivityType.WALKING, 0L, walkStride = 0.7)
        p.steps = 100                    // 70 m walked before the first GPS fix
        p.engageGps()                    // first fix = anchor
        p.steps = 120                    // 14 m more until the second fix…
        p.gpsDistanceM += 14.0           // …which GPS measures as the first delta
        assertEquals(84.0, p.distanceMeters, 1e-9)
    }

    @Test
    fun st05_engageIsIdempotent() {
        val p = ActivityPeriod(ActivityType.WALKING, 0L, walkStride = 0.7)
        p.steps = 10; p.engageGps()
        p.steps = 50; p.engageGps()
        assertEquals(7.0, p.stepDistanceAtGpsStartM, 1e-9)
    }

    // ── ST-12: calibration GPS filter ────────────────────────────────────────

    @Test
    fun st12_bounceRejected_walkAndJogAccepted() {
        assertTrue(CalibrationMath.isGpsBounce(30.0, 0.5))     // 60 m/s
        assertFalse(CalibrationMath.isGpsBounce(0.8, 0.5))     // brisk walk
        assertFalse(CalibrationMath.isGpsBounce(2.0, 0.5))     // jog
        assertTrue(CalibrationMath.isGpsBounce(5.0, 0.0))      // same timestamp
        assertFalse(CalibrationMath.countsAsGpsDistance(0.2))
        assertTrue(CalibrationMath.countsAsGpsDistance(1.0))
    }

    // ── ST-13: decimal comma ─────────────────────────────────────────────────

    @Test
    fun st13_decimalCommaAccepted() {
        assertEquals(52.5, Format.parseDecimal("52,5")!!, 1e-9)
        assertEquals(52.5, Format.parseDecimal(" 52.5 ")!!, 1e-9)
        assertEquals(175.0, Format.parseDecimal("175")!!, 1e-9)
        assertNull(Format.parseDecimal(""))
        assertNull(Format.parseDecimal("abc"))
        assertNull(Format.parseDecimal("NaN"))
        assertNull(Format.parseDecimal(null))
    }

    @Test
    fun st13_commaDistance_givesStride() {
        assertNotNull(CalibrationMath.strideFromManual(60, Format.parseDecimal("45,5")))
    }

    // ── ST-16: sensor clock ──────────────────────────────────────────────────

    @Test
    fun st16_wallClockJumpAfterSettling_doesNotStretchStepGaps() {
        val clock = SensorClock(settleSamples = 10)
        var bootNs = 5_000_000_000L
        var wall = 1_700_000_000_000L
        repeat(10) { clock.toWallMs(bootNs, wall); bootNs += 20_000_000L; wall += 20 }
        val before = clock.toWallMs(bootNs, wall)
        // Wall clock jumps an hour (NTP / manual change); sensor time moves 500 ms.
        val after = clock.toWallMs(bootNs + 500_000_000L, wall + 3_600_000L)
        assertEquals(500L, after - before)
    }
}
