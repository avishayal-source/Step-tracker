package com.steptracker.app

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.util.Log
import kotlin.math.sqrt

/**
 * Walk/run classifier — v9 (GPS-speed Kalman, in-place hold, dwell-gated)
 *
 * Why this is a full redesign:
 * Real-session CSV analysis (5,626 steps) showed the device's cadence is useless
 * for separating walking from running: median walking cadence was 196 SPM and
 * median running cadence was 189 SPM — fully overlapping (the accelerometer counts
 * roughly double the real steps on this hardware). GPS speed, however, separated
 * the two cleanly: walking ≈ 1.1 m/s (p90 = 1.23), running ≈ 2.15 m/s (p10 = 1.54).
 *
 * Design:
 * 1. PRIMARY SIGNAL = GPS speed, smoothed by a 1-D Kalman filter (random-walk model).
 *    GPS speed is noisy (~1 Hz, multipath) — the classic case where a Kalman filter
 *    helps. Updated once per fix in updateGpsSpeed().
 *
 * 2. HYSTERESIS BAND on the filtered speed (v10 recalibration):
 *      WALKING → RUNNING  when filtered speed sustained above RUN_ENTER_MPS (1.9)
 *      RUNNING → WALKING  when filtered speed sustained below RUN_EXIT_MPS  (1.7)
 *    A later session showed brisk walking reaching ~1.65 m/s (p90), which straddled
 *    the old 1.35 exit threshold and caused 300–600-step run→walk lag. The true
 *    walk/run boundary for this user is ~1.8 m/s (walk median 1.45, run median 2.38).
 *
 * 3. IN-PLACE HOLD (the key fix): when filtered speed collapses below LOW_WALK_MPS
 *    (0.8 m/s) the user is NOT walking forward — they are stopped or running in place
 *    (e.g. jogging on the spot at a traffic light: cadence stays 200+ SPM while GPS
 *    speed drops to ~0). Near-zero speed is therefore treated as "paused / in place"
 *    and the current activity is HELD, never flipped to walking.
 *
 * 4. LEAKY-BUCKET DWELL (v10): evidence for a switch accumulates over time and
 *    DECAYS when the evidence reverses, instead of hard-resetting on every GPS
 *    wobble across the threshold. The v9 hard reset restarted an 18 s timer every
 *    time noisy speed poked back over the boundary, so a switch almost never
 *    committed near the walk/run boundary. The bucket commits once net evidence
 *    reaches STATE_DWELL_MS (8 s), riding through brief dips and noise.
 *
 * 5. CADENCE FALLBACK: when no fresh GPS fix is available (e.g. indoor treadmill),
 *    fall back to cadence-vote majority so the app still classifies something.
 *
 * Validated offline against two real sessions. On the brisk-walk session the
 * run→walk lag dropped from ~376/630 steps (v9) to ~50 steps (v10), matching all
 * six user-reported transitions including a brief mid-run walk while tying laces.
 */
class StepDetector(private val listener: StepListener) : SensorEventListener {

