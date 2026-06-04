package com.steptracker.app

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.util.Log
import kotlin.math.sqrt

/**
 * Walk/run classifier — v7 (cadence-only, single threshold)
 *
 * Bug fixes over v6:
 * 1. Dead zone removed: in v6 the "dead zone" made classify() return RUNNING whenever
 *    the current state was RUNNING and SPM was 128-155. This meant every step during
 *    a brisk walk (or any transition) kept voting RUNNING → walkVotes never reached
 *    the threshold → permanently stuck in running. Single threshold (150 SPM) fixes this.
 *
 * 2. INTERVAL_WINDOW 10→5: with a 10-step median, the cadence measurement only
 *    shifts after 5+ steps at the new pace. With 5 steps, sorted[2] (the median)
 *    flips after just 3 steps at the new pace. Transition is 3× faster.
 *
 * 3. Asymmetric vote thresholds: VOTES_TO_RUN=8 (hard to enter), VOTES_TO_WALK=6
 *    (easier to exit). This prevents false-positive running without locking the state.
 *
 * 4. Votes NOT cleared on transition: the sliding vote window naturally dilutes
 *    old votes. Clearing caused a cold-start after every switch.
 *
 * Kalman filter assessment: a Kalman filter on SPM would react faster to pace
 * changes while filtering noise. The vote window achieves similar smoothing at
 * lower complexity. The current design is sufficient; a Kalman filter on GPS
 * position would have more benefit (see StepTrackerService).
 */
class StepDetector(private val listener: StepListener) : SensorEventListener {

    interface StepListener {
        fun onStep(wallTimeMs: Long, activity: ActivityType)
        fun onActivityChanged(newActivity: ActivityType, wallTimeMs: Long)
    }

    // ── Sensor timestamp → wall-clock ─────────────────────────────────────────
    private var offsetMs = 0L
    private var offsetInitialised = false

    private fun bootNsToWallMs(bootNs: Long): Long {
        val raw = System.currentTimeMillis() - bootNs / 1_000_000L
        offsetMs = if (!offsetInitialised) { offsetInitialised = true; raw }
                   else (offsetMs * 15 + raw) / 16
        return offsetMs + bootNs / 1_000_000L
    }

    // ── Gravity filter ────────────────────────────────────────────────────────
    private val GRAVITY_ALPHA = 0.80f
    private var gx = 0f; private var gy = 0f; private var gz = 9.81f

    // ── Peak / step detection ─────────────────────────────────────────────────
    private val STEP_THRESHOLD = 1.5f
    private val MIN_STEP_MS    = 230L
    private var lastStepWallMs = 0L
    private var lastMag        = 0f
    private var rising         = false

    // ── Cadence window (short = responsive) ──────────────────────────────────
    // 5-step median: after 3 steps at new pace, sorted[2] already reflects the change.
    private val INTERVAL_WINDOW = 5
    private val stepIntervals   = ArrayDeque<Long>(INTERVAL_WINDOW)

    // Single SPM threshold — no dead zone.
    // Research: brisk walk ≤ 140 SPM, slow jog ≥ 150 SPM.
    private val CLASSIFY_SPM = 150

    // ── Vote window (provides stability) ─────────────────────────────────────
    private val VOTE_WINDOW   = 10
    private val VOTES_TO_RUN  = 8    // hard to enter: 8/10 must vote run
    private val VOTES_TO_WALK = 6    // easier to exit: 6/10 must vote walk
    private val classVotes    = ArrayDeque<ActivityType>(VOTE_WINDOW)

    var currentActivity = ActivityType.IDLE
        private set

