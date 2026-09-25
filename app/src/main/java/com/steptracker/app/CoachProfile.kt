package com.steptracker.app

import android.content.Context

/**
 * Reads Botty / coach intake fields used outside the Coach tab (calories, etc.).
 * Weight lives in the encrypted [coach_prefs] store written by [CoachView].
 */
object CoachProfile {

    private const val PREFS = "coach_prefs"
    private const val KEY_WEIGHT = "weight"

    /** Body mass in kg from the coach form, or null if unset / out of range. */
    fun weightKg(context: Context): Double? {
        val raw = SecurePrefs.open(context, PREFS).getString(KEY_WEIGHT, null) ?: return null
        return raw.toDoubleOrNull()?.takeIf { it in 25.0..300.0 }
    }
}
