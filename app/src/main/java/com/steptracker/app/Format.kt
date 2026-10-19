package com.steptracker.app

import java.util.concurrent.TimeUnit

/** Shared formatting helpers so distance/units render consistently across screens. */
object Format {
    /** Metres → "123 m" or "1.23 km" (2 decimals for km). */
    fun dist(m: Double): String =
        if (m >= 1000) "${"%.2f".format(m / 1000)} km" else "${m.toInt()} m"

    /** Whole kilocalories → "320 kcal", or empty when unknown. */
    fun kcal(kcal: Int): String = if (kcal > 0) "$kcal kcal" else ""

    /** Totals: "2h 5m" / "45m". */
    fun durHoursMinutes(ms: Long): String {
        val h = TimeUnit.MILLISECONDS.toHours(ms)
        val m = TimeUnit.MILLISECONDS.toMinutes(ms) % 60
        return if (h > 0) "${h}h ${m}m" else "${m}m"
    }

    /** One session: "12m 3s" / "40s". */
    fun durMinutesSeconds(ms: Long): String {
        val min = TimeUnit.MILLISECONDS.toMinutes(ms)
        val sec = TimeUnit.MILLISECONDS.toSeconds(ms) % 60
        return if (min > 0) "${min}m ${sec}s" else "${sec}s"
    }
}

/** The all-time block at the top of the History tab. */
object HistorySummary {
    fun text(records: List<WorkoutRecord>, weightKnown: Boolean): String {
        if (records.isEmpty()) return "No workouts yet"
        val totalKcal = records.sumOf { it.caloriesKcal }
        val kcalLine = when {
            totalKcal > 0 -> "\n🔥 ${Format.kcal(totalKcal)} (est.)"
            !weightKnown -> "\n🔥 Add your weight in Botty to estimate calories"
            else -> ""
        }
        return "ALL TIME  ·  ${records.size} workouts\n" +
            "🚶 ${Format.dist(records.sumOf { it.walkDistM })}  ${Format.durHoursMinutes(records.sumOf { it.walkDurationMs })}\n" +
            "🏃 ${Format.dist(records.sumOf { it.runDistM })}  ${Format.durHoursMinutes(records.sumOf { it.runDurationMs })}" +
            kcalLine
    }
}
