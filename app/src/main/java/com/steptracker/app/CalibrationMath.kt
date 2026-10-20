package com.steptracker.app

/**
 * Rules of the step-length calibration wizard: how much data a measurement needs and
 * which metres-per-detected-impact values may be saved.
 */
object CalibrationMath {

    /** Detected peaks (tracker units); ~footfalls ≈ peaks / 2. */
    const val MIN_STEPS = 40
    const val MIN_GPS_DIST_M = 20.0
    const val MIN_MANUAL_DIST_M = 15.0
    private const val SLOW_WALK_PEAKS_PER_STEP = 1.6

    data class Bounds(val minOk: Double, val maxOk: Double, val ideal: Double) {
        fun accepts(stride: Double): Boolean = stride in minOk..maxOk
    }

    /**
     * Acceptable metres-per-detected-impact. With height, cap just below a full
     * anatomical stride so values like 0.94 m for a 1.75 m walker are rejected —
     * that is longer than a real step and cannot be a valid pocket (or hand) walk length.
     */
    fun bounds(heightCm: Float, jog: Boolean): Bounds {
        if (jog) {
            if (heightCm >= 120f) {
                val full = UserPrefs.expectedRunStrideM(heightCm)
                val ideal = full / UserPrefs.RUN_PEAKS_PER_STEP
                return Bounds(
                    minOk = (ideal * 0.55).coerceAtLeast(0.30),
                    // Allow slightly over half-stride; never above a full stride.
                    maxOk = (ideal * 1.55).coerceAtMost(full * 0.95),
                    ideal = ideal
                )
            }
            return Bounds(0.35, 0.95, 0.60)
        }
        if (heightCm >= 120f) {
            val full = UserPrefs.expectedWalkStrideM(heightCm)
            val ideal = full / UserPrefs.WALK_PEAKS_PER_STEP
            return Bounds(
                // Shorter means ~2 peaks on every step: a slow walk whose length would
                // halve every normal-pace walk afterwards.
                minOk = (full / SLOW_WALK_PEAKS_PER_STEP).coerceAtLeast(0.22),
                // e.g. 1.75 m → full≈0.73, max≈0.62 — rejects 0.94
                maxOk = (ideal * 1.55).coerceAtMost(full * 0.85),
                ideal = ideal
            )
        }
        // No height: still reject "almost a metre" walk lengths.
        return Bounds(0.25, 0.65, 0.40)
    }

    /** Metres per impact from GPS, or null when there are too few impacts or too little GPS distance. */
    fun strideFromGps(steps: Int, gpsDistM: Double): Double? =
        if (steps >= MIN_STEPS && gpsDistM >= MIN_GPS_DIST_M) gpsDistM / steps else null

    /** Metres per impact from a typed distance, or null when it is unusable. */
    fun strideFromManual(steps: Int, distM: Double?): Double? =
        if (steps >= MIN_STEPS && distM != null && distM >= MIN_MANUAL_DIST_M) distM / steps else null

    /** Faster than any jog (~29 km/h): the fix jumped, the runner didn't. */
    const val MAX_GPS_SPEED_MPS = 8.0

    fun isGpsBounce(deltaM: Double, dtSec: Double): Boolean =
        deltaM / dtSec.coerceAtLeast(0.001) > MAX_GPS_SPEED_MPS

    /** Below 0.5 m is standing-still jitter; above 40 m between 0.5 s fixes is a jump. */
    fun countsAsGpsDistance(deltaM: Double): Boolean = deltaM in 0.5..40.0
}
