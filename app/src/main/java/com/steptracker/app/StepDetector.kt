package com.steptracker.app

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.util.Log
import kotlin.math.sqrt

/**
 * Walk/run classifier — v8 (GPS-fused cadence, cooldown)
 *
 * Changes from v7:
 * 1. CLASSIFY_SPM 150→165: real-session CSV data showed the user's natural
 *    walking cadence was 147-154 SPM, right on the old threshold. Raising to
 *    165 moves the boundary above brisk walking and into slow-jog territory.
 *
 * 2. GPS speed fusion: when a fresh GPS fix is available, the device's
 *    instantaneous speed (m/s) is used as a strong additional vote signal.
 *    GPS < 1.8 m/s (6.5 km/h) → clear walking  → counts as GPS_VOTE_WEIGHT walk votes.
 *    GPS > 2.5 m/s (9.0 km/h) → clear running  → counts as GPS_VOTE_WEIGHT run votes.
 *    1.8–2.5 m/s is ambiguous → GPS vote is neutral (0).
 *    A GPS vote alone (4 extra votes) can tip the balance across either threshold
 *    when combined with even a few matching cadence votes. When GPS is stale
 *    (>3 s since last fix) it contributes nothing so cadence-only logic takes over.
 *
 * 3. 10-second cooldown (from v8 interim commit): after any state switch, another
 *    switch is blocked for MIN_STATE_DURATION_MS. Prevents rapid flickering from
 *    short cadence noise bursts after a transition.
 */
class StepDetector(private val listener: StepListener) : SensorEventListener {

    data class DebugSample(
        val event: String,
        val wallTimeMs: Long,
        val intervalMs: Long,
        val spm: Int,
        val vote: ActivityType,
        val runVotes: Int,
        val walkVotes: Int,
        val totalVotes: Int,
        val gpsSpeedMps: Double?,
        val gpsVote: ActivityType?,
        val effectiveRunVotes: Int,
        val effectiveWalkVotes: Int,
        val previousActivity: ActivityType,
        val currentActivity: ActivityType
    )

    interface StepListener {
        fun onStep(wallTimeMs: Long, activity: ActivityType)
        fun onActivityChanged(newActivity: ActivityType, wallTimeMs: Long)
        fun onClassifierDebug(sample: DebugSample)
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

    // ── Cadence window ────────────────────────────────────────────────────────
    private val INTERVAL_WINDOW = 5
    private val stepIntervals   = ArrayDeque<Long>(INTERVAL_WINDOW)

    // Raised from 150 to 165: real-session data showed user's walking cadence
    // of 147-154 SPM was indistinguishable from running at the old threshold.
    private val CLASSIFY_SPM = 165

    // ── Vote window ───────────────────────────────────────────────────────────
    private val VOTE_WINDOW   = 10
    private val VOTES_TO_RUN  = 8
    private val VOTES_TO_WALK = 6
    private val classVotes    = ArrayDeque<ActivityType>(VOTE_WINDOW)

    // ── GPS speed fusion ──────────────────────────────────────────────────────
    // Speed boundaries (m/s):  walk < 1.8 (6.5 km/h) | ambiguous | run > 2.5 (9 km/h)
    private val GPS_WALK_MPS       = 1.8
    private val GPS_RUN_MPS        = 2.5
    // How many extra effective votes a clear GPS speed signal adds.
    // With VOTE_WINDOW=10: 4 GPS votes + 2 cadence votes = 6 → meets VOTES_TO_WALK.
    private val GPS_VOTE_WEIGHT    = 4
    private val GPS_STALE_MS       = 3_000L   // ignore GPS speed older than 3 s

    private var latestGpsSpeedMps: Double? = null
    private var latestGpsTimeMs: Long      = 0L

    /** Called by StepTrackerService on every validated GPS fix. */
    fun updateGpsSpeed(speedMps: Double, fixTimeMs: Long) {
        latestGpsSpeedMps = speedMps
        latestGpsTimeMs   = fixTimeMs
    }

    // ── Cooldown ──────────────────────────────────────────────────────────────
    private val MIN_STATE_DURATION_MS = 10_000L
    private var lastStateChangeMs     = 0L

    var currentActivity = ActivityType.IDLE
        private set

    // ── Debug properties ─────────────────────────────────────────────────────
    val currentSpm: Int
        get() {
            if (stepIntervals.size < 2) return 0
            val sorted = stepIntervals.sorted()
            return (60_000.0 / sorted[sorted.size / 2]).toInt()
        }
    val lastIntervalMs: Long
        get() = if (stepIntervals.isNotEmpty()) stepIntervals.last() else 0L
    val runVoteCount: Int   get() = classVotes.count { it == ActivityType.RUNNING }
    val walkVoteCount: Int  get() = classVotes.count { it == ActivityType.WALKING }
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

        val rawRunVotes  = runVoteCount
        val rawWalkVotes = walkVoteCount

