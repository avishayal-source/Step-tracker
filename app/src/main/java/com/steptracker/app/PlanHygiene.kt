package com.steptracker.app

/**
 * Keeps the overdue backlog honest.
 *
 * A missed workout stays pending so the user can still do it, but a backlog that grows
 * forever would push people into "catch-up" double sessions — the exact injury risk
 * [RunPlanCoach] is built to avoid. So two rules retire stale work:
 *
 *  1. Grace window: a workout more than [GRACE_DAYS] days late is retired as skipped.
 *  2. Backlog cap: at most [MAX_BACKLOG] overdue workouts are carried; the oldest
 *     beyond that are retired.
 *
 * Retiring is never silent — [reconcile] reports what it dropped so Botty can say so.
 */
object PlanHygiene {

    /** A workout stays doable for this many days past its scheduled day. */
    const val GRACE_DAYS = 3

    /** How many overdue workouts may be carried at once. */
    const val MAX_BACKLOG = 2

    data class Result(
        val plan: TrainingPlan,
        val retired: List<PlannedWorkout>
    ) {
        val changed: Boolean get() = retired.isNotEmpty()
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

        return Result(plan, retired)
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
}
