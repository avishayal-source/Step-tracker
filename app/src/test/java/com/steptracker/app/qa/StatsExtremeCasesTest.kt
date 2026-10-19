package com.steptracker.app.qa

import com.steptracker.app.CaloriesCalculator
import com.steptracker.app.Format
import com.steptracker.app.WorkoutInsights
import com.steptracker.app.WorkoutRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.Locale

/** Calories, History insights and number formatting with hostile or corrupted values. */
class StatsExtremeCasesTest {

    @get:Rule val tz = TimeZoneRule("Asia/Jerusalem")

    private val hour = 3_600_000L

    // ── Calories ──────────────────────────────────────────────────────────────

    @Test
    fun weightOutsideValidRange_givesZero() {
        for (kg in listOf(null, 0.0, 24.99, 300.01, -70.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertEquals("kg=$kg", 0, CaloriesCalculator.estimateKcal(kg, hour, hour))
        }
        assertTrue(CaloriesCalculator.estimateKcal(25.0, hour, 0) > 0)
        assertTrue(CaloriesCalculator.estimateKcal(300.0, hour, 0) > 0)
    }

    @Test
    fun corruptDistances_neverCrashOrGoNegative() {
        for (d in listOf(Double.NaN, Double.POSITIVE_INFINITY, -1000.0, 1e12, 0.0)) {
            val k = CaloriesCalculator.estimateKcal(70.0, hour, hour, d, d)
            assertTrue("dist=$d kcal=$k", k in 1..3000)
        }
    }

    @Test
    fun hugeDurations_doNotOverflowNegative() {
        for (ms in listOf(Long.MAX_VALUE, Long.MAX_VALUE / 2, 365L * 24 * hour)) {
            assertTrue(CaloriesCalculator.estimateKcal(70.0, ms, ms) >= 0)
        }
    }

    @Test
    fun calories_growWithTimeAndWeight() {
        var prev = 0
        for (min in listOf(1, 5, 30, 60, 180)) {
            val k = CaloriesCalculator.estimateKcal(70.0, 0, min * 60_000L)
            assertTrue(k >= prev); prev = k
        }
        assertTrue(CaloriesCalculator.estimateKcal(110.0, hour, 0) > CaloriesCalculator.estimateKcal(60.0, hour, 0))
    }

    @Test
    fun oneHourAtWorldClassPace_staysPhysiological() {
        // 15 km in an hour, 70 kg — real-world ~1000–1100 kcal.
        val k = CaloriesCalculator.estimateKcal(70.0, 0, hour, 0.0, 15_000.0)
        assertTrue("kcal=$k", k in 700..1200)
    }

    /** Clock changes mid-workout can yield a negative duration; it must not subtract calories. */
    @Test
    fun defect_negativeDurationNeverReducesCalories() {
        val runOnly = CaloriesCalculator.estimateKcal(70.0, 0, hour)
        val withNegWalk = CaloriesCalculator.estimateKcal(70.0, -hour / 2, hour)
        assertEquals(runOnly, withNegWalk)
    }

    // ── Insights ──────────────────────────────────────────────────────────────

    private fun rec(dateMs: Long, runMin: Double = 30.0, runKm: Double = 5.0, walkMin: Double = 5.0, walkKm: Double = 0.5) =
        WorkoutRecord(
            id = dateMs, dateMs = dateMs, walkSteps = 500, runSteps = 4000,
            walkDistM = walkKm * 1000, runDistM = runKm * 1000,
            walkDurationMs = (walkMin * 60_000).toLong(), runDurationMs = (runMin * 60_000).toLong()
        )

    @Test
    fun insights_emptyAndDegenerateHistories() {
        val now = localTime(2026, 11, 10, 9)
        assertTrue(WorkoutInsights.compute(emptyList(), now).isEmpty())
        WorkoutInsights.compute(listOf(rec(now, 0.0, 0.0, 0.0, 0.0)), now)
        WorkoutInsights.compute(listOf(rec(now + 30 * DAY_MS)), now)          // future-dated (wrong clock)
        WorkoutInsights.compute(listOf(rec(0L), rec(Long.MAX_VALUE / 4)), now)
    }

    @Test
    fun streak_isCountedAcrossDstEnd() {
        val days = (20..30).map { localTime(2026, 10, it, 7) }
        val lines = WorkoutInsights.compute(days.map { rec(it) }, localTime(2026, 10, 30, 20))
        assertTrue(lines.toString(), lines.any { it.contains("11-day") })
    }

    @Test
    fun insights_onTwentyThousandRecords_isFast() {
        val now = localTime(2026, 11, 10, 9)
        val many = (0 until 20_000).map { rec(now - it * 4 * hour) }
        val t0 = System.nanoTime()
        WorkoutInsights.compute(many, now)
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue("took $ms ms", ms < 2_000)
    }

    /** A manually edited 0.01-minute run must not produce "You ran 50000% faster". */
    @Test
    fun defect_paceInsight_ignoresImplausibleSessions() {
        val now = localTime(2026, 11, 10, 9)
        val history = listOf(rec(now, runMin = 0.01, runKm = 5.0), rec(now - 2 * DAY_MS), rec(now - 4 * DAY_MS))
        val pace = WorkoutInsights.compute(history, now).firstOrNull { it.startsWith("🏃") }
        val pct = pace?.let { Regex("""(\d+)%""").find(it)?.groupValues?.get(1)?.toInt() } ?: 0
        assertTrue("insight says: $pace", pct <= 100)
    }

    // ── Formatting ────────────────────────────────────────────────────────────

    @Test
    fun distanceFormatting_edges() {
        assertEquals("0 m", Format.dist(0.0))
        assertEquals("999 m", Format.dist(999.9))
        assertEquals("1.00 km", Format.dist(1000.0))
        assertEquals("0 m", Format.dist(Double.NaN))
        assertEquals("", Format.kcal(0))
        assertEquals("", Format.kcal(-5))
    }

    @Test
    fun distanceFormatting_inOtherLocales_neverThrows() {
        val saved = Locale.getDefault()
        try {
            for (l in listOf("he-IL", "de-DE", "ar-EG", "hi-IN", "fa-IR").map(Locale::forLanguageTag)) {
                Locale.setDefault(l)
                assertTrue(Format.dist(12_345.0).endsWith("km"))
            }
        } finally {
            Locale.setDefault(saved)
        }
    }
}