        // GPS fusion: compute extra votes from speed if the fix is fresh enough.
        val gpsAge = wallMs - latestGpsTimeMs
        val freshGpsSpeed = if (gpsAge <= GPS_STALE_MS) latestGpsSpeedMps else null
        val gpsVote: ActivityType? = freshGpsSpeed?.let { spd ->
            when {
                spd < GPS_WALK_MPS -> ActivityType.WALKING
                spd > GPS_RUN_MPS  -> ActivityType.RUNNING
                else               -> null   // ambiguous band — contribute nothing
            }
        }
        val effectiveRunVotes  = rawRunVotes  + if (gpsVote == ActivityType.RUNNING) GPS_VOTE_WEIGHT else 0
        val effectiveWalkVotes = rawWalkVotes + if (gpsVote == ActivityType.WALKING) GPS_VOTE_WEIGHT else 0

        val previousActivity = currentActivity

        // Bootstrap: resolve IDLE once we have enough cadence data.
        if (currentActivity == ActivityType.IDLE && classVotes.size >= 5) {
            currentActivity = if (effectiveRunVotes > effectiveWalkVotes) ActivityType.RUNNING
                              else ActivityType.WALKING
            lastStateChangeMs = wallMs
            Log.d("StepDebug", "bootstrap → $currentActivity  spm=$spm  gps=${freshGpsSpeed?.let { "%.1f".format(it) } ?: "n/a"}")
            listener.onClassifierDebug(buildSample("bootstrap", wallMs, intervalMs, spm, vote,
                rawRunVotes, rawWalkVotes, freshGpsSpeed, gpsVote,
                effectiveRunVotes, effectiveWalkVotes, previousActivity))
            listener.onActivityChanged(currentActivity, wallMs)
        }

        val cooldownElapsed = wallMs - lastStateChangeMs >= MIN_STATE_DURATION_MS
        val newActivity = when (currentActivity) {
            ActivityType.WALKING -> if (cooldownElapsed && effectiveRunVotes  >= VOTES_TO_RUN)  ActivityType.RUNNING else ActivityType.WALKING
            ActivityType.RUNNING -> if (cooldownElapsed && effectiveWalkVotes >= VOTES_TO_WALK) ActivityType.WALKING else ActivityType.RUNNING
            ActivityType.IDLE    -> currentActivity
        }

        if (newActivity != currentActivity && newActivity != ActivityType.IDLE) {
            val beforeSwitch = currentActivity
            Log.d("StepDebug", "SWITCH $currentActivity→$newActivity  spm=$spm  " +
                "rv=$rawRunVotes wv=$rawWalkVotes  gps=${freshGpsSpeed?.let { "%.1f".format(it) } ?: "n/a"}  " +
                "eff_rv=$effectiveRunVotes eff_wv=$effectiveWalkVotes")
            currentActivity = newActivity
            lastStateChangeMs = wallMs
            listener.onClassifierDebug(buildSample("switch", wallMs, intervalMs, spm, vote,
                rawRunVotes, rawWalkVotes, freshGpsSpeed, gpsVote,
                effectiveRunVotes, effectiveWalkVotes, beforeSwitch))
            listener.onActivityChanged(currentActivity, wallMs)
        }

        Log.d("StepDebug", "step spm=$spm vote=$vote rv=$rawRunVotes wv=$rawWalkVotes " +
            "gps=${freshGpsSpeed?.let { "%.1f" .format(it) } ?: "-"} gpsVote=$gpsVote " +
            "eff_rv=$effectiveRunVotes eff_wv=$effectiveWalkVotes → $currentActivity")
        listener.onClassifierDebug(buildSample("step", wallMs, intervalMs, spm, vote,
            rawRunVotes, rawWalkVotes, freshGpsSpeed, gpsVote,
            effectiveRunVotes, effectiveWalkVotes, previousActivity))
        listener.onStep(wallMs, currentActivity)
    }

    private fun buildSample(
        event: String, wallMs: Long, intervalMs: Long,
        spm: Int, vote: ActivityType,
        rawRun: Int, rawWalk: Int,
        gpsSpeed: Double?, gpsVote: ActivityType?,
        effRun: Int, effWalk: Int,
        prevActivity: ActivityType
    ) = DebugSample(
        event = event, wallTimeMs = wallMs, intervalMs = intervalMs,
        spm = spm, vote = vote,
        runVotes = rawRun, walkVotes = rawWalk, totalVotes = totalVoteCount,
        gpsSpeedMps = gpsSpeed, gpsVote = gpsVote,
        effectiveRunVotes = effRun, effectiveWalkVotes = effWalk,
        previousActivity = prevActivity, currentActivity = currentActivity
    )

    // ── Reset ─────────────────────────────────────────────────────────────────

    fun reset(seedActivity: ActivityType = ActivityType.IDLE) {
        stepIntervals.clear(); classVotes.clear()
        currentActivity = seedActivity
        lastStepWallMs = 0L; lastMag = 0f; rising = false
        offsetInitialised = false; lastStateChangeMs = 0L
        latestGpsSpeedMps = null; latestGpsTimeMs = 0L
    }
}
