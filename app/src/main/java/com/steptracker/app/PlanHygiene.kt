package com.steptracker.app

import java.util.Calendar
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Keeps the plan in step with what the user actually does.
 *
 * A missed workout stays pending so the user can still do it, but a backlog that grows
 * forever would push people into "catch-up" double sessions — the exact injury risk
 * [RunPlanCoach] is built to avoid. So two rules retire stale work:
 *
 *  1. Grace window: a workout more than [GRACE_DAYS] days late is retired as skipped.
 *  2. Backlog cap: at most [MAX_BACKLOG] overdue workouts are carried; the oldest
 *     beyond that are retired.
 *
 * The plan itself follows the calendar, so two more rules keep it from outrunning the user:
 *
 *  3. Readiness: a session due now or tomorrow is shortened to at most
 *     [GROWTH_FACTOR] × (or [GROWTH_KM] more than) the longest recent completed session.
 *     Older sessions count for less, so coming back from a break starts easier.
 *  4. Goal readiness: when the goal run comes up and fewer than half the earlier sessions
 *     were done, an extra build week is inserted before it (up to [MAX_EXTRA_WEEKS]).
 *
 * None of this is silent — [reconcile] reports what changed so Botty can say so.
 */
object PlanHygiene {

    /** A workout stays doable for this many days past its scheduled day. */
    const val GRACE_DAYS = 3

    /** How many overdue workouts may be carried at once. */
    const val MAX_BACKLOG = 2

    const val GROWTH_FACTOR = 1.3
    const val GROWTH_KM = 0.5
    /** A completed session counts fully for this long, then fades to half at twice this. */
    const val FRESH_DAYS = 14
    const val MAX_EXTRA_WEEKS = 4
    private const val GOAL_READY_SHARE = 0.5

    data class Result(
        val plan: TrainingPlan,
        val retired: List<PlannedWorkout>,
        val eased: List<PlannedWorkout> = emptyList(),
        val addedBuildWeek: Boolean = false
    ) {
        val changed: Boolean get() = retired.isNotEmpty() || eased.isNotEmpty() || addedBuildWeek
    }

    fun reconcile(plan: TrainingPlan, now: Long = System.currentTimeMillis()): Result {
        val retired = mutableListOf<PlannedWorkout>()

        for (w in plan.overdueWorkouts(now)) {
            if (w.daysLate(now) > GRACE_DAYS) {
                w.status = WorkoutStatus.SKIPPED
                retired += w
            }
        }

        // Oldest-first, so trimming keeps the most recent misses.
        val stillOverdue = plan.overdueWorkouts(now)
        val excess = stillOverdue.size - MAX_BACKLOG
        if (excess > 0) {
            for (w in stillOverdue.take(excess)) {
                w.status = WorkoutStatus.SKIPPED
                retired += w
            }
        }

        val extended = withBuildWeekIfNotReady(plan, now)
        val (eased, easedPlan) = easedToReadiness(extended ?: plan, now)
        return Result(easedPlan, retired, eased, addedBuildWeek = extended != null)
    }

    /** What the user can currently handle, from completed sessions; null before the first one. */
    fun capacityKm(plan: TrainingPlan, now: Long): Double? {
        val today = PlannedWorkout.dayIndex(now)
        return plan.workouts.filter { it.done }.maxOfOrNull { w ->
            val at = if (w.completedAtMs > 0L) w.completedAtMs else w.dateMs
            val age = (today - PlannedWorkout.dayIndex(at)).toInt().coerceAtLeast(0)
            val freshness = if (age <= FRESH_DAYS) 1.0
                else (1.0 - (age - FRESH_DAYS) / (2.0 * FRESH_DAYS)).coerceAtLeast(0.5)
            w.distanceKm * freshness
        }
    }

    fun maxNextKm(capacityKm: Double): Double = max(capacityKm * GROWTH_FACTOR, capacityKm + GROWTH_KM)

    private fun easedToReadiness(plan: TrainingPlan, now: Long): Pair<List<PlannedWorkout>, TrainingPlan> {
        val capacity = capacityKm(plan, now) ?: return emptyList<PlannedWorkout>() to plan
        val limit = floor(maxNextKm(capacity) * 10.0) / 10.0
        val eased = mutableListOf<PlannedWorkout>()
        val workouts = plan.workouts.map { w ->
            if (w.pending && w.daysLate(now) >= -1 && w.distanceKm > limit + 0.05) {
                eased(w, limit).also { eased += it }
            } else w
        }
        return if (eased.isEmpty()) eased to plan else eased to plan.copy(workouts = workouts)
    }

    private fun eased(w: PlannedWorkout, km: Double): PlannedWorkout {
        val ratio = km / w.distanceKm
        val title = w.title.replaceFirst(KM_IN_TITLE, "${fmt(km)} km")
            .let { if (it.endsWith(EASED_TAG)) it else it + EASED_TAG }
        return w.copy(distanceKm = km, title = title, items = scaledMain(w.items, ratio))
    }

