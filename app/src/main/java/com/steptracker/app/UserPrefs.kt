package com.steptracker.app

import android.content.Context

class UserPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)

    // Metres per *detected peak*. The counter registers more than one peak per anatomical
    // step (see WALK_PEAKS_PER_STEP / RUN_PEAKS_PER_STEP), so defaults are shorter than a step.
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

    // No legacy fallback: the old single "calibrated" flag can't say which gait was
    // measured, and claiming a jog calibration that never happened is the worse error.
    var runCalibrated: Boolean
        get() = prefs.getBoolean("run_calibrated", false)
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

    /** When the "allow unrestricted battery" offer was last shown (0 = never). */
    var batteryPromptLastMs: Long
        get() = prefs.getLong("battery_prompt_last_ms",
            // Users who saw the old one-time prompt count as asked "a while ago".
            if (prefs.getBoolean("asked_battery_unrestricted", false)) 1L else 0L)
        set(v) { prefs.edit().putLong("battery_prompt_last_ms", v).apply() }

    /** How many times the user answered "Not now". */
    var batteryPromptDeclines: Int
        get() = prefs.getInt("battery_prompt_declines", 0)
        set(v) { prefs.edit().putInt("battery_prompt_declines", v).apply() }

    companion object {
        /**
         * Detected peaks per anatomical step, measured on recorded workouts: walking adds a
         * mid-stance peak on most steps (it only clears the 280 ms gap below ~107 steps/min),
         * jogging mostly doesn't.
         */
        const val WALK_PEAKS_PER_STEP = 1.5
        const val RUN_PEAKS_PER_STEP = 1.6
        fun peaksPerStep(jog: Boolean): Double = if (jog) RUN_PEAKS_PER_STEP else WALK_PEAKS_PER_STEP
        /** Anatomical adult walk / easy-jog stride (metres). */
        const val ANATOMICAL_WALK_STRIDE_M = 0.78
        const val ANATOMICAL_RUN_STRIDE_M = 1.20
        /** Uncalibrated defaults: metres per detected peak. */
        val DEFAULT_WALK_STRIDE_M = ANATOMICAL_WALK_STRIDE_M / WALK_PEAKS_PER_STEP
        val DEFAULT_RUN_STRIDE_M = ANATOMICAL_RUN_STRIDE_M / RUN_PEAKS_PER_STEP

        /** Typical walk stride ≈ 41.5% of height. */
        fun expectedWalkStrideM(heightCm: Float): Double =
            (heightCm / 100.0) * 0.415

        /** Typical easy-jog stride ≈ 65% of height. */
        fun expectedRunStrideM(heightCm: Float): Double =
            (heightCm / 100.0) * 0.65

        fun peakWalkStrideM(heightCm: Float = 0f): Double =
            (if (heightCm >= 120f) expectedWalkStrideM(heightCm) else ANATOMICAL_WALK_STRIDE_M) /
                WALK_PEAKS_PER_STEP

        fun peakRunStrideM(heightCm: Float = 0f): Double =
            (if (heightCm >= 120f) expectedRunStrideM(heightCm) else ANATOMICAL_RUN_STRIDE_M) /
                RUN_PEAKS_PER_STEP
    }

    /** Replace leftover anatomical defaults only for gaits that were never calibrated. */
    fun applyUncalibratedPeakDefaults() {
        if (!walkCalibrated) walkStrideM = peakWalkStrideM(heightCm)
        if (!runCalibrated) runStrideM = peakRunStrideM(heightCm)
    }
}
