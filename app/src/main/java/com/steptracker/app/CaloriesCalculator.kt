package com.steptracker.app

/**
 * Estimates energy expenditure from walk/run durations (and optional pace).
 *
 * Uses the standard MET × body-mass × hours model (Compendium of Physical
 * Activities). Values are estimates for healthy adults — not medical advice.
 *
 * Jogging is stored in History as part of [WorkoutRecord.runDurationMs], so the
 * run MET is pace-banded from easy-jog up through faster running when distance
 * is available.
 */
object CaloriesCalculator {

    /** Brisk walk default when pace is unknown. */
    const val MET_WALK_DEFAULT = 3.5

    /** Easy jog default when pace is unknown. */
    const val MET_RUN_DEFAULT = 7.0

    /**
     * @return whole kilocalories, or 0 when weight is missing/invalid or there
     *         is no timed activity.
     */
    fun estimateKcal(
        weightKg: Double?,
        walkDurationMs: Long,
        runDurationMs: Long,
        walkDistM: Double = 0.0,
        runDistM: Double = 0.0
    ): Int {
        val kg = weightKg?.takeIf { it in 25.0..300.0 } ?: return 0
        if (walkDurationMs <= 0L && runDurationMs <= 0L) return 0

        val walkHours = walkDurationMs / 3_600_000.0
        val runHours = runDurationMs / 3_600_000.0
        val walkMet = metForWalk(walkDistM, walkDurationMs)
        val runMet = metForRun(runDistM, runDurationMs)
        val kcal = walkMet * kg * walkHours + runMet * kg * runHours
        return kcal.coerceAtLeast(0.0).toInt()
    }

    fun estimateFor(record: WorkoutRecord, weightKg: Double?): Int = estimateKcal(
        weightKg = weightKg,
        walkDurationMs = record.walkDurationMs,
        runDurationMs = record.runDurationMs,
        walkDistM = record.walkDistM,
        runDistM = record.runDistM
    )

    /** Walking MET from pace (min/km); falls back to [MET_WALK_DEFAULT]. */
    fun metForWalk(distM: Double, durationMs: Long): Double {
        val pace = paceMinPerKm(distM, durationMs) ?: return MET_WALK_DEFAULT
        return when {
            pace < 10.0 -> 5.0   // very brisk / power walk
            pace < 12.5 -> 4.3   // brisk
            pace < 16.0 -> 3.5   // moderate
            else -> 2.8          // easy stroll
        }
    }

    /** Running-family MET from pace; falls back to [MET_RUN_DEFAULT]. */
    fun metForRun(distM: Double, durationMs: Long): Double {
        val pace = paceMinPerKm(distM, durationMs) ?: return MET_RUN_DEFAULT
        return when {
            pace < 5.0 -> 12.0   // ~12 km/h+
            pace < 6.0 -> 11.0
            pace < 7.0 -> 9.8    // ~8.5–10 km/h
            pace < 8.5 -> 8.3
            pace < 10.0 -> 7.0   // easy jog
            else -> 6.0          // very easy / shuffle jog
        }
    }

    private fun paceMinPerKm(distM: Double, durationMs: Long): Double? {
        if (distM < 50.0 || durationMs < 30_000L) return null
        val hours = durationMs / 3_600_000.0
        val km = distM / 1000.0
        if (km <= 0.0 || hours <= 0.0) return null
        return (hours * 60.0) / km
    }
}
