package com.steptracker.app.qa

import com.steptracker.app.ActivityType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Walk / jog / run classifier (StepDetector v13) driven by synthetic step + GPS streams.
 * Each test prints a per-segment table (latency to correct label, settled accuracy,
 * counted steps) to the test's stdout.
 */
class ClassifierScenarioTest {

    private fun ClassifierRun.printed(name: String) = also { println("── $name\n${report()}") }

    @Test
    fun steadyWalk_isWalking() {
        val o = ClassifierRun(1).play(walk(180)).printed("steady walk").outcomes[0]
        assertTrue(o.settledAccuracy >= 0.95)
    }

    @Test
    fun steadyJog_isJogging() {
        val o = ClassifierRun(2).play(jog(180)).printed("steady jog").outcomes[0]
        assertTrue("settled ${o.settledAccuracy}", o.settledAccuracy >= 0.90)
    }

    @Test
    fun steadyRun_isRunning() {
        val o = ClassifierRun(3).play(run(180)).printed("steady run").outcomes[0]
        assertTrue(o.settledAccuracy >= 0.95)
    }

    @Test
    fun walkRunWalkIntervals_switchPromptlyWithoutFlapping() {
        val r = ClassifierRun(4).play(walk(120), run(120), walk(120), run(120), walk(120)).printed("intervals")
        r.outcomes.drop(1).forEach { o ->
            val limit = if (o.segment.truth == ActivityType.RUNNING) 20_000 else 35_000
            assertNotNull("${o.segment.label} never detected", o.latencyMs)
            assertTrue("${o.segment.label} took ${o.latencyMs} ms", o.latencyMs!! <= limit)
        }
        assertTrue("activity changes: ${r.rec.changes.size}", r.rec.changes.size <= 5)
    }

    @Test
    fun scheduleLikeCouchTo5k_runWalkIntervals() {
        // 2-min run / 1-min walk × 6, beginner speeds.
        val segs = (1..6).flatMap { listOf(jog(120, 2.0, "run 2'"), walk(60, 1.3, "walk 1'")) }.toTypedArray()
        val r = ClassifierRun(5).play(*segs).printed("C25K intervals")
        val detectedWalks = r.outcomes.filter { it.segment.truth == ActivityType.WALKING && it.latencyMs != null }
        assertTrue("only ${detectedWalks.size}/6 one-minute walks detected", detectedWalks.size >= 5)
    }

    @Test
    fun slowCooldown_withDoubledPeaks_returnsToWalking() {
        val r = ClassifierRun(6).play(run(180), doubledWalk(150, 1.15, "slow cooldown (doubled)")).printed("slow cooldown")
        val o = r.outcomes[1]
        assertTrue("latency ${o.latencyMs}", o.latencyMs != null && o.latencyMs <= 75_000)
    }

    /**
     * Brisk walking (1.5 m/s ≈ 5.4 km/h) after a run with the peak counter double-counting:
     * too fast for the slow override (≤ 1.40), cadence distrusted, gait baselines unlearned.
     */
    @Test
    fun defect_briskCooldown_withDoubledPeaks_returnsToWalking() {
        val r = ClassifierRun(7).play(run(180), doubledWalk(180, 1.5, "brisk cooldown (doubled)")).printed("brisk cooldown")
        val o = r.outcomes[1]
        assertTrue("brisk cooldown latency=${o.latencyMs} settled=${o.settledAccuracy}",
            o.latencyMs != null && o.latencyMs <= 90_000)
    }

    /** 2026-09-08 regression guard: slow jog whose cadence is double-counted must stay a jog. */
    @Test
    fun slowJog_withDoubledCadence_staysJogging() {
        val slowJog = Segment("slow jog 1.6 m/s (doubled)", 240_000, 1.6, 285, 10.0f, truth = ActivityType.JOGGING)
        val r = ClassifierRun(14).play(run(120), slowJog).printed("slow doubled jog")
        assertTrue(r.rec.changes.none { it.second == ActivityType.WALKING })
    }

