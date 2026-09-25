package com.steptracker.app

/** Shared formatting helpers so distance/units render consistently across screens. */
object Format {
    /** Metres → "123 m" or "1.23 km" (2 decimals for km). */
    fun dist(m: Double): String =
        if (m >= 1000) "${"%.2f".format(m / 1000)} km" else "${m.toInt()} m"

    /** Whole kilocalories → "320 kcal", or empty when unknown. */
    fun kcal(kcal: Int): String = if (kcal > 0) "$kcal kcal" else ""
}