    data class DebugSample(
        val event: String,
        val wallTimeMs: Long,
        val intervalMs: Long,
        val spm: Int,
        val rawGpsMps: Double?,
        val filteredGpsMps: Double?,
        val kalmanGain: Double?,
        val gpsAgeMs: Long,
        val inPlaceHold: Boolean,
        val signalSource: String,
        val desiredActivity: ActivityType?,
        val candidate: ActivityType?,
        val dwellMs: Long,
        val dwellRequiredMs: Long,
        val cadenceRunVotes: Int,
        val cadenceWalkVotes: Int,
        val decisionReason: String,
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

    // ── Cadence window (used only for logging + no-GPS fallback) ──────────────
    private val INTERVAL_WINDOW = 5
    private val stepIntervals   = ArrayDeque<Long>(INTERVAL_WINDOW)
    private val CLASSIFY_SPM = 165

    private val VOTE_WINDOW   = 10
    private val VOTES_TO_RUN  = 8
    private val VOTES_TO_WALK = 6
    private val classVotes    = ArrayDeque<ActivityType>(VOTE_WINDOW)

    // ── GPS speed Kalman filter (primary signal) ──────────────────────────────
    // Random-walk model:  predict P += Q ; gain K = P/(P+R) ; x += K(z−x) ; P = (1−K)P
    private val KALMAN_Q  = 0.01    // process noise — how fast true speed may change
    private val KALMAN_R  = 0.4     // measurement noise — GPS speed jitter variance
    private var kalmanX   = 0.0     // filtered speed estimate (m/s)
    private var kalmanP   = 1.0
    private var kalmanInitialised = false
    private var lastKalmanGain: Double? = null

    private var latestGpsRawSpeedMps: Double? = null
    private var latestGpsTimeMs: Long         = 0L
    private val GPS_STALE_MS = 3_000L

    /** Called by StepTrackerService on every validated GPS fix. Runs the Kalman update. */
    fun updateGpsSpeed(speedMps: Double, fixTimeMs: Long) {
        latestGpsRawSpeedMps = speedMps
        latestGpsTimeMs      = fixTimeMs
        if (!kalmanInitialised) {
            kalmanX = speedMps; kalmanP = 1.0; kalmanInitialised = true
            lastKalmanGain = 1.0
        } else {
            val pPred = kalmanP + KALMAN_Q
            val k     = pPred / (pPred + KALMAN_R)
            kalmanX  += k * (speedMps - kalmanX)
            kalmanP   = (1 - k) * pPred
            lastKalmanGain = k
        }
        emitDebug(
            event = "gps_update",
            wallMs = fixTimeMs,
            intervalMs = 0L,
            spm = currentSpm,
            filtered = if (kalmanInitialised) kalmanX else null,
            inPlaceHold = false,
            signalSource = "gps",
            desiredActivity = null,
            dwellMs = switchAccumMs.toLong(),
            decisionReason = "kalman_update",
            prevActivity = currentActivity
        )
    }

    // ── Classification thresholds (m/s on the filtered speed) ─────────────────
    private val RUN_ENTER_MPS = 1.9    // walk → run above this (sustained)
    private val RUN_EXIT_MPS  = 1.7    // run → walk below this (sustained) …
    private val LOW_WALK_MPS  = 0.8    // … but above this. Below = in-place/paused → HOLD
    private val STATE_DWELL_MS = 8_000L
    private val DWELL_DECAY    = 1.0   // leaky-bucket: counter-evidence drains at this × dt

    // Leaky-bucket switch evidence. switchAccumMs accumulates time toward the pending
    // switch (stateCandidate) and drains when evidence reverses, so brief GPS wobble
    // across the threshold no longer hard-resets progress (the v9 fragility).
    private var stateCandidate: ActivityType? = null
    private var switchAccumMs = 0.0

    var currentActivity = ActivityType.IDLE
        private set

    // ── Debug / fallback helpers ──────────────────────────────────────────────
    val currentSpm: Int
        get() {
            if (stepIntervals.size < 2) return 0
            val sorted = stepIntervals.sorted()
            return (60_000.0 / sorted[sorted.size / 2]).toInt()
        }
    private val runVoteCount: Int  get() = classVotes.count { it == ActivityType.RUNNING }
    private val walkVoteCount: Int get() = classVotes.count { it == ActivityType.WALKING }

    private val filteredGpsSpeed: Double? get() = if (kalmanInitialised) kalmanX else null

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

        val spm = currentSpm
        val cadenceVote = if (spm >= CLASSIFY_SPM) ActivityType.RUNNING else ActivityType.WALKING
        if (classVotes.size >= VOTE_WINDOW) classVotes.removeFirst()
        classVotes.addLast(cadenceVote)

        val gpsAge       = (wallMs - latestGpsTimeMs).coerceAtLeast(0L)
        val haveFreshGps = kalmanInitialised && gpsAge <= GPS_STALE_MS
        val filtered     = filteredGpsSpeed

        val previousActivity = currentActivity
        var inPlaceHold = false
        var signalSource = if (haveFreshGps) "gps" else "cadence_fallback"
        var desired: ActivityType? = null
        var decisionReason = "stable"

        // ── Bootstrap from IDLE ───────────────────────────────────────────────
        if (currentActivity == ActivityType.IDLE) {
            when {
                haveFreshGps && filtered != null -> {
                    currentActivity = if (filtered >= (RUN_ENTER_MPS + RUN_EXIT_MPS) / 2)
                        ActivityType.RUNNING else ActivityType.WALKING
                    stateCandidate = null
                    signalSource = "bootstrap_gps"
                    decisionReason = "bootstrap_gps"
                    Log.d("StepDebug", "bootstrap(gps) → $currentActivity  filt=%.2f".format(filtered))
                    emitDebug("bootstrap", wallMs, intervalMs, spm, filtered, false,
                        signalSource, currentActivity, 0L, decisionReason, previousActivity)
                    listener.onActivityChanged(currentActivity, wallMs)
                }
                classVotes.size >= 5 -> {
                    currentActivity = if (runVoteCount > walkVoteCount)
                        ActivityType.RUNNING else ActivityType.WALKING
                    stateCandidate = null
                    signalSource = "bootstrap_cadence"
                    decisionReason = "bootstrap_cadence"
                    Log.d("StepDebug", "bootstrap(cadence) → $currentActivity  spm=$spm")
                    emitDebug("bootstrap", wallMs, intervalMs, spm, filtered, false,
                        signalSource, currentActivity, 0L, decisionReason, previousActivity)
                    listener.onActivityChanged(currentActivity, wallMs)
                }
                else -> decisionReason = "idle_waiting"
            }
        }

        // ── Steady-state decision ─────────────────────────────────────────────
        if (currentActivity != ActivityType.IDLE) {
            if (haveFreshGps && filtered != null) {
                if (filtered < LOW_WALK_MPS) {
                    inPlaceHold = currentActivity == ActivityType.RUNNING
                    stateCandidate = null
                    desired = currentActivity
                    decisionReason = if (inPlaceHold) "hold_in_place" else "hold_paused"
                } else {
                    desired = when (currentActivity) {
                        ActivityType.WALKING -> if (filtered > RUN_ENTER_MPS) ActivityType.RUNNING else ActivityType.WALKING
                        ActivityType.RUNNING -> if (filtered < RUN_EXIT_MPS)  ActivityType.WALKING else ActivityType.RUNNING
                        else -> currentActivity
                    }
                    decisionReason = if (desired == currentActivity) "stable" else "dwell_pending"
                }
            } else {
                signalSource = "cadence_fallback"
                desired = when (currentActivity) {
                    ActivityType.WALKING -> if (runVoteCount  >= VOTES_TO_RUN)  ActivityType.RUNNING else ActivityType.WALKING
                    ActivityType.RUNNING -> if (walkVoteCount >= VOTES_TO_WALK) ActivityType.WALKING else ActivityType.RUNNING
                    else -> currentActivity
                }
                decisionReason = if (desired == currentActivity) "stable_cadence" else "dwell_pending_cadence"
            }

            // ── Leaky-bucket dwell gate ───────────────────────────────────────
            // dt is the step interval, clamped so a long GPS gap can't dump a huge
            // chunk of evidence in (or out) of the bucket in one step.
            val dtMs = intervalMs.toDouble().coerceIn(0.0, 2_000.0)
            if (inPlaceHold || desired == null || desired == currentActivity) {
                // No switch wanted (or paused): drain evidence.
                switchAccumMs = (switchAccumMs - dtMs * DWELL_DECAY).coerceAtLeast(0.0)
                if (switchAccumMs == 0.0) stateCandidate = null
            } else {
                // Switch wanted: a new target restarts the bucket; same target fills it.
                if (stateCandidate != desired) {
                    stateCandidate = desired
                    switchAccumMs = 0.0
                    emitDebug("candidate_start", wallMs, intervalMs, spm, filtered, inPlaceHold,
                        signalSource, desired, 0L, decisionReason, previousActivity)
                }
                switchAccumMs += dtMs
                if (switchAccumMs >= STATE_DWELL_MS) {
                    val beforeSwitch = currentActivity
                    currentActivity = desired
                    stateCandidate = null
                    switchAccumMs = 0.0
                    decisionReason = "switch_committed"
                    Log.d("StepDebug", "SWITCH $beforeSwitch→$currentActivity  " +
                        "filt=${filtered?.let { "%.2f".format(it) } ?: "n/a"}  spm=$spm")
                    emitDebug("switch", wallMs, intervalMs, spm, filtered, inPlaceHold,
                        signalSource, desired, 0L, decisionReason, beforeSwitch)
                    listener.onActivityChanged(currentActivity, wallMs)
                }
            }
        }

        val dwellMs = switchAccumMs.toLong()
        if (decisionReason !in setOf("bootstrap_gps", "bootstrap_cadence", "switch_committed")) {
            emitDebug("step", wallMs, intervalMs, spm, filtered, inPlaceHold,
                signalSource, desired, dwellMs, decisionReason, previousActivity)
        }
        listener.onStep(wallMs, currentActivity)
    }

