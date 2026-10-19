package com.steptracker.app.qa

import com.steptracker.app.CalibrationMath
import com.steptracker.app.StepPeakDetector
import com.steptracker.app.UserPrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.exp
import kotlin.random.Random

/**
 * The step-length calibration wizard: data requirements, accept/reject bounds, and an
 * end-to-end run of "calibrate, then track" through the real StepPeakDetector on
 * synthetic accelerometer traces.
 */
class CalibrationTest {

    // ── Data requirements ─────────────────────────────────────────────────────

    @Test
    fun tooFewImpactsOrTooLittleDistance_isNotAResult() {
        assertNull(CalibrationMath.strideFromGps(39, 100.0))
        assertNull(CalibrationMath.strideFromGps(0, 100.0))
        assertNull(CalibrationMath.strideFromGps(120, 19.9))
        assertEquals(0.5, CalibrationMath.strideFromGps(40, 20.0)!!, 1e-9)
        assertNull(CalibrationMath.strideFromManual(100, 14.9))
        assertNull(CalibrationMath.strideFromManual(100, null))
        assertNull(CalibrationMath.strideFromManual(39, 50.0))
        assertEquals(0.5, CalibrationMath.strideFromManual(100, 50.0)!!, 1e-9)
    }

    // ── Accept / reject bounds ────────────────────────────────────────────────

    @Test
    fun boundsAreOrderedAndBelowAFullStride_forEveryHeight() {
        for (h in 120..230 step 5) for (jog in listOf(false, true)) {
            val b = CalibrationMath.bounds(h.toFloat(), jog)
            val full = if (jog) UserPrefs.expectedRunStrideM(h.toFloat()) else UserPrefs.expectedWalkStrideM(h.toFloat())
            val tag = "h=$h jog=$jog $b"
            assertTrue(tag, b.minOk > 0 && b.minOk < b.ideal && b.ideal < b.maxOk)
            assertTrue(tag, b.maxOk < full)
        }
    }

    @Test
    fun noHeight_usesConservativeDefaults() {
        assertEquals(CalibrationMath.Bounds(0.25, 0.65, 0.40), CalibrationMath.bounds(0f, false))
        assertEquals(CalibrationMath.Bounds(0.35, 0.95, 0.60), CalibrationMath.bounds(0f, true))
        assertEquals(CalibrationMath.bounds(0f, false), CalibrationMath.bounds(119.9f, false))
    }

    /** v1.0.18 regression guard: 0.94 m for a 175 cm walker must be refused. */
    @Test
    fun oversizedWalkCalibration_isRejected() {
        val b = CalibrationMath.bounds(175f, false)
        assertFalse(b.accepts(0.94))
        assertTrue(b.accepts(0.50))
    }

    @Test
    fun boundsGrowWithHeight() {
        for (jog in listOf(false, true)) {
            val short = CalibrationMath.bounds(155f, jog)
            val tall = CalibrationMath.bounds(195f, jog)
            assertTrue(tall.maxOk > short.maxOk && tall.ideal > short.ideal)
        }
    }

    // ── End to end on synthetic accelerometer traces ──────────────────────────

    /**
     * Vertical acceleration for a gait: a heel-strike pulse per step plus a smaller
     * mid-stance bump (the second peak the real counter also picks up while walking).
     */
    private fun countImpacts(seconds: Double, stepHz: Double, impact: Double, bump: Double, seed: Int): Int {
        val det = StepPeakDetector()
        val rnd = Random(seed)
        val dt = 0.02
        val period = 1.0 / stepHz
        var count = 0
        var t = 0.0
        val t0 = 1_800_000_000_000L
        while (t < seconds) {
            val phase = t % period
            val heel = impact * exp(-((phase - 0.05) / 0.03).let { it * it })
            val mid = bump * exp(-((phase - 0.05 - 0.5 * period) / 0.04).let { it * it })
            val az = 9.81 + heel + mid + rnd.nextDouble(-0.25, 0.25)
            val ax = rnd.nextDouble(-0.3, 0.3)
            val ay = rnd.nextDouble(-0.3, 0.3)
            if (det.onSample(ax.toFloat(), ay.toFloat(), az.toFloat(), t0 + (t * 1000).toLong()) != null) count++
            t += dt
        }
        return count
    }

    // The detector ignores peaks < 280 ms apart, so the mid-stance bump (half a step after
    // heel strike) only counts below ~107 steps/min: 1.6 Hz → 2 impacts/step, 1.8 Hz+ → 1.
    private data class Gait(val mps: Double, val stepHz: Double, val impact: Double, val bump: Double)
    private val slowWalk = Gait(1.3, 1.6, 7.0, 4.5)
    private val easyWalk = Gait(1.45, 1.9, 7.0, 4.5)
    private val briskWalk = Gait(1.6, 2.0, 8.0, 5.0)
    private val handWalk = Gait(1.3, 1.6, 7.0, 1.0)
    private val pocketJog = Gait(2.4, 2.8, 16.0, 1.0)

    private fun calibrate(g: Gait, metres: Double, seed: Int): Double? =
        CalibrationMath.strideFromGps(countImpacts(metres / g.mps, g.stepHz, g.impact, g.bump, seed), metres)

