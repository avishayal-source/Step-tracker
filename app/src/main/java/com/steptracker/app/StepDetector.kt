package com.steptracker.app

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.util.Log
import kotlin.math.sqrt

/**
 * Walk/run classifier — v11 (GPS-speed Kalman + accelerometer gait gate, jogging tier)
 *
 * What v10 got wrong (June 18 session, 6,260 steps):
 * The user ran continuously from step ~486 to ~5043, but the run included slow
 * UPHILL stretches where GPS speed sagged to 1.43–1.66 m/s — straight into the band
 * that overlaps brisk walking. Because v10 used GPS speed as the SOLE primary signal
 * (run→walk below RUN_EXIT = 1.7), it chopped the single run into three false walking
 * blips. The smoking gun: at step 4034 cadence was 208 SPM with 10/10 "running" votes
 * — the gait signal was certain it was running — yet speed (1.43) forced WALKING.
 * Speed alone cannot separate slow uphill running from brisk walking; gait can.
 *
 * Design (changes from v10 in CAPS):
 * 1. PRIMARY SIGNAL = GPS speed, smoothed by a 1-D Kalman filter (random-walk model),
 *    updated once per fix in updateGpsSpeed().
 *
 * 2. HYSTERESIS BAND on filtered speed: walk→run above RUN_ENTER_MPS (1.9),
 *    run→walk below RUN_EXIT_MPS (1.7).
 *
 * 3. ACCELEROMETER GAIT GATE (the v11 fix): we track each step's PEAK impact
 *    magnitude (running has a flight phase → harder vertical impact than walking,
 *    independent of forward speed). The walk and run impact levels are learned
 *    ONLINE from moments when GPS speed is unambiguous (clearly walking / clearly
 *    running), so no manual calibration is needed. A run→walk switch is only allowed
 *    when the impact has ALSO dropped to walking level. If speed dips but impact is
 *    still running-like (uphill jog), the activity HOLDS as running.
 *
 * 4. JOGGING is a FULL activity state (not just a label). The walk↔run boundary is
 *    still the hard problem (gait + speed); within the running family the speed is
 *    split into JOGGING (slow / uphill) and RUNNING (fast) with its own hysteresis
 *    band (JOG_RUN_ENTER / JOG_RUN_EXIT). Any change between the three states is
 *    committed through the same leaky-bucket dwell, so jog↔run doesn't churn.
 *
 * 5. IN-PLACE HOLD: filtered speed below LOW_WALK_MPS (0.8) = stopped / running in
 *    place (e.g. at a light) → hold current activity, never flip to walking.
 *
 * 6. LEAKY-BUCKET DWELL (8 s): switch evidence accumulates and DECAYS when it
 *    reverses, riding through brief GPS noise instead of hard-resetting.
 *
 * 7. CADENCE FALLBACK when no fresh GPS fix is available.
 *
 * The peak impact and learned baselines are written to the debug CSV so the gait
 * thresholds can be validated/tuned against the next real session.
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

    /** Current step's impact sits in the running half of the learned walk↔run range. */
    private val gaitSaysRunning: Boolean
        get() = gaitSeparationReady && recentImpact >= (walkImpactEwma + runImpactEwma) / 2.0

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
                    Log.d("StepDebug", "bootstrap(gps) → $currentActivity  filt=%.2f".format(filtered))
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
                            // Speed says walk — but only believe it if the GAIT also dropped to
                            // walking level. Running-like impact at low speed = uphill / slow jog,
                            // so stay in the running family (the v11 fix for the false walk blips).
                            if (filtered < RUN_EXIT_MPS && !gaitSaysRunning) ActivityType.WALKING
                            else runningTierFor(currentActivity, filtered)
                        }
                    }
                    decisionReason = when {
                        desired == currentActivity && isRunningFamily(currentActivity) &&
                            filtered < RUN_EXIT_MPS && gaitSaysRunning -> "hold_gait_jog"
                        desired == currentActivity -> "stable"
                        else -> "dwell_pending"
                    }
                }
            } else {
                signalSource = "cadence_fallback"
                // Without GPS, cadence can only separate walk vs running-family (not jog
                // vs run), so running-family is represented as JOGGING and held as-is.
                desired = when (currentActivity) {
                    ActivityType.WALKING -> if (runVoteCount  >= VOTES_TO_RUN)  ActivityType.JOGGING else ActivityType.WALKING
                    else                 -> if (walkVoteCount >= VOTES_TO_WALK) ActivityType.WALKING else currentActivity
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
