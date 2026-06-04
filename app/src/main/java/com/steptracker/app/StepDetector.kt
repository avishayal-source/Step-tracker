package com.steptracker.app

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import kotlin.math.sqrt

/**
 * Walk/run classifier — v6 (cadence-only)
 *
 * Previous versions mixed accelerometer magnitude into the classification.
 * Magnitude is too sensitive to phone placement (pocket, hand, armband) and
 * caused persistent mis-classification as running.
 *
 * This version uses cadence (steps per minute) only:
 *   ≤ 128 SPM  → clearly walking  (500 ms+ per step)
 *   ≥ 155 SPM  → clearly jogging  (387 ms per step)
 *   128–155    → dead zone: stay in current state (hysteresis)
 *
 * A vote window of 10 steps requires a sustained cadence shift before
 * the activity label changes (8 of 10 votes to enter run; 7 of 10 to exit).
 */
class StepDetector(private val listener: StepListener) : SensorEventListener {

    interface StepListener {
        fun onStep(wallTimeMs: Long, activity: ActivityType)
        fun onActivityChanged(newActivity: ActivityType, wallTimeMs: Long)
    }

    // ── Sensor timestamp → wall-clock conversion ──────────────────────────────
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
    private val STEP_THRESHOLD = 1.5f   // m/s² above gravity
    private val MIN_STEP_MS    = 230L   // debounce: ~260 SPM max
    private var lastStepWallMs = 0L
    private var lastMag        = 0f
    private var rising         = false

    // ── Cadence window ────────────────────────────────────────────────────────
    private val INTERVAL_WINDOW = 10
    private val stepIntervals   = ArrayDeque<Long>(INTERVAL_WINDOW)

    // SPM thresholds — research-based
    // Typical walking:   80–130 SPM   Typical jogging: 140–180 SPM
    private val RUN_ENTER_SPM = 155   // ≥ this → running vote
    private val RUN_EXIT_SPM  = 128   // ≤ this → walking vote

    // ── Vote window ───────────────────────────────────────────────────────────
    private val VOTE_WINDOW   = 10
    private val VOTES_TO_RUN  = 8    // need 8/10 sustained to enter running
    private val VOTES_TO_WALK = 7    // need 7/10 sustained to return to walking
    private val classVotes    = ArrayDeque<ActivityType>(VOTE_WINDOW)

    var currentActivity = ActivityType.IDLE
        private set

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

        val vote = classify()
        if (classVotes.size >= VOTE_WINDOW) classVotes.removeFirst()
        classVotes.addLast(vote)

        val runVotes  = classVotes.count { it == ActivityType.RUNNING }
        val walkVotes = classVotes.count { it == ActivityType.WALKING }

        // Bootstrap: resolve IDLE once we have enough steps to judge cadence
        if (currentActivity == ActivityType.IDLE && classVotes.size >= 5) {
            currentActivity = if (runVotes > walkVotes) ActivityType.RUNNING else ActivityType.WALKING
            listener.onActivityChanged(currentActivity, wallMs)
        }

        val newActivity = when (currentActivity) {
            ActivityType.WALKING -> if (runVotes  >= VOTES_TO_RUN)  ActivityType.RUNNING else ActivityType.WALKING
            ActivityType.RUNNING -> if (walkVotes >= VOTES_TO_WALK) ActivityType.WALKING else ActivityType.RUNNING
            ActivityType.IDLE    -> currentActivity
        }

        if (newActivity != currentActivity && newActivity != ActivityType.IDLE) {
            currentActivity = newActivity
            classVotes.clear()   // flush votes after a switch — require fresh evidence
            listener.onActivityChanged(currentActivity, wallMs)
        }

        listener.onStep(wallMs, currentActivity)
    }

    // ── Cadence classifier (cadence-only, placement-independent) ─────────────

    private fun classify(): ActivityType {
        if (stepIntervals.size < 3) return ActivityType.WALKING
        val sorted   = stepIntervals.sorted()
        val medianMs = sorted[sorted.size / 2].toDouble()
        val spm      = (60_000.0 / medianMs).toInt()

        return when {
            spm >= RUN_ENTER_SPM -> ActivityType.RUNNING
            spm <= RUN_EXIT_SPM  -> ActivityType.WALKING
            // Dead zone: maintain current state
            currentActivity == ActivityType.RUNNING -> ActivityType.RUNNING
            else -> ActivityType.WALKING
        }
    }

    // ── Reset ─────────────────────────────────────────────────────────────────

    fun reset(seedActivity: ActivityType = ActivityType.IDLE) {
        stepIntervals.clear(); classVotes.clear()
        currentActivity = seedActivity
        lastStepWallMs = 0L; lastMag = 0f; rising = false
        offsetInitialised = false
    }
}
