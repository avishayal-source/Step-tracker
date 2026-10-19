package com.steptracker.app.qa

import com.steptracker.app.ActivityType
import com.steptracker.app.PlannedWorkout
import com.steptracker.app.RunPlanCoach
import com.steptracker.app.ScheduleItem
import com.steptracker.app.TrainingPlan
import com.steptracker.app.WorkoutStatus
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * The active plan lives as JSON in SharedPreferences and travels inside backups.
 * TrainingPlanStore.load() returns null on any parse error, so a parse failure means
 * the user silently loses their whole plan.
 */
class PlanPersistenceRobustnessTest {

    @get:Rule val tz = TimeZoneRule("UTC")

    // Getter, not a field: fields initialise before TimeZoneRule switches the zone.
    private val start get() = localMidnight(2026, 10, 19)

    private fun samplePlan(): TrainingPlan =
        buildPlan(profile(), RunPlanCoach.Goal(5.0, 9), start).second

    @Test
    fun roundTrip_preservesEverything() {
        val plan = samplePlan()
        plan.workouts[0].status = WorkoutStatus.DONE
        plan.workouts[0].completedAtMs = start + 3_600_000
        plan.workouts[1].status = WorkoutStatus.SKIPPED
        val back = TrainingPlan.fromJson(JSONObject(plan.toJson().toString()))
        assertEquals(plan.copy(createdMs = back.createdMs), back)
    }

    @Test
    fun unicodeAndHostileStrings_surviveRoundTrip() {
        val nasty = listOf("ריצה קלה 🏃", "\"quoted\" \\ back\\slash", "line\nbreak\ttab", "</script>", "\u0000nul", "x".repeat(10_000))
        for (s in nasty) {
            val w = PlannedWorkout(1, 1, start, 1, s, s, 1.0, false, listOf(ScheduleItem(ActivityType.RUNNING, 5)))
            val plan = TrainingPlan(0, s, 3, 3, listOf(w))
            val back = TrainingPlan.fromJson(JSONObject(plan.toJson().toString()))
            assertEquals(s, back.goalLabel)
            assertEquals(s, back.workouts[0].title)
        }
    }

    @Test
    fun legacyDoneFlag_isMigrated() {
        val o = samplePlan().toJson()
        val arr = o.getJSONArray("workouts")
        for (i in 0 until arr.length()) {
            val w = arr.getJSONObject(i)
            w.remove("status"); w.remove("completedAt")
            w.put("done", i == 0)
        }
        val back = TrainingPlan.fromJson(o)
        assertEquals(WorkoutStatus.DONE, back.workouts[0].status)
        assertTrue(back.workouts.drop(1).all { it.status == WorkoutStatus.PENDING })
    }

    @Test
    fun unknownStatus_fallsBackToPending() {
        val o = samplePlan().toJson()
        o.getJSONArray("workouts").getJSONObject(0).put("status", "POSTPONED")
        assertEquals(WorkoutStatus.PENDING, TrainingPlan.fromJson(o).workouts[0].status)
    }

    @Test
    fun missingOptionalFields_useDefaults() {
        val o = JSONObject("""{"created":1,"goal":"5 km in 9 weeks","workouts":[
            {"id":1,"rc":80000,"date":$start,"week":1,"title":"t","items":[{"type":"RUNNING","mins":10}]}]}""")
        val p = TrainingPlan.fromJson(o)
        assertEquals(3, p.warmupMin)
        assertEquals(0L, p.startDateMs)
        assertEquals(WorkoutStatus.PENDING, p.workouts[0].status)
    }

    @Test
    fun bigPlan_serialisesQuickly() {
        val big = buildPlan(profile(longestRunKm = 3.0, days = 6), RunPlanCoach.Goal(42.2, 52), start).second
        val t0 = System.nanoTime()
        repeat(50) { TrainingPlan.fromJson(JSONObject(big.toJson().toString())) }
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue("50 round-trips took $ms ms", ms < 3_000)
    }

    /**
     * A backup written by a newer build (new period type) or a hand-edited file must not
     * wipe the entire plan; the unknown period should be skipped or mapped.
     */
    @Test
    fun defect_unknownActivityType_doesNotDiscardWholePlan() {
        val o = samplePlan().toJson()
        o.getJSONArray("workouts").getJSONObject(0).getJSONArray("items")
            .getJSONObject(0).put("type", "STRENGTH")
        val parsed = runCatching { TrainingPlan.fromJson(o) }
        assertTrue("one unknown period type throws ${parsed.exceptionOrNull()} → whole plan lost", parsed.isSuccess)
    }
}
