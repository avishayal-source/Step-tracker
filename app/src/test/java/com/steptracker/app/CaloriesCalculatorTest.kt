package com.steptracker.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CaloriesCalculatorTest {

    @Test
    fun noWeight_returnsZero() {
        assertEquals(
            0,
            CaloriesCalculator.estimateKcal(
                weightKg = null,
                walkDurationMs = 600_000L,
                runDurationMs = 1_800_000L
            )
        )
    }

    @Test
    fun knownSession_matchesMetFormula() {
        // 70 kg, 10 min walk @ default 3.5 MET, 30 min run @ default 7.0 MET
        // kcal = MET * kg * hours
        val walk = 3.5 * 70.0 * (10.0 / 60.0)
        val run = 7.0 * 70.0 * (30.0 / 60.0)
        val expected = (walk + run).toInt()
        assertEquals(
            expected,
            CaloriesCalculator.estimateKcal(
                weightKg = 70.0,
                walkDurationMs = 10 * 60_000L,
                runDurationMs = 30 * 60_000L
            )
        )
    }

    @Test
    fun fasterRunPace_usesHigherMet() {
        val easy = CaloriesCalculator.metForRun(distM = 3000.0, durationMs = 30 * 60_000L) // 10 min/km
        val hard = CaloriesCalculator.metForRun(distM = 5000.0, durationMs = 25 * 60_000L) // 5 min/km
        assertTrue(hard > easy)
    }
}
