package com.steptracker.app

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.util.Log
import kotlin.math.sqrt

/**
 * Walk/run classifier — v12 (cadence-backed run→walk gate)
 *
 * v11 added an accelerometer impact gait gate so slow uphill running isn't
 * forced to WALKING by GPS dips. Logs from 2026-08-23 still showed long false
 * walking segments at ~186 SPM: impact baselines often aren't ready early in a
 * session, so gaitSaysRunning stayed false and speed alone still flipped run→walk.
 *
 * v12: a run/jog → walk transition also requires cadence to look like walking.
 * High SPM / run-majority votes keep the running family even when GPS sags and
 * gait baselines aren't learned yet.
 *
 * Still uses: GPS Kalman primary signal, hysteresis, impact gait when ready,
 * in-place hold, leaky-bucket dwell, cadence fallback without GPS.
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
        val recentImpact: Double,
        val walkImpactBase: Double?,
        val runImpactBase: Double?,
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

    // ── Accelerometer gait feature (peak vertical impact per step) ────────────
    // Running has a flight phase → harder landing impact than walking, independent
    // of forward speed. We take the median peak magnitude over a short window and
    // learn the user's walk vs run impact levels online from unambiguous GPS speeds.
    private val PEAK_WINDOW = 8
    private val peakMags    = ArrayDeque<Float>(PEAK_WINDOW)
    private var recentImpact = 0.0

    private val IMPACT_ALPHA       = 0.08   // EWMA rate for the learned baselines
    private val WALK_CONFIDENT_MPS = 1.2    // clearly walking → learn walk impact
    private val RUN_CONFIDENT_MPS  = 2.3    // clearly running → learn run impact
    private val MIN_IMPACT_RATIO   = 1.25   // run baseline must exceed walk × this to trust gait
    private var walkImpactEwma = 0.0
    private var runImpactEwma  = 0.0
    private var walkImpactSeen = false
    private var runImpactSeen  = false

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
    private val RUN_ENTER_MPS = 1.9    // walk → jog above this (sustained)
    private val RUN_EXIT_MPS  = 1.7    // jog/run → walk below this (sustained) …
    private val LOW_WALK_MPS  = 0.8    // … but above this. Below = in-place/paused → HOLD
    // Jog ↔ run split inside the running family (hysteresis avoids churn near the line).
    private val JOG_RUN_ENTER_MPS = 2.3   // jog → run above this
    private val JOG_RUN_EXIT_MPS  = 2.0   // run → jog below this
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

    /** True once both walk and run impact baselines are learned and well separated. */
    private val gaitSeparationReady: Boolean
        get() = walkImpactSeen && runImpactSeen && runImpactEwma > walkImpactEwma * MIN_IMPACT_RATIO

    /** Release builds omit classifier diagnostics (Play / privacy). */
    private fun debugLog(msg: String) {
        if (BuildConfig.DEBUG) Log.d("StepDebug", msg)
    }
    private val gaitSaysRunning: Boolean
        get() = gaitSeparationReady && recentImpact >= (walkImpactEwma + runImpactEwma) / 2.0

    /** Cadence still looks like the running family (votes + instantaneous SPM). */
    private val cadenceSaysRunning: Boolean
        get() = runVoteCount >= 5 || currentSpm >= CLASSIFY_SPM

    /**
     * Allow run/jog → walk only when BOTH speed says walk AND neither gait nor
     * cadence still insist on running. Prevents GPS-dip false walks while jogging.
     */
    private fun allowRunToWalk(filtered: Double): Boolean {
        if (filtered >= RUN_EXIT_MPS) return false
        if (gaitSaysRunning || cadenceSaysRunning) return false
        return true
    }

    private fun isRunningFamily(a: ActivityType) =
        a == ActivityType.JOGGING || a == ActivityType.RUNNING

    /** Within the running family, pick JOGGING vs RUNNING by speed with hysteresis. */
    private fun runningTierFor(current: ActivityType, filtered: Double): ActivityType = when (current) {
        ActivityType.RUNNING -> if (filtered < JOG_RUN_EXIT_MPS) ActivityType.JOGGING else ActivityType.RUNNING
        else                 -> if (filtered >= JOG_RUN_ENTER_MPS) ActivityType.RUNNING else ActivityType.JOGGING
    }

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
            // lastMag is the peak of the just-finished upswing = this step's impact.
            if (gap >= MIN_STEP_MS) registerStep(wallMs, gap, lastMag)
        } else {
            rising = false
        }
    }

    private fun registerStep(wallMs: Long, intervalMs: Long, peakMag: Float) {
        lastStepWallMs = wallMs
        if (stepIntervals.size >= INTERVAL_WINDOW) stepIntervals.removeFirst()
        stepIntervals.addLast(intervalMs)

        // Rolling median peak impact = the gait feature.
        if (peakMags.size >= PEAK_WINDOW) peakMags.removeFirst()
        peakMags.addLast(peakMag)
        recentImpact = peakMags.sorted().let { it[it.size / 2] }.toDouble()

        val spm = currentSpm
        val cadenceVote = if (spm >= CLASSIFY_SPM) ActivityType.RUNNING else ActivityType.WALKING
        if (classVotes.size >= VOTE_WINDOW) classVotes.removeFirst()
        classVotes.addLast(cadenceVote)

        val gpsAge       = (wallMs - latestGpsTimeMs).coerceAtLeast(0L)
        val haveFreshGps = kalmanInitialised && gpsAge <= GPS_STALE_MS
        val filtered     = filteredGpsSpeed

        // Learn the user's walk vs run impact levels from unambiguous GPS speeds.
        if (haveFreshGps && filtered != null && recentImpact > 0.0) {
            when {
                filtered < WALK_CONFIDENT_MPS -> {
                    walkImpactEwma = if (walkImpactSeen) walkImpactEwma + IMPACT_ALPHA * (recentImpact - walkImpactEwma) else recentImpact
                    walkImpactSeen = true
                }
                filtered > RUN_CONFIDENT_MPS -> {
                    runImpactEwma = if (runImpactSeen) runImpactEwma + IMPACT_ALPHA * (recentImpact - runImpactEwma) else recentImpact
                    runImpactSeen = true
                }
            }
        }

        val previousActivity = currentActivity
        var inPlaceHold = false
        var signalSource = if (haveFreshGps) "gps" else "cadence_fallback"
        var desired: ActivityType? = null
        var decisionReason = "stable"

        // ── Bootstrap from IDLE ───────────────────────────────────────────────
        if (currentActivity == ActivityType.IDLE) {
            when {
                haveFreshGps && filtered != null -> {
                    currentActivity = when {
                        filtered >= JOG_RUN_ENTER_MPS -> ActivityType.RUNNING
                        filtered >= (RUN_ENTER_MPS + RUN_EXIT_MPS) / 2 -> ActivityType.JOGGING
                        else -> ActivityType.WALKING
                    }
                    stateCandidate = null
                    signalSource = "bootstrap_gps"
                    decisionReason = "bootstrap_gps"
                    debugLog("bootstrap(gps) → $currentActivity  filt=%.2f".format(filtered))
                    emitDebug("bootstrap", wallMs, intervalMs, spm, filtered, false,
                        signalSource, currentActivity, 0L, decisionReason, previousActivity)
                    listener.onActivityChanged(currentActivity, wallMs)
                }
                classVotes.size >= 5 -> {
                    // No GPS yet: cadence can only tell walk vs running-family → start as jogging.
                    currentActivity = if (runVoteCount > walkVoteCount)
                        ActivityType.JOGGING else ActivityType.WALKING
                    stateCandidate = null
                    signalSource = "bootstrap_cadence"
                    decisionReason = "bootstrap_cadence"
                    debugLog("bootstrap(cadence) → $currentActivity  spm=$spm")
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
                    inPlaceHold = isRunningFamily(currentActivity)
                    stateCandidate = null
                    desired = currentActivity
                    decisionReason = if (inPlaceHold) "hold_in_place" else "hold_paused"
                } else {
                    desired = when (currentActivity) {
                        ActivityType.WALKING ->
                            if (filtered > RUN_ENTER_MPS) runningTierFor(ActivityType.WALKING, filtered)
                            else ActivityType.WALKING
                        // Running family (JOGGING / RUNNING):
                        else -> {
                            // Speed says walk — only believe it when gait AND cadence also
                            // look like walking (v12). High SPM at low GPS = uphill / slow jog.
                            if (allowRunToWalk(filtered)) ActivityType.WALKING
                            else runningTierFor(currentActivity, filtered)
                        }
                    }
                    decisionReason = when {
                        desired == currentActivity && isRunningFamily(currentActivity) &&
                            filtered < RUN_EXIT_MPS && (gaitSaysRunning || cadenceSaysRunning) ->
                            if (gaitSaysRunning) "hold_gait_jog" else "hold_cadence_jog"
                        desired == currentActivity -> "stable"
                        else -> "dwell_pending"
                    }
                }
            } else {
                signalSource = "cadence_fallback"
                // Without GPS, cadence can only separate walk vs running-family (not jog
                // vs run), so running-family is represented as JOGGING and held as-is.
                // Require clear walk cadence (not just majority) before leaving a run.
                desired = when (currentActivity) {
                    ActivityType.WALKING -> if (runVoteCount >= VOTES_TO_RUN) ActivityType.JOGGING else ActivityType.WALKING
                    else -> if (walkVoteCount >= VOTES_TO_WALK && !cadenceSaysRunning)
                        ActivityType.WALKING else currentActivity
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
                    debugLog("SWITCH $beforeSwitch→$currentActivity  " +
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
            recentImpact = recentImpact,
            walkImpactBase = if (walkImpactSeen) walkImpactEwma else null,
            runImpactBase = if (runImpactSeen) runImpactEwma else null,
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
        peakMags.clear(); recentImpact = 0.0
        walkImpactEwma = 0.0; runImpactEwma = 0.0
        walkImpactSeen = false; runImpactSeen = false
    }
}