    // ── Debug properties (exposed for on-screen metrics + Logcat) ────────────
    val currentSpm: Int
        get() {
            if (stepIntervals.size < 2) return 0
            val sorted = stepIntervals.sorted()
            return (60_000.0 / sorted[sorted.size / 2]).toInt()
        }
    val lastIntervalMs: Long
        get() = if (stepIntervals.isNotEmpty()) stepIntervals.last() else 0L
    val runVoteCount: Int  get() = classVotes.count { it == ActivityType.RUNNING }
    val walkVoteCount: Int get() = classVotes.count { it == ActivityType.WALKING }
    val totalVoteCount: Int get() = classVotes.size

    // ── SensorEventListener ───────────────────────────────────────────────────

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) return
        val wallMs = bootNsToWallMs(event.timestamp)

        val ax = event.values[0]; val ay = event.values[1]; val az = event.values[2]
        gx = GRAVITY_ALPHA * gx + (1 - GRAVITY_ALPHA) * ax
        gy = GRAVITY_ALPHA * gy + (1 - GRAVITY_ALPHA) * ay
        gz = GRAVITY_ALPHA * gz + (1 - GRAVITY_ALPHA) * az

        val mag = sqrt(((ax-gx)*(ax-gx) + (ay-gy)*(ay-gy) + (az-gz)*(az-gz)).toDouble()).toFloat()
        detectStep(mag, wallMs)
        lastMag = mag
    }

    // ── Step detection ────────────────────────────────────────────────────────

    private fun detectStep(mag: Float, wallMs: Long) {
        if (mag > lastMag) {
            rising = true
        } else if (rising && lastMag > STEP_THRESHOLD) {
            rising = false
            val gap = wallMs - lastStepWallMs
            if (gap >= MIN_STEP_MS) registerStep(wallMs, gap)
        } else {
            rising = false
        }
    }

    private fun registerStep(wallMs: Long, intervalMs: Long) {
        lastStepWallMs = wallMs
        if (stepIntervals.size >= INTERVAL_WINDOW) stepIntervals.removeFirst()
        stepIntervals.addLast(intervalMs)

        val spm  = currentSpm
        val vote = if (spm >= CLASSIFY_SPM) ActivityType.RUNNING else ActivityType.WALKING

        if (classVotes.size >= VOTE_WINDOW) classVotes.removeFirst()
        classVotes.addLast(vote)

        val runVotes  = runVoteCount
        val walkVotes = walkVoteCount

        // Bootstrap: resolve IDLE once we have enough cadence data
        if (currentActivity == ActivityType.IDLE && classVotes.size >= 5) {
            currentActivity = if (runVotes > walkVotes) ActivityType.RUNNING else ActivityType.WALKING
            Log.d("StepDebug", "bootstrap → $currentActivity  spm=$spm")
            listener.onActivityChanged(currentActivity, wallMs)
        }

        val newActivity = when (currentActivity) {
            ActivityType.WALKING -> if (runVotes  >= VOTES_TO_RUN)  ActivityType.RUNNING else ActivityType.WALKING
            ActivityType.RUNNING -> if (walkVotes >= VOTES_TO_WALK) ActivityType.WALKING else ActivityType.RUNNING
            ActivityType.IDLE    -> currentActivity
        }

        if (newActivity != currentActivity && newActivity != ActivityType.IDLE) {
            Log.d("StepDebug", "SWITCH $currentActivity→$newActivity  spm=$spm  rv=$runVotes wv=$walkVotes")
            currentActivity = newActivity
            listener.onActivityChanged(currentActivity, wallMs)
        }

        Log.d("StepDebug", "step iv=${intervalMs}ms spm=$spm vote=$vote rv=$runVotes wv=$walkVotes/${totalVoteCount} → $currentActivity")
        listener.onStep(wallMs, currentActivity)
    }

    // ── Reset ─────────────────────────────────────────────────────────────────

    fun reset(seedActivity: ActivityType = ActivityType.IDLE) {
        stepIntervals.clear(); classVotes.clear()
        currentActivity = seedActivity
        lastStepWallMs = 0L; lastMag = 0f; rising = false
        offsetInitialised = false
    }
}
