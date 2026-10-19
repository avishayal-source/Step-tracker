package com.steptracker.app.qa

import com.steptracker.app.Format
import com.steptracker.app.HistorySummary
import com.steptracker.app.WorkoutInsights
import com.steptracker.app.WorkoutRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * History tab: the all-time summary block, per-row durations, and the insights lines
 * (last 7 days, pace trend, streak, 30-day frequency, personal best) for realistic
 * usage patterns.
 */
class HistoryTabTest {

    @get:Rule val tz = TimeZoneRule("Asia/Jerusalem")

    private val now get() = localTime(2026, 11, 30, 20)

    private fun rec(daysAgo: Int, runKm: Double = 5.0, runMin: Double = 30.0, walkKm: Double = 1.0,
                    walkMin: Double = 12.0, kcal: Int = 0, hour: Int = 7): WorkoutRecord {
        val date = plusCalendarDays(localMidnight(2026, 11, 30), -daysAgo) + hour * 3_600_000L
        return WorkoutRecord(date, date, (walkMin * 110).toInt(), (runMin * 165).toInt(),
            walkKm * 1000, runKm * 1000, (walkMin * 60_000).toLong(), (runMin * 60_000).toLong(), kcal)
    }

    private fun insight(lines: List<String>, emoji: String) = lines.firstOrNull { it.startsWith(emoji) }

    // ── Summary block ─────────────────────────────────────────────────────────

    @Test
    fun summary_totalsAndCalorieLine() {
        assertEquals("No workouts yet", HistorySummary.text(emptyList(), true))
        val rs = listOf(rec(1, 5.0, 30.0, 1.0, 12.0, 400), rec(3, 3.2, 20.0, 0.5, 6.0, 250))
        val s = HistorySummary.text(rs, true)
        assertTrue(s, s.startsWith("ALL TIME  ·  2 workouts"))
        assertTrue(s, s.contains("🚶 1.50 km  18m"))
        assertTrue(s, s.contains("🏃 8.20 km  50m"))
        assertTrue(s, s.contains("🔥 650 kcal (est.)"))
        assertTrue(HistorySummary.text(listOf(rec(1)), false).contains("Add your weight in Botty"))
        assertFalse(HistorySummary.text(listOf(rec(1)), true).contains("🔥"))
    }

    @Test
    fun summary_longTotalsRollIntoHours() {
        val year = (0 until 150).map { rec(it * 2, runMin = 45.0, walkMin = 10.0) }
        val s = HistorySummary.text(year, true)
        assertTrue(s, s.contains("🏃 750.00 km  112h 30m"))
    }

    @Test
    fun durations_formatting() {
        assertEquals("0m", Format.durHoursMinutes(0))
        assertEquals("59m", Format.durHoursMinutes(59 * 60_000L + 59_000))
        assertEquals("1h 1m", Format.durHoursMinutes(61 * 60_000L))
        assertEquals("100h 0m", Format.durHoursMinutes(100 * 3_600_000L))
        assertEquals("0s", Format.durMinutesSeconds(0))
        assertEquals("59s", Format.durMinutesSeconds(59_999))
        assertEquals("1m 1s", Format.durMinutesSeconds(61_000))
        assertEquals("90m 0s", Format.durMinutesSeconds(90 * 60_000L))
    }

    // ── Insights by usage pattern ─────────────────────────────────────────────

    @Test
    fun dailyUser_getsStreakAndFrequency() {
        val lines = WorkoutInsights.compute((0 until 10).map { rec(it) }, now)
        assertEquals("🔥 10-day workout streak — keep it alive!", insight(lines, "🔥"))
        assertEquals("📅 You worked out 10 times in the last 30 days.", insight(lines, "📅"))
        // Sessions at 07:00, "now" is 20:00: the one 7 days back is 7 d 13 h old and drops out.
        assertTrue(insight(lines, "🗓️")!!.contains("7 workouts"))
    }

    @Test
    fun occasionalUser_noStreak_frequencyAndWeekAreHonest() {
        val lines = WorkoutInsights.compute((0 until 8).map { rec(it * 7 + 2) }, now)
        assertNull(insight(lines, "🔥"))
        assertTrue(insight(lines, "📅")!!.contains("4 times"))
        assertTrue(insight(lines, "🗓️")!!.contains("1 workout ·"))
    }

    @Test
    fun streak_countsOnlyWhenLastWorkoutWasTodayOrYesterday() {
        assertTrue(insight(WorkoutInsights.compute(listOf(rec(1), rec(2), rec(3)), now), "🔥")!!.contains("3-day"))
        assertNull(insight(WorkoutInsights.compute(listOf(rec(2), rec(3), rec(4)), now), "🔥"))
        // Two sessions on one day still count as one streak day.
        assertTrue(insight(WorkoutInsights.compute(listOf(rec(0, hour = 7), rec(0, hour = 19), rec(1)), now), "🔥")!!.contains("2-day"))
    }

    @Test
    fun lastSevenDays_boundary() {
        val exactly7 = rec(0).copy(id = 1, dateMs = now - 7 * DAY_MS)
        val over7 = rec(0).copy(id = 2, dateMs = now - 7 * DAY_MS - 1)
        assertTrue(insight(WorkoutInsights.compute(listOf(exactly7), now), "🗓️")!!.contains("1 workout"))
        assertTrue(insight(WorkoutInsights.compute(listOf(over7), now), "🗓️")!!.contains("No workouts"))
    }

    @Test
    fun paceTrend_thresholds() {
        fun trend(latestMin: Double) = insight(WorkoutInsights.compute(
            listOf(rec(0, 5.0, latestMin), rec(3, 5.0, 30.0), rec(6, 5.0, 30.0)), now), "🏃")!!
        assertTrue(trend(29.5).contains("right on"))          // ~1.7 % faster
        assertTrue(trend(29.0).contains("faster"))            // ~3.4 % faster
        assertTrue(trend(31.0).contains("slower"))            // ~3.2 % slower
    }

    @Test
    fun personalBest_isTheLongestSession() {
        val lines = WorkoutInsights.compute(listOf(rec(1, 5.0), rec(5, 10.5), rec(9, 7.0)), now)
        assertEquals("🏆 Longest session: 11.50 km", insight(lines, "🏆"))
    }

    @Test
    fun walkOnlyHistory_hasNoPaceLine() {
        val lines = WorkoutInsights.compute((0 until 5).map { rec(it, runKm = 0.0, runMin = 0.0) }, now)
        assertNull(insight(lines, "🏃"))
    }

    /** Back after 5 weeks injured: what History tells the user on their first run back. */
    @Test
    fun returningFromInjury_insightsStillMakeSense() {
        val history = listOf(rec(0, 3.0, 21.0)) + (36 until 64 step 2).map { rec(it, 6.0, 33.0) }
        val lines = WorkoutInsights.compute(history, now)
        println("returning from injury: $lines")
        assertNull(insight(lines, "🔥"))
        assertTrue(insight(lines, "📅")!!.contains("1 time "))
    }

    /**
     * When there were no runs in the 28 days before the latest one, the baseline silently
     * falls back to older runs, but the line still says "4-week average".
     */
    @Test
    fun defect_paceInsight_onlyClaimsFourWeekAverageWhenItIsOne() {
        val history = listOf(rec(0, 3.0, 21.0)) + (36 until 64 step 2).map { rec(it, 6.0, 33.0) }
        val pace = insight(WorkoutInsights.compute(history, now), "🏃")
        assertTrue("says: $pace", pace == null || !pace.contains("4-week"))
    }
}