    private fun emitDebug(
        event: String,
        wallMs: Long,
        intervalMs: Long,
        spm: Int,
        filtered: Double?,
        inPlaceHold: Boolean,
        signalSource: String,
        desiredActivity: ActivityType?,
        dwellMs: Long,
        decisionReason: String,
        prevActivity: ActivityType
    ) = listener.onClassifierDebug(
        DebugSample(
            event = event,
            wallTimeMs = wallMs,
            intervalMs = intervalMs,
            spm = spm,
            rawGpsMps = latestGpsRawSpeedMps,
            filteredGpsMps = filtered,
            kalmanGain = lastKalmanGain,
            gpsAgeMs = if (latestGpsTimeMs > 0L) (wallMs - latestGpsTimeMs).coerceAtLeast(0L) else -1L,
            inPlaceHold = inPlaceHold,
            signalSource = signalSource,
            desiredActivity = desiredActivity,
            candidate = stateCandidate,
            dwellMs = dwellMs,
            dwellRequiredMs = STATE_DWELL_MS,
            cadenceRunVotes = runVoteCount,
            cadenceWalkVotes = walkVoteCount,
            decisionReason = decisionReason,
            previousActivity = prevActivity,
            currentActivity = currentActivity
        )
    )

    // ── Reset ─────────────────────────────────────────────────────────────────

    fun reset(seedActivity: ActivityType = ActivityType.IDLE) {
        stepIntervals.clear(); classVotes.clear()
        currentActivity = seedActivity
        lastStepWallMs = 0L; lastMag = 0f; rising = false
        offsetInitialised = false
        stateCandidate = null; switchAccumMs = 0.0
        kalmanInitialised = false; kalmanX = 0.0; kalmanP = 1.0; lastKalmanGain = null
        latestGpsRawSpeedMps = null; latestGpsTimeMs = 0L
    }
}
