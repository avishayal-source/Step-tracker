package com.steptracker.app.qa

import com.steptracker.app.RunPlanCoach
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Botty / RunPlanCoach under every input a user can actually type into the Coach form
 * (CoachView validates age 10–100, weight 25–300, height 100–230, days 1–7, and only
 * "> 0" for distance and weeks), plus a few values the form does not block.
 */
class CoachExtremeCasesTest {

    private val ages = listOf(10, 17, 50, 65, 100)
    private val weights = listOf(25.0, 70.0, 140.0, 300.0)
    private val heights = listOf(100.0, 175.0, 230.0)
    private val days = listOf(1, 2, 7)
    private val longest = listOf(-5.0, 0.0, 0.99, 1.0, 5.0, 42.2)
    private val targets = listOf(0.1, 1.0, 5.0, 5.01, 10.0, 21.1, 42.2)
    private val horizons = listOf(1, 2, 8, 16, 52)

    @Test
    fun everyUiReachableCombination_producesASanePlan() {
        var cases = 0
        val problems = mutableListOf<String>()
        for (a in ages) for (w in weights) for (h in heights) for (d in days)
            for (l in longest) for (t in targets) for (hz in horizons) {
                cases++
                val p = profile(age = a, weightKg = w, heightCm = h, longestRunKm = l, days = d)
                val g = RunPlanCoach.Goal(t, hz)
                val r = try {
                    RunPlanCoach.evaluate(p, g)
                } catch (e: Throwable) {
                    problems += "THROW $p $g → $e"; continue
                }
                val tag = "age=$a w=$w h=$h d=$d longest=$l target=$t weeks=$hz"
                if (r.plan.isEmpty()) problems += "empty plan: $tag"
                r.plan.forEachIndexed { i, wk ->
                    if (wk.week != i + 1) problems += "week numbering gap at ${wk.week}: $tag"
                    if (!wk.longRunKm.isFinite() || wk.longRunKm < 0) problems += "bad longRun ${wk.longRunKm}: $tag"
                    if (!wk.weeklyVolumeKm.isFinite() || wk.weeklyVolumeKm < 0) problems += "bad volume: $tag"
                    if (wk.runningDays !in 1..7) problems += "runningDays ${wk.runningDays}: $tag"
                }
                if (!r.bmi.isFinite()) problems += "bmi not finite: $tag"
                if (r.recommendedHorizonWeeks < r.requestedHorizonWeeks) problems += "recommended < requested: $tag"
                if (r.headline.isBlank() || r.rationale.isEmpty()) problems += "no explanation: $tag"
            }
        if (problems.isNotEmpty()) fail("${problems.size}/$cases problems, first 20:\n" + problems.take(20).joinToString("\n"))
    }

    @Test
    fun weeklyLongRunNeverJumpsUnsafely() {
        val problems = mutableListOf<String>()
        for (l in longest) for (t in targets) for (hz in horizons) for (a in listOf(30, 70)) {
            val r = RunPlanCoach.evaluate(profile(age = a, longestRunKm = l), RunPlanCoach.Goal(t, hz))
            r.plan.zipWithNext().forEach { (prev, next) ->
                // Ladder: +1 km or +50% early on; ramp: ≤ ~15%/week. Generous upper bound.
                val limit = maxOf(prev.longRunKm * 1.5, prev.longRunKm + 1.0)
                if (next.longRunKm > limit + 0.05)
                    problems += "longest=$l target=$t weeks=$hz: week ${prev.week} ${prev.longRunKm} → ${next.longRunKm}"
            }
        }
        assertTrue(problems.take(20).joinToString("\n"), problems.isEmpty())
    }

    @Test
    fun highRiskProfiles_alwaysGetMedicalCaution() {
        val obese = RunPlanCoach.evaluate(profile(weightKg = 130.0, heightCm = 170.0), RunPlanCoach.Goal(5.0, 12))
        assertTrue(obese.cautions.any { it.contains("doctor", true) || it.contains("medical", true) })
        val senior = RunPlanCoach.evaluate(profile(age = 80), RunPlanCoach.Goal(5.0, 12))
        assertTrue(senior.cautions.any { it.contains("medical clearance", true) })
        val under = RunPlanCoach.evaluate(profile(weightKg = 40.0, heightCm = 180.0), RunPlanCoach.Goal(5.0, 12))
        assertTrue(under.cautions.any { it.contains("underweight", true) })
    }

    @Test
    fun beginnerLadder_endsExactlyOnTheGoal() {
        for (t in listOf(2.0, 5.0, 8.0, 10.0)) for (hz in horizons) {
            val r = RunPlanCoach.evaluate(profile(longestRunKm = 0.0), RunPlanCoach.Goal(t, hz))
            assertEquals("target=$t weeks=$hz", t, r.plan.last().longRunKm, 0.05)
        }
    }

    @Test
    fun notRealisticGoal_offersAShorterSafeTarget() {
        val r = RunPlanCoach.evaluate(profile(longestRunKm = 5.0), RunPlanCoach.Goal(42.2, 4))
        assertEquals(RunPlanCoach.Verdict.NOT_REALISTIC, r.verdict)
        assertTrue(r.safeTargetInHorizonKm in 5.0..42.2)
        assertTrue(r.recommendedHorizonWeeks > 4)
    }

    @Test
    fun elitePace_isFlagged() {
        val r = RunPlanCoach.evaluate(profile(longestRunKm = 10.0), RunPlanCoach.Goal(10.0, 12, targetTimeMinutes = 25.0))
        assertTrue(r.cautions.any { it.contains("elite", true) })
    }

    @Test
    fun zeroOrNegativeTargetTime_isIgnored() {
        for (tt in listOf(0.0, -10.0)) {
            val r = RunPlanCoach.evaluate(profile(longestRunKm = 3.0), RunPlanCoach.Goal(5.0, 10, tt))
            assertTrue(r.rationale.none { it.contains("pace", true) && it.contains("/km") })
        }
    }

    // ── Defects: inputs the form accepts that produce nonsense ────────────────

    /** Form accepts any weeks > 0. A typo like "500" weeks must not build a 10-year plan. */
    @Test
    fun defect_hugeHorizon_isBounded() {
        val r = RunPlanCoach.evaluate(profile(longestRunKm = 10.0), RunPlanCoach.Goal(5.0, 500))
        assertTrue("plan has ${r.plan.size} weeks for a 5 km goal the user can already run", r.plan.size <= 104)
    }

    /** Form accepts any distance > 0. "1000" (km instead of m?) must not plan a 1000 km long run. */
    @Test
    fun defect_absurdTargetDistance_isRejectedOrCapped() {
        val r = RunPlanCoach.evaluate(profile(longestRunKm = 10.0), RunPlanCoach.Goal(1000.0, 52))
        val maxLong = r.plan.maxOf { it.longRunKm }
        assertTrue("longest planned run is $maxLong km", maxLong <= 100.0)
    }

    /** Form offers "1 day per week" but the coach silently coerces to 2 without telling the user. */
    @Test
    fun defect_oneDayPerWeek_isRespectedOrExplained() {
        val r = RunPlanCoach.evaluate(profile(longestRunKm = 0.0, days = 1), RunPlanCoach.Goal(5.0, 12))
        val respected = r.plan.all { it.runningDays <= 1 }
        val explained = (r.rationale + r.cautions).any { it.contains("2 days", true) || it.contains("two days", true) }
        assertTrue("user asked for 1 day/week; plan uses ${r.plan.maxOf { it.runningDays }} with no explanation",
            respected || explained)
    }
}
