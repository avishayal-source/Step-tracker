package com.steptracker.app

import android.content.Context

/**
 * Tracks whether the user has accepted the legal terms (Terms of Service,
 * Privacy Policy, Health & AI disclaimers) shown during onboarding.
 *
 * Consent is versioned: bump [CURRENT_VERSION] whenever the legal documents
 * change materially, and existing users will be asked to re-accept.
 */
object LegalConsent {
    /** Increment when Terms / Privacy / disclaimers change materially. */
    const val CURRENT_VERSION = 1

    private const val PREFS = "legal_consent"
    private const val KEY_VERSION = "accepted_version"
    private const val KEY_TS = "accepted_at"

    fun isAccepted(ctx: Context): Boolean {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getInt(KEY_VERSION, 0) >= CURRENT_VERSION
    }

    fun accept(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_VERSION, CURRENT_VERSION)
            .putLong(KEY_TS, System.currentTimeMillis())
            .apply()
    }
}