    private fun trackedMetres(g: Gait, metres: Double, stride: Double, seed: Int): Double =
        countImpacts(metres / g.mps, g.stepHz, g.impact, g.bump, seed) * stride

    @Test
    fun calibrateThenTrack_sameGait_isAccurate() {
        for ((name, g) in listOf("slow walk" to slowWalk, "easy walk" to easyWalk, "jog" to pocketJog)) {
            val stride = calibrate(g, 60.0, 1)!!
            val tracked = trackedMetres(g, 1000.0, stride, 2)
            println("$name: stride=%.3f m/impact, 1000 m tracked as %.0f m".format(stride, tracked))
            assertEquals("$name tracked distance", 1000.0, tracked, 30.0)
        }
    }

    /** Hand carry misses the mid-stance bump → ~double length per impact; saving it would double every distance. */
    @Test
    fun handCarryCalibration_isRejected() {
        val pocket = calibrate(slowWalk, 60.0, 4)!!
        val hand = calibrate(handWalk, 60.0, 4)!!
        println("pocket=%.3f hand=%.3f".format(pocket, hand))
        assertTrue(hand > pocket * 1.5)
        assertFalse("hand stride $hand accepted at 175 cm", CalibrationMath.bounds(175f, false).accepts(hand))
    }

    /** Both paces above the double-count cadence: only the step length changes. */
    @Test
    fun calibratedAtEasyPace_trackedBrisk_errorStaysModerate() {
        val stride = calibrate(easyWalk, 60.0, 5)!!
        val tracked = trackedMetres(briskWalk, 1000.0, stride, 6)
        val err = (tracked - 1000.0) / 1000.0
        println("easy-pace calibration used for brisk walking: 1000 m → %.0f m (%.1f%%)".format(tracked, err * 100))
        assertTrue("error ${err * 100}%", abs(err) <= 0.20)
    }

    /**
     * Calibrating during a slow walk (< ~107 steps/min, two impacts per step) and then
     * walking normally (one impact per step) would halve every step-based distance. The
     * wizard must refuse such a length, or it must track a normal walk accurately.
     */
    @Test
    fun slowWalkCalibration_isRefusedOrStaysAccurate() {
        val stride = calibrate(slowWalk, 60.0, 7)!!
        val tracked = trackedMetres(easyWalk, 1000.0, stride, 8)
        val err = (tracked - 1000.0) / 1000.0
        println("slow-walk calibration used for a normal walk: 1000 m → %.0f m (%.1f%%)".format(tracked, err * 100))
        // The synthetic walk has a 0.81 m step, which is only realistic from ~165 cm up.
        for (h in listOf(165f, 175f, 185f, 195f)) {
            val saved = CalibrationMath.bounds(h, false).accepts(stride)
            assertTrue("h=$h: %.3f m saved, 1000 m tracked as %.0f m".format(stride, tracked), !saved || abs(err) <= 0.15)
        }
    }

    // ── Against the real recorded workout ─────────────────────────────────────

    private fun realMedianImpliedStep(activity: String): Double? {
        val f = listOf("../pulled_logs/classifier_debug_20260922_utf8.csv").map(::File).firstOrNull { it.exists() } ?: return null
        val lines = f.readLines()
        val h = lines[0].split(',')
        val iEv = h.indexOf("event"); val iStep = h.indexOf("implied_step_m"); val iAct = h.indexOf("current_activity")
        val v = lines.drop(1).map { it.split(',') }
            .filter { it.size == h.size && it[iEv] == "step" && it[iAct] == activity }
            .mapNotNull { it[iStep].toDoubleOrNull() }.sorted()
        return if (v.isEmpty()) null else v[v.size / 2]
    }

    /** Height from the 2026-08 calibration notes ("a 1.85 m adult"). */
    private val userHeight = 185f

    @Test
    fun realWorldLengths_areAcceptedByTheWizard() {
        val walk = realMedianImpliedStep("WALKING"); val jog = realMedianImpliedStep("JOGGING")
        assumeTrue("recorded log not present", walk != null && jog != null)
        println("real per-impact length: walk=%.2f jog=%.2f".format(walk, jog))
        assertTrue(CalibrationMath.bounds(userHeight, false).accepts(walk!!))
        assertTrue(CalibrationMath.bounds(userHeight, true).accepts(jog!!))
    }

    /**
     * Uncalibrated defaults assume two impacts per step. The recorded workout shows ~1.3
     * per walking step and ~1 per jogging step, so step-based distance (no GPS yet, or
     * indoors) comes out 20–27 % short until the user calibrates.
     */
    @Test
    fun defect_uncalibratedDefaults_matchRealImpactLengths() {
        val walk = realMedianImpliedStep("WALKING"); val jog = realMedianImpliedStep("JOGGING")
        assumeTrue("recorded log not present", walk != null && jog != null)
        val dWalk = UserPrefs.peakWalkStrideM(userHeight); val dJog = UserPrefs.peakRunStrideM(userHeight)
        val msg = "defaults walk=%.2f (real %.2f), jog=%.2f (real %.2f)".format(dWalk, walk, dJog, jog)
        assertTrue(msg, abs(dWalk - walk!!) / walk <= 0.15 && abs(dJog - jog!!) / jog <= 0.15)
    }
}
