package com.steptracker.app

import android.content.Context

class UserPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)

    // Walk step length in metres — raised default to match typical adult stride
    var walkStrideM: Double
        get() = prefs.getFloat("walk_stride", 0.78f).toDouble()
        set(v) { prefs.edit().putFloat("walk_stride", v.toFloat()).apply() }

    // Run step length in metres — raised default
    var runStrideM: Double
        get() = prefs.getFloat("run_stride", 1.20f).toDouble()
        set(v) { prefs.edit().putFloat("run_stride", v.toFloat()).apply() }

    var isCalibrated: Boolean
        get() = prefs.getBoolean("calibrated", false)
        set(v) { prefs.edit().putBoolean("calibrated", v).apply() }

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
        /** Typical walk stride ≈ 41.5% of height. */
        fun expectedWalkStrideM(heightCm: Float): Double =
            (heightCm / 100.0) * 0.415

        /** Typical easy-jog stride ≈ 65% of height. */
        fun expectedRunStrideM(heightCm: Float): Double =
            (heightCm / 100.0) * 0.65
    }
}
