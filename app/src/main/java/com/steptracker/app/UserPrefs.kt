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
}
