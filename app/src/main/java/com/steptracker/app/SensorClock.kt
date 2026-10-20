package com.steptracker.app

/**
 * Maps sensor timestamps (nanoseconds since boot, monotonic) to wall-clock ms. The offset
 * is smoothed over the first [settleSamples] events to absorb delivery jitter, then frozen,
 * so a wall-clock change mid-session (NTP sync, time zone, manual edit) can't stretch or
 * shrink the gaps between steps.
 */
class SensorClock(private val settleSamples: Int = 100) {
    private var offsetMs = 0L
    private var samples = 0

    fun toWallMs(bootNs: Long, nowWallMs: Long = System.currentTimeMillis()): Long {
        val bootMs = bootNs / 1_000_000L
        if (samples < settleSamples) {
            val raw = nowWallMs - bootMs
            offsetMs = if (samples == 0) raw else (offsetMs * 15 + raw) / 16
            samples++
        }
        return offsetMs + bootMs
    }
}
