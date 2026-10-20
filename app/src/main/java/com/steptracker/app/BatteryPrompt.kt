package com.steptracker.app

/** When to offer "allow unrestricted battery" again on Start, after the user said "Not now". */
object BatteryPrompt {
    const val REOFFER_AFTER_MS = 14L * 24 * 60 * 60 * 1000
    /** After this many "Not now" answers it is only reachable from the More menu. */
    const val MAX_DECLINES = 3

    fun shouldOffer(declines: Int, lastShownMs: Long, nowMs: Long): Boolean = when {
        lastShownMs == 0L -> true
        declines >= MAX_DECLINES -> false
        else -> nowMs - lastShownMs >= REOFFER_AFTER_MS
    }
}