    /** Shortens the work between warmup and cooldown: one block gets shorter, intervals lose reps. */
    private fun scaledMain(items: List<ScheduleItem>, ratio: Double): List<ScheduleItem> {
        val first = items.indexOfFirst { it.type != ActivityType.WALKING }
        val last = items.indexOfLast { it.type != ActivityType.WALKING }
        if (first < 0) return items
        val main = items.subList(first, last + 1)
        val newMain = if (main.size == 1) {
            listOf(main[0].copy(durationMinutes = max(1, (main[0].durationMinutes * ratio).roundToInt())))
        } else {
            main.take(max(2, ceil(main.size * ratio).toInt())).dropLastWhile { it.type == ActivityType.WALKING }
        }
        return items.subList(0, first) + newMain + items.subList(last + 1, items.size)
    }

    /**
     * Inserts a repeat of the week before the goal, starting today, and moves the goal week
     * back by a week — when the goal run is due within a day and the user did fewer than half
     * of the sessions before it. Returns null when nothing changes.
     */
    private fun withBuildWeekIfNotReady(plan: TrainingPlan, now: Long): TrainingPlan? {
        if (plan.extraWeeks >= MAX_EXTRA_WEEKS) return null
        val goal = plan.workouts.filter { it.isLongRun }.maxByOrNull { it.dateMs } ?: return null
        if (!goal.pending || goal.daysLate(now) < -1) return null
        val earlier = plan.workouts.filter { it.weekNumber < goal.weekNumber }
        if (earlier.isEmpty() || earlier.count { it.done } >= earlier.size * GOAL_READY_SHARE) return null
        val template = plan.workouts.filter { it.weekNumber == goal.weekNumber - 1 }.sortedBy { it.dateMs }
        if (template.isEmpty()) return null

        val goalWeek = goal.weekNumber
        val today = startOfDay(now)
        val templateStart = PlannedWorkout.dayIndex(template.first().dateMs)
        var nextId = plan.workouts.maxOf { it.id } + 1
        var nextRc = plan.workouts.maxOf { it.requestCode } + 1
        val extra = template.map { t ->
            val offset = (PlannedWorkout.dayIndex(t.dateMs) - templateStart).toInt()
            t.copy(
                id = nextId++,
                requestCode = nextRc++,
                dateMs = PlannedWorkout.plusCalendarDays(today, offset),
                weekNumber = goalWeek,
                title = t.title.removeSuffix(EASED_TAG).replaceFirst(WEEK_IN_TITLE, "Week $goalWeek"),
                status = WorkoutStatus.PENDING,
                completedAtMs = 0L
            )
        }

        val goalWeekPending = plan.workouts.filter { it.weekNumber == goalWeek && it.pending }
        val shift = (PlannedWorkout.dayIndex(today) + 7 - PlannedWorkout.dayIndex(goalWeekPending.minOf { it.dateMs })).toInt()
        val moved = plan.workouts.map { w ->
            if (w.weekNumber == goalWeek && w.pending) w.copy(
                dateMs = PlannedWorkout.plusCalendarDays(w.dateMs, shift.coerceAtLeast(0)),
                weekNumber = goalWeek + 1,
                title = w.title.replaceFirst(WEEK_IN_TITLE, "Week ${goalWeek + 1}")
            ) else w
        }
        val weeks = if (plan.totalWeeks > 0) plan.totalWeeks else goalWeek
        return plan.copy(
            workouts = (moved + extra).sortedBy { it.dateMs },
            totalWeeks = weeks + 1,
            extraWeeks = plan.extraWeeks + 1
        ).withCorrectedGoalLabel()
    }

    /** "Botty dropped 2 missed runs…" — user-facing note, or null when nothing was retired. */
    fun retiredMessage(retired: List<PlannedWorkout>): String? {
        if (retired.isEmpty()) return null
        val titles = retired.sortedBy { it.dateMs }.joinToString("\n") { "• ${it.dateLabel} — ${it.title}" }
        val n = retired.size
        return if (n == 1) {
            "Botty let one missed workout go so you don't stack sessions:\n\n$titles\n\n" +
                "Pick up the plan from your next session — no need to make it up."
        } else {
            "Botty let $n missed workouts go so you don't stack sessions:\n\n$titles\n\n" +
                "Pick up the plan from your next session — no need to make them up."
        }
    }

    /** Everything [reconcile] changed, as one note for Botty; null when nothing did. */
    fun changeMessage(result: Result): String? {
        val parts = mutableListOf<String>()
        retiredMessage(result.retired)?.let { parts += it }
        if (result.addedBuildWeek) {
            parts += "You've done fewer than half of the sessions so far, so Botty added an extra " +
                "build week before your goal run. Your goal moved back a week."
        }
        if (result.eased.isNotEmpty()) {
            val titles = result.eased.sortedBy { it.dateMs }.joinToString("\n") { "• ${it.title}" }
            parts += "Botty shortened your next session to match what you've run recently:\n\n$titles\n\n" +
                "The plan builds back up from here."
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString("\n\n")
    }

    private val KM_IN_TITLE = Regex("""\d+(\.\d+)? km""")
    private val WEEK_IN_TITLE = Regex("""^Week \d+""")
    private const val EASED_TAG = " (eased)"

    private fun fmt(v: Double): String = if (v >= 10) "%.0f".format(v) else "%.1f".format(v)

    private fun startOfDay(ms: Long): Long = Calendar.getInstance().apply {
        timeInMillis = ms
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis
}
