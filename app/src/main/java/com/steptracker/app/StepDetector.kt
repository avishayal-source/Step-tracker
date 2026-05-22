package com.steptracker.app

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import kotlin.math.sqrt

/**
 * Walk/run classifier — v5
 *
 * - Cadence + magnitude hysteresis: different thresholds to enter vs leave running.
 * - Vote window still dampens jitter, but switches need fewer agreeing votes.
 * - Keeps partial vote history across transitions (no full cold reset).
 */
class StepDetector(private val listener: StepListener) : SensorEventListener {

    interface StepListener {
        fun onStep(wallTimeMs: Long, activity: ActivityType)
        fun onActivityChanged(newActivity: ActivityType, wallTimeMs: Long)
    }

    private var offsetMs = 0L
    private var offsetInitialised = false

    private fun bootNsToWallMs(bootNs: Long): Long {
        val raw = System.currentTimeMillis() - bootNs / 1_000_000L
        offsetMs = if (!offsetInitialised) { offsetInitialised = true; raw }
                   else (offsetMs * 15 + raw) / 16
        return offsetMs + bootNs / 1_000_000L
    }

    private val GRAVITY_ALPHA = 0.80f
    private var gx = 0f; private var gy = 0f; private var gz = 9.81f

    private val STEP_THRESHOLD = 1.5f
    private val MIN_STEP_MS    = 230L
    private var lastStepWallMs = 0L
    private var lastMag        = 0f
    private var rising         = false
    private var currentPeakMag = 0f

    private val WINDOW         = 8
    private val MAG_WINDOW     = 6
    private val stepIntervals  = ArrayDeque<Long>(WINDOW)
    private val recentPeaks    = ArrayDeque<Float>(MAG_WINDOW)

    // Enter run: higher bar. Exit run: lower bar (hysteresis).
    private val RUN_ENTER_SPM  = 126
    private val RUN_EXIT_SPM   = 112
    private val RUN_ENTER_MAG  = 2.85f
    private val RUN_EXIT_MAG   = 2.55f

    private val VOTE_WINDOW    = 8
    private val VOTES_TO_RUN   = 5
    private val VOTES_TO_WALK  = 4
    private val classVotes     = ArrayDeque<ActivityType>(VOTE_WINDOW)

    var currentActivity = ActivityType.IDLE
        private set

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) return
        val wallMs = bootNsToWallMs(event.timestamp)

        val ax = event.values[0]; val ay = event.values[1]; val az = event.values[2]
        gx = GRAVITY_ALPHA * gx + (1 - GRAVITY_ALPHA) * ax
        gy = GRAVITY_ALPHA * gy + (1 - GRAVITY_ALPHA) * ay
        gz = GRAVITY_ALPHA * gz + (1 - GRAVITY_ALPHA) * az

        val mag = sqrt(((ax-gx)*(ax-gx) + (ay-gy)*(ay-gy) + (az-gz)*(az-gz)).toDouble()).toFloat()
        if (mag > currentPeakMag) currentPeakMag = mag

        detectStep(mag, wallMs)
        lastMag = mag
    }

    private fun detectStep(mag: Float, wallMs: Long) {
        if (mag > lastMag) { rising = true }
        else if (rising && lastMag > STEP_THRESHOLD) {
            rising = false
            val gap = wallMs - lastStepWallMs
            if (gap >= MIN_STEP_MS) {
                if (recentPeaks.size >= MAG_WINDOW) recentPeaks.removeFirst()
                recentPeaks.addLast(currentPeakMag)
                currentPeakMag = 0f
                registerStep(wallMs, gap)
            }
        } else { rising = false }
    }

    private fun registerStep(wallMs: Long, intervalMs: Long) {
        lastStepWallMs = wallMs
        if (stepIntervals.size >= WINDOW) stepIntervals.removeFirst()
        stepIntervals.addLast(intervalMs)

        val vote = classify()
        if (classVotes.size >= VOTE_WINDOW) classVotes.removeFirst()
        classVotes.addLast(vote)

        val runVotes  = classVotes.count { it == ActivityType.RUNNING }
        val walkVotes = classVotes.count { it == ActivityType.WALKING }

        // Bootstrap from idle after a few steps
        if (currentActivity == ActivityType.IDLE && classVotes.size >= 3) {
            currentActivity = if (runVotes > walkVotes) ActivityType.RUNNING else ActivityType.WALKING
            listener.onActivityChanged(currentActivity, wallMs)
        }

        val newActivity = when (currentActivity) {
            ActivityType.WALKING ->
                if (runVotes >= VOTES_TO_RUN) ActivityType.RUNNING else ActivityType.WALKING
            ActivityType.RUNNING ->
                if (walkVotes >= VOTES_TO_WALK) ActivityType.WALKING else ActivityType.RUNNING
            ActivityType.IDLE -> currentActivity
        }

        if (newActivity != currentActivity && newActivity != ActivityType.IDLE) {
            currentActivity = newActivity
            // Keep recent cadence/magnitude context; only trim a little of the vote buffer
            repeat(2) { if (classVotes.isNotEmpty()) classVotes.removeFirst() }
            listener.onActivityChanged(currentActivity, wallMs)
        }

        listener.onStep(wallMs, currentActivity)
    }

    private fun classify(): ActivityType {
        if (stepIntervals.isEmpty()) return ActivityType.WALKING
        val sorted   = stepIntervals.sorted()
        val medianMs = sorted[sorted.size / 2].toDouble()
        val spm      = (60_000.0 / medianMs).toInt()
        val rmsMag   = if (recentPeaks.isNotEmpty())
            sqrt(recentPeaks.sumOf { (it * it).toDouble() } / recentPeaks.size).toFloat()
        else 0f

        val inRun = currentActivity == ActivityType.RUNNING
        val runSpmThresh = if (inRun) RUN_EXIT_SPM else RUN_ENTER_SPM
        val runMagThresh = if (inRun) RUN_EXIT_MAG else RUN_ENTER_MAG

        return when {
            spm >= runSpmThresh && rmsMag >= runMagThresh -> ActivityType.RUNNING
            spm < runSpmThresh - 8 || rmsMag < runMagThresh - 0.35f -> ActivityType.WALKING
            inRun -> ActivityType.RUNNING
            else  -> ActivityType.WALKING
        }
    }

    fun reset(seedActivity: ActivityType = ActivityType.IDLE) {
        stepIntervals.clear(); recentPeaks.clear(); classVotes.clear()
        currentActivity = seedActivity
        lastStepWallMs  = 0L; lastMag = 0f; rising = false; currentPeakMag = 0f
        offsetInitialised = false
    }
}
