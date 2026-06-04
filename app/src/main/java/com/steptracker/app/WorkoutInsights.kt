package com.steptracker.app

import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * Derives human-readable insights from workout history. This is the "auto
 * workout summary" feature — pure stats over the Room-backed records (no LLM),
 * surfaced at the top of the History tab.
 *
 * Everything is computed from the in-memory list the screen already loaded,
 * so it stays cheap and side-effect free / easy to reason about.
 */
object WorkoutInsights {

    private val DAY_MS = TimeUnit.DAYS.toMillis(1)

    /**
     * Builds an ordered list of short, emoji-prefixed insight lines.
     * [records] is expected newest-first (as [WorkoutHistory.loadAll] returns),
     * but we don't rely on the order. Returns an empty list when there's nothing
     * meaningful to say.
     */
    fun compute(records: List<WorkoutRecord>, now: Long = System.currentTimeMillis()): List<String> {
        if (records.isEmpty()) return emptyList()

        val lines = mutableListOf<String>()
        val sorted = records.sortedByDescending { it.dateMs }

        last7DaysLine(sorted, now)?.let { lines.add(it) }
        paceTrendLine(sorted)?.let { lines.add(it) }
        streakLine(sorted, now)?.let { lines.add(it) }
        frequencyLine(sorted, now)?.let { lines.add(it) }
        personalBestLine(sorted)?.let { lines.add(it) }

        return lines
    }

    // ── Last 7 days activity ─────────────────────────────────────────────────
    private fun last7DaysLine(records: List<WorkoutRecord>, now: Long): String? {
        val recent = records.filter { now - it.dateMs <= 7 * DAY_MS }
        if (recent.isEmpty()) return "🗓️ No workouts in the last 7 days — time to get moving!"
        val dist = recent.sumOf { it.totalDistM }
        val plural = if (recent.size == 1) "workout" else "workouts"
        return "🗓️ Last 7 days: ${recent.size} $plural · ${Format.dist(dist)}"
    }

    // ── Running pace vs 4-week average ───────────────────────────────────────
    private fun paceTrendLine(records: List<WorkoutRecord>): String? {
        val runs = records.filter { it.runDurationMs > 0 && it.runDistM > 0 }
        if (runs.size < 2) return null

        val latest = runs.first()
        val latestSpeed = speedMps(latest.runDistM, latest.runDurationMs) ?: return null

        // Baseline: other runs within 28 days before the latest; fall back to all prior runs.
        val windowStart = latest.dateMs - 28 * DAY_MS
        var baseline = runs.drop(1).filter { it.dateMs >= windowStart }
        if (baseline.isEmpty()) baseline = runs.drop(1)
        if (baseline.isEmpty()) return null

        val baseDist = baseline.sumOf { it.runDistM }
        val baseDur = baseline.sumOf { it.runDurationMs }
        val baseSpeed = speedMps(baseDist, baseDur) ?: return null
        if (baseSpeed <= 0) return null

        val pct = ((latestSpeed - baseSpeed) / baseSpeed) * 100.0
        return when {
            pct >= 3 -> "🏃 You ran ${pct.toInt()}% faster than your 4-week average — nice!"
            pct <= -3 -> "🏃 Your last run was ${(-pct).toInt()}% slower than your 4-week average."
            else -> "🏃 Your running pace is right on your 4-week average."
        }
    }

    // ── Consecutive-day streak ───────────────────────────────────────────────
    private fun streakLine(records: List<WorkoutRecord>, now: Long): String? {
        val days = records.map { dayIndex(it.dateMs) }.toSortedSet().toList().sortedDescending()
        if (days.isEmpty()) return null

        val today = dayIndex(now)
        // Streak is only "current" if the most recent workout was today or yesterday.
        if (today - days.first() > 1) return null

        var streak = 1
        for (i in 1 until days.size) {
            if (days[i] == days[i - 1] - 1) streak++ else break
        }
        if (streak < 2) return null
        return "🔥 $streak-day workout streak — keep it alive!"
    }

    // ── 30-day frequency ─────────────────────────────────────────────────────
    private fun frequencyLine(records: List<WorkoutRecord>, now: Long): String? {
        val count = records.count { now - it.dateMs <= 30 * DAY_MS }
        if (count == 0) return null
        val plural = if (count == 1) "time" else "times"
        return "📅 You worked out $count $plural in the last 30 days."
    }

    // ── Personal best (longest single session) ───────────────────────────────
    private fun personalBestLine(records: List<WorkoutRecord>): String? {
        val best = records.maxByOrNull { it.totalDistM } ?: return null
        if (best.totalDistM <= 0) return null
        return "🏆 Longest session: ${Format.dist(best.totalDistM)}"
    }

    // ── helpers ──────────────────────────────────────────────────────────────
    private fun speedMps(distM: Double, durMs: Long): Double? =
        if (durMs > 0) distM / (durMs / 1000.0) else null

    /** Local-calendar day number, increments by 1 each midnight (DST-safe enough for streaks). */
    private fun dayIndex(ms: Long): Long {
        val cal = Calendar.getInstance().apply {
            timeInMillis = ms
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return cal.timeInMillis / DAY_MS
    }
}
