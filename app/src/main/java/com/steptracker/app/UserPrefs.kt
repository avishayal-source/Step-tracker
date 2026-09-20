package com.steptracker.app

import android.content.Context

class UserPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)

    // Metres per *detected peak*. The counter registers ~two peaks per anatomical
    // stride, so uncalibrated defaults are half of a typical adult stride.
    var walkStrideM: Double
        get() = prefs.getFloat("walk_stride", DEFAULT_WALK_STRIDE_M.toFloat()).toDouble()
        set(v) { prefs.edit().putFloat("walk_stride", v.toFloat()).apply() }

    var runStrideM: Double
        get() = prefs.getFloat("run_stride", DEFAULT_RUN_STRIDE_M.toFloat()).toDouble()
        set(v) { prefs.edit().putFloat("run_stride", v.toFloat()).apply() }

    var isCalibrated: Boolean
        get() = walkCalibrated || runCalibrated
        set(v) {
            // Legacy setter used by backups / clear-bad-calib: both gaits together.
            prefs.edit()
                .putBoolean("calibrated", v)
                .putBoolean("walk_calibrated", v)
                .putBoolean("run_calibrated", v)
                .apply()
        }

    var walkCalibrated: Boolean
        get() = prefs.getBoolean("walk_calibrated", prefs.getBoolean("calibrated", false))
        set(v) {
            prefs.edit()
                .putBoolean("walk_calibrated", v)
                .putBoolean("calibrated", v || prefs.getBoolean("run_calibrated", false))
                .apply()
        }

    var runCalibrated: Boolean
        get() = prefs.getBoolean("run_calibrated", prefs.getBoolean("calibrated", false))
        set(v) {
            prefs.edit()
                .putBoolean("run_calibrated", v)
                .putBoolean("calibrated", prefs.getBoolean("walk_calibrated", false) || v)
                .apply()
        }

    /** Optional height for anthropometric stride suggestions (cm). 0 = unset. */
    var heightCm: Float
        get() = prefs.getFloat("height_cm", 0f)
        set(v) { prefs.edit().putFloat("height_cm", v).apply() }

    /** Whistle cues on walk/run transitions. */
    var soundCuesEnabled: Boolean
        get() = prefs.getBoolean("sound_cues", true)
        set(v) { prefs.edit().putBoolean("sound_cues", v).apply() }

    /** Spoken progress cues at the quarter points of a workout. */
    var voiceCuesEnabled: Boolean
        get() = prefs.getBoolean("voice_cues", true)
        set(v) { prefs.edit().putBoolean("voice_cues", v).apply() }

    /** First-run product tour (after legal consent). */
    var hasSeenProductHelp: Boolean
        get() = prefs.getBoolean("seen_product_help", false)
        set(v) { prefs.edit().putBoolean("seen_product_help", v).apply() }

    companion object {
        const val PEAKS_PER_STRIDE = 2.0
        /** Anatomical adult walk / easy-jog stride (metres). */
        const val ANATOMICAL_WALK_STRIDE_M = 0.78
        const val ANATOMICAL_RUN_STRIDE_M = 1.20
        /** Uncalibrated defaults: metres per detected peak. */
        val DEFAULT_WALK_STRIDE_M = ANATOMICAL_WALK_STRIDE_M / PEAKS_PER_STRIDE
        val DEFAULT_RUN_STRIDE_M = ANATOMICAL_RUN_STRIDE_M / PEAKS_PER_STRIDE

        /** Typical walk stride ≈ 41.5% of height. */
        fun expectedWalkStrideM(heightCm: Float): Double =
            (heightCm / 100.0) * 0.415

        /** Typical easy-jog stride ≈ 65% of height. */
        fun expectedRunStrideM(heightCm: Float): Double =
            (heightCm / 100.0) * 0.65

        fun peakWalkStrideM(heightCm: Float = 0f): Double =
            (if (heightCm >= 120f) expectedWalkStrideM(heightCm) else ANATOMICAL_WALK_STRIDE_M) /
                PEAKS_PER_STRIDE

        fun peakRunStrideM(heightCm: Float = 0f): Double =
            (if (heightCm >= 120f) expectedRunStrideM(heightCm) else ANATOMICAL_RUN_STRIDE_M) /
                PEAKS_PER_STRIDE
    }

    /** Replace leftover anatomical defaults only for gaits that were never calibrated. */
    fun applyUncalibratedPeakDefaults() {
        if (!walkCalibrated) walkStrideM = peakWalkStrideM(heightCm)
        if (!runCalibrated) runStrideM = peakRunStrideM(heightCm)
    }
}
