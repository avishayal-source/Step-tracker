package com.steptracker.app.qa

import com.steptracker.app.ActivityType
import com.steptracker.app.PlanScheduler
import com.steptracker.app.RunPlanCoach
import com.steptracker.app.TrainingPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test

/** Dated plan generation: structural invariants the Schedule tab and reminders rely on. */
class PlanSchedulerExtremeTest {

    // UTC keeps this suite about structure; calendar/DST behaviour lives in CalendarAndDstTest.
    @get:Rule val tz = TimeZoneRule("UTC")

    // Getter, not a field: fields initialise before TimeZoneRule switches the zone.
    private val start get() = localMidnight(2026, 10, 19)

    private fun allPlans(): List<Pair<String, TrainingPlan>> {
        val out = mutableListOf<Pair<String, TrainingPlan>>()
        for (l in listOf(0.0, 0.5, 3.0, 12.0)) for (t in listOf(1.0, 5.0, 10.0, 21.1, 42.2))
            for (d in 1..7) for (hz in listOf(1, 6, 12, 30)) for (age in listOf(25, 70)) {
                val p = profile(age = age, longestRunKm = l, days = d)
                val g = RunPlanCoach.Goal(t, hz)
                out += "longest=$l target=$t days=$d weeks=$hz age=$age" to buildPlan(p, g, start).second
            }
        return out
    }

    @Test
    fun weekdayOffsets_areDistinctAndInsideTheWeek() {
        for (d in -3..12) {
            val o = PlanScheduler.weekdayOffsets(d)
            assertEquals("days=$d", d.coerceIn(2, 6), o.size)
            assertEquals(o.distinct(), o)
            assertTrue(o.all { it in 0..6 })
        }
    }

    @Test
    fun everyGeneratedPlan_isStructurallyValid() {
        val problems = mutableListOf<String>()
        for ((tag, plan) in allPlans()) {
            val w = plan.workouts
            if (w.isEmpty()) problems += "no workouts: $tag"
            if (w.map { it.id }.toSet().size != w.size) problems += "duplicate ids: $tag"
            if (w.map { it.requestCode }.toSet().size != w.size) problems += "duplicate reminder request codes: $tag"
            if (w.zipWithNext().any { (a, b) -> a.dateMs > b.dateMs }) problems += "not sorted: $tag"
            if (w.groupBy { it.dateMs / DAY_MS }.any { it.value.size > 1 }) problems += "two workouts on one day: $tag"
            for (pw in w) {
                if (pw.items.isEmpty()) problems += "empty workout ${pw.title}: $tag"
                if (pw.items.any { it.durationMinutes <= 0 }) problems += "non-positive period ${pw.title}: $tag"
                if (pw.items.none { it.type == ActivityType.RUNNING }) problems += "no running in ${pw.title}: $tag"
                // A beginner marathon at 9 min/km is ~6.5 h; anything beyond 7 h is a bug.
                val total = pw.items.sumOf { it.durationMinutes }
                if (total > 420) problems += "${total} min session ${pw.title}: $tag"
                val weekStart = start + (pw.weekNumber - 1) * 7 * DAY_MS
                if (pw.dateMs !in weekStart until weekStart + 7 * DAY_MS) problems += "workout outside its week: $tag"
            }
            if (plan.totalWeeks <= 0) problems += "totalWeeks=${plan.totalWeeks}: $tag"
            if (!plan.goalLabel.contains("${plan.totalWeeks} week")) problems += "label '${plan.goalLabel}' ≠ ${plan.totalWeeks} weeks: $tag"
        }
        if (problems.isNotEmpty()) fail("${problems.size} problems, first 25:\n" + problems.take(25).joinToString("\n"))
    }

    @Test
    fun zeroWarmupAndCooldown_produceNoEmptyPeriods() {
        val (_, plan) = buildPlan(profile(), RunPlanCoach.Goal(5.0, 9), start, warmup = 0, cooldown = 0)
        assertTrue(plan.workouts.all { pw -> pw.items.all { it.durationMinutes > 0 } })
        assertTrue(plan.workouts.all { it.items.first().type == ActivityType.RUNNING })
    }

    @Test
    fun negativeWarmup_isNotWrittenIntoTheSchedule() {
        val (_, plan) = buildPlan(profile(), RunPlanCoach.Goal(5.0, 9), start, warmup = -5, cooldown = -5)
        assertTrue(plan.workouts.all { pw -> pw.items.all { it.durationMinutes > 0 } })
    }

    @Test
    fun preferContinuous_beginnerGetsOneRunBlock() {
        val p = profile(longestRunKm = 0.0)
        val g = RunPlanCoach.Goal(5.0, 9)
        val r = RunPlanCoach.evaluate(p, g)
        val plan = PlanScheduler.generate(r, p, g, start, 5, 5, preferContinuous = true)
        assertTrue(plan.workouts.all { pw -> pw.items.count { it.type == ActivityType.RUNNING } == 1 })
    }

    /**
     * Easy runs are min(longRun, (volume − longRun) / (days − 1)). With volume = longRun / 0.38
     * and only two running days that is always the long run, so the user gets two long runs a week.
     */
    @Test
    fun defect_easyRuns_areShorterThanTheLongRun() {
        val bad = mutableListOf<String>()
        for (d in 1..7) for (t in listOf(5.0, 10.0, 21.1)) {
            val (_, plan) = buildPlan(profile(longestRunKm = 3.0, days = d), RunPlanCoach.Goal(t, 16), start)
            plan.workouts.groupBy { it.weekNumber }.forEach { (wk, ws) ->
                val long = ws.first { it.isLongRun }.distanceKm
                ws.filter { !it.isLongRun && it.distanceKm >= long && long > 1.0 }.forEach {
                    bad += "days=$d target=$t week $wk: easy ${it.distanceKm} km vs long $long km"
                }
            }
        }
        assertTrue("${bad.size} easy runs as long as the long run, e.g.\n" + bad.take(8).joinToString("\n"), bad.isEmpty())
    }

    /** Reminder request codes are REQ_BASE + seq; a big plan must not collide with other alarm ids. */
    @Test
    fun reminderRequestCodes_stayInsideAReservedRange() {
        val (_, plan) = buildPlan(profile(longestRunKm = 3.0, days = 6), RunPlanCoach.Goal(42.2, 52), start)
        assertTrue(plan.workouts.all { it.requestCode in 80_000 until 90_000 })
    }
}
