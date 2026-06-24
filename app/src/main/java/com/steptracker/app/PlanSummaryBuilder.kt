package com.steptracker.app

import kotlin.math.roundToInt

/**
 * Turns a [RunPlanCoach.Result] into Botty's short motivational summary and mid-plan
 * milestone — the default view instead of dumping every week at once.
 */
object PlanSummaryBuilder {

    data class Presentation(
        val oneLiner: String,
        val milestoneTeaser: String?,
        val milestoneWeek: Int,
        val totalWeeks: Int,
        val workoutsPerWeek: Int,
        val startKm: Double,
        val targetKm: Double
    )

    fun fromResult(
        result: RunPlanCoach.Result,
        profile: RunPlanCoach.Profile,
        goal: RunPlanCoach.Goal
    ): Presentation {
        val plan = result.plan
        if (plan.isEmpty()) {
            return Presentation(
                oneLiner = "No plan generated yet.",
                milestoneTeaser = null,
                milestoneWeek = 0,
                totalWeeks = 0,
                workoutsPerWeek = profile.daysPerWeekAvailable,
                startKm = 0.0,
                targetKm = goal.targetDistanceKm
            )
        }

        val totalWeeks = plan.size
        val week1 = plan.first()
        val workoutsPerWeek = profile.daysPerWeekAvailable.coerceAtLeast(1)
        val startKm = week1.longRunKm
        val targetKm = goal.targetDistanceKm

        val milestoneIdx = ((totalWeeks - 1) * 0.42).roundToInt().coerceIn(0, totalWeeks - 1)
        val milestoneWeek = plan[milestoneIdx]

        val oneLiner = buildString {
            append("$workoutsPerWeek workouts per week")
            append(" · start at ${fmt(startKm)} km in week 1")
            append(" · reach ${fmt(targetKm)} km by week $totalWeeks")
        }

        val milestoneTeaser = if (totalWeeks >= 3) {
            "By week ${milestoneWeek.week} you'll run ${fmt(milestoneWeek.longRunKm)} km — " +
                "real progress toward your goal."
        } else null

        return Presentation(
            oneLiner = oneLiner,
            milestoneTeaser = milestoneTeaser,
            milestoneWeek = milestoneWeek.week,
            totalWeeks = totalWeeks,
            workoutsPerWeek = workoutsPerWeek,
            startKm = startKm,
            targetKm = targetKm
        )
    }

    /** Full week-by-week text for the "View full plan" dialog only. */
    fun fullPlanText(result: RunPlanCoach.Result): String {
        val sb = StringBuilder()
        if (result.verdict == RunPlanCoach.Verdict.NOT_REALISTIC) {
            sb.append("Safe ${result.recommendedHorizonWeeks}-week plan to reach your distance:\n\n")
        }
        for (w in result.plan) {
            val tag = if (w.isCutback) "  (recovery)" else ""
            sb.append("Week ${w.week} · ${w.phase}$tag\n")
            sb.append("  ${fmt(w.longRunKm)} km long run · ~${fmt(w.weeklyVolumeKm)} km total · ${w.runningDays} days\n")
            sb.append("  ${w.focus}\n\n")
        }
        return sb.toString().trimEnd()
    }

    /** Week-by-week lines for an active [TrainingPlan] (no stored engine result). */
    fun fullPlanTextFromWorkouts(plan: TrainingPlan): String {
        val byWeek = plan.workouts.groupBy { it.weekNumber }.toSortedMap()
        val sb = StringBuilder()
        sb.append("${plan.goalLabel}\n\n")
        for ((week, workouts) in byWeek) {
            sb.append("Week $week\n")
            for (w in workouts.sortedBy { it.dateMs }) {
                val status = if (w.done) " ✓" else ""
                sb.append("  • ${w.dateLabel}: ${w.title}$status\n")
            }
            sb.append('\n')
        }
        return sb.toString().trimEnd()
    }

    fun fmt(v: Double): String = if (v >= 10) "%.0f".format(v) else "%.1f".format(v)
}