    /** Real jogs show soft-landing streaks of up to ~15 s; one inside a tunnel must not flip to walking. */
    @Test
    fun softLandingStreakInTunnel_staysRunningFamily() {
        val soft = jog(20).copy(label = "soft landings, no GPS", gpsMps = null, peakMag = 3.0f)
        val r = ClassifierRun(15).play(run(120), soft, jog(60)).printed("soft streak tunnel")
        assertTrue(r.rec.changes.none { it.second == ActivityType.WALKING })
    }

    @Test
    fun gpsTunnelMidRun_neverDropsToWalking() {
        val tunnel = run(60).copy(label = "tunnel (no GPS)", gpsMps = null)
        val r = ClassifierRun(8).play(run(120), tunnel, run(60)).printed("tunnel")
        val walkedInTunnel = r.rec.changes.any { (t, a) ->
            a == ActivityType.WALKING && t in r.outcomes[1].startMs..(r.outcomes[1].startMs + 60_000)
        }
        assertTrue(!walkedInTunnel)
    }

    @Test
    fun trafficLightStop_countsNoStepsAndKeepsTheRun() {
        val stand = Segment("stand at light", 45_000, 0.1, 1500, 2.4f, gpsNoise = 0.05,
            intervalJitterMs = 300, truth = ActivityType.RUNNING)
        val r = ClassifierRun(9).play(run(120), stand, run(60)).printed("traffic light")
        // The Kalman filter needs ~10 s to fall below walking speed; fidgets in that window may count.
        assertTrue("steps counted while standing: ${r.outcomes[1].stepsCounted}", r.outcomes[1].stepsCounted <= 8)
        assertTrue(r.rec.changes.none { it.second == ActivityType.WALKING })
    }

    @Test
    fun noisyGpsAtTheJogBoundary_doesNotFlap() {
        val wobble = Segment("1.85 m/s ± 0.5", 600_000, 1.85, 360, 10.0f, gpsNoise = 0.5, truth = ActivityType.JOGGING)
        val r = ClassifierRun(10).play(wobble).printed("boundary wobble")
        assertTrue("activity changes in 10 min: ${r.rec.changes.size}", r.rec.changes.size <= 6)
    }

    @Test
    fun handWavingWhileSitting_noGps_countsAlmostNoSteps() {
        val wave = Segment("waving phone", 120_000, null, 700, 4.0f, intervalJitterMs = 650, truth = ActivityType.IDLE)
        val o = ClassifierRun(11).play(wave).printed("hand waving").outcomes[0]
        assertTrue("counted ${o.stepsCounted}/${o.peaksFed}", o.stepsCounted <= o.peaksFed * 0.15)
    }

    @Test
    fun treadmillRun_noGps_isRunningFamilyAndCountsSteps() {
        val tm = run(180).copy(label = "treadmill run", gpsMps = null, truth = ActivityType.JOGGING)
        val o = ClassifierRun(12).play(tm).printed("treadmill run").outcomes[0]
        assertTrue(o.settledAccuracy >= 0.95)
        assertTrue(o.stepsCounted >= o.peaksFed * 0.9)
    }

    /** Indoors there is no GPS to expose the doubled walking cadence (~207 SPM). */
    @Test
    fun defect_treadmillWalk_withDoubledPeaks_staysWalking() {
        val tm = doubledWalk(180, 1.3, "treadmill walk (doubled)").copy(gpsMps = null)
        val o = ClassifierRun(13).play(tm).printed("treadmill walk").outcomes[0]
        assertTrue("settled walking accuracy ${o.settledAccuracy}", o.settledAccuracy >= 0.9)
    }

    @Test
    fun sameSeed_isDeterministic() {
        val a = ClassifierRun(42).play(walk(60), run(90), walk(60)).rec.changes
        val b = ClassifierRun(42).play(walk(60), run(90), walk(60)).rec.changes
        assertEquals(a, b)
    }

    @Test
    fun seedSweep_intervalWorkoutIsRobust() {
        val failures = (100 until 140).filter { seed ->
            val r = ClassifierRun(seed).play(walk(90), run(120), walk(120))
            val run = r.outcomes[1].latencyMs
            val back = r.outcomes[2].latencyMs
            run == null || run > 20_000 || back == null || back > 35_000 || r.rec.changes.size > 3
        }
        assertTrue("failing seeds: $failures", failures.size <= 2)
    }
}
