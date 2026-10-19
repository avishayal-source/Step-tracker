package com.steptracker.app.qa

import com.steptracker.app.ActivityType
import com.steptracker.app.CaloriesCalculator
import com.steptracker.app.PlanHygiene
import com.steptracker.app.RunPlanCoach
import com.steptracker.app.TrainingPlan
import com.steptracker.app.TrainingPlanStore
import com.steptracker.app.WorkoutInsights
import com.steptracker.app.WorkoutRecord
import com.steptracker.app.WorkoutStatus
import org.junit.AfterClass
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Calendar
import java.util.TimeZone
import kotlin.random.Random

/**
 * Virtual users living through a Botty plan on a virtual calendar, one day at a time.
 *
 * Each day the simulator does what the app does when opened (reconcile the backlog,
 * pick the next due workout), lets the persona decide whether to train, and records the
 * session into History with calories and insights. App-level invariants are asserted
 * every simulated day; a per-persona report is written to build/qa-reports.
 */
class VirtualUsersTimelineTest {

    data class Persona(
        val name: String,
        val zone: String,
        val start: Triple<Int, Int, Int>,
        val profile: RunPlanCoach.Profile,
        val goal: RunPlanCoach.Goal,
        /** Probability of training on a day something is due. */
        val adherence: Double,
        /** Local hour the user opens the app. */
        val hour: Int,
        /** Inclusive day ranges (from plan start) when the user does nothing at all. */
        val breaks: List<IntRange> = emptyList(),
        /** Uses "shift plan to today" whenever something is overdue. */
        val realigns: Boolean = false,
        /** Clears every overdue session the same day (stacking). */
        val catchUpEagerly: Boolean = false,
        /** Never comes back after this day. */
        val quitsOnDay: Int? = null,
        /** Fraction of a session actually completed before stopping early. */
        val completionFraction: Double = 1.0,
        val seed: Int
    )

    data class Report(
        val persona: Persona,
        val totalWorkouts: Int,
        var done: Int = 0,
        var skippedByHygiene: Int = 0,
        var maxBacklog: Int = 0,
        var stackedDays: Int = 0,
        var realignments: Int = 0,
        var offMidnightWorkouts: Int = 0,
        var simulatedDays: Int = 0,
        var pendingAtEnd: Int = 0,
        var totalKcal: Int = 0,
        var history: Int = 0,
        val violations: MutableList<String> = mutableListOf()
    )

    private val personas = listOf(
        Persona("Couch beginner, disciplined", "Asia/Jerusalem", Triple(2026, 10, 19),
            profile(age = 32, longestRunKm = 0.0, days = 3), RunPlanCoach.Goal(5.0, 9),
            adherence = 0.95, hour = 7, seed = 1),
        Persona("Obese 66-year-old, cautious", "Asia/Jerusalem", Triple(2026, 10, 19),
            profile(age = 66, weightKg = 112.0, heightCm = 168.0, longestRunKm = 0.0, days = 3), RunPlanCoach.Goal(5.0, 12),
            adherence = 0.6, hour = 17, completionFraction = 0.7, seed = 2),
        Persona("Intermediate 10K → half", "Asia/Jerusalem", Triple(2026, 10, 19),
            profile(age = 41, longestRunKm = 10.0, days = 4), RunPlanCoach.Goal(21.1, 14),
            adherence = 0.9, hour = 6, seed = 3),
        Persona("Ghost: 3-week vacation mid-plan", "Asia/Jerusalem", Triple(2026, 10, 19),
            profile(age = 28, longestRunKm = 0.5, days = 3), RunPlanCoach.Goal(5.0, 9),
            adherence = 0.85, hour = 20, breaks = listOf(14..35), seed = 4),
        Persona("Realigner: always shifts plan", "Asia/Jerusalem", Triple(2026, 10, 19),
            profile(age = 37, longestRunKm = 2.0, days = 3), RunPlanCoach.Goal(10.0, 12),
            adherence = 0.55, hour = 21, realigns = true, seed = 5),
        Persona("Stacker: makes up every miss", "Asia/Jerusalem", Triple(2026, 10, 19),
            profile(age = 24, longestRunKm = 3.0, days = 4), RunPlanCoach.Goal(10.0, 10),
            adherence = 0.5, hour = 23, catchUpEagerly = true, seed = 6),
        Persona("Quitter after week 2", "Asia/Jerusalem", Triple(2026, 10, 19),
            profile(age = 45, longestRunKm = 0.0, days = 3), RunPlanCoach.Goal(5.0, 9),
            adherence = 0.9, hour = 8, quitsOnDay = 14, seed = 7),
        Persona("New Yorker across DST end", "America/New_York", Triple(2026, 10, 26),
            profile(age = 30, longestRunKm = 5.0, days = 5), RunPlanCoach.Goal(10.0, 8),
            adherence = 0.8, hour = 0, seed = 8),
        Persona("Spring starter across DST start", "Asia/Jerusalem", Triple(2027, 3, 22),
            profile(age = 52, longestRunKm = 0.0, days = 2), RunPlanCoach.Goal(10.0, 20),
            adherence = 0.75, hour = 12, seed = 9),
        Persona("Night owl at 23:59", "Asia/Jerusalem", Triple(2026, 12, 28),
            profile(age = 19, longestRunKm = 1.5, days = 6), RunPlanCoach.Goal(10.0, 8),
            adherence = 0.7, hour = 23, seed = 10)
    )

    @Test
    fun virtualUsers_liveThroughTheirPlans() {
        val reports = personas.map { simulate(it) }
        writeReport(reports)
        val all = reports.flatMap { r -> r.violations.map { "[${r.persona.name}] $it" } }
        assertTrue("${all.size} invariant violations, first 30:\n" + all.take(30).joinToString("\n"), all.isEmpty())
    }

    @Test
    fun disciplinedUsers_finishMostOfTheirPlan() {
        for (p in personas.filter { it.adherence >= 0.9 && it.quitsOnDay == null && it.breaks.isEmpty() }) {
            val r = simulate(p)
            assertTrue("${p.name}: ${r.done}/${r.totalWorkouts}", r.done >= r.totalWorkouts * 0.8)
        }
    }

    private fun simulate(p: Persona): Report {
        val saved = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone(p.zone))
        try {
            return runPersona(p)
        } finally {
            TimeZone.setDefault(saved)
        }
    }

    private fun runPersona(p: Persona): Report {
        val rnd = Random(p.seed)
        val start = localMidnight(p.start.first, p.start.second, p.start.third)
        var plan: TrainingPlan = buildPlan(p.profile, p.goal, start).second
        val r = Report(p, plan.workouts.size)
        val history = mutableListOf<WorkoutRecord>()
        r.offMidnightWorkouts = plan.workouts.count { hourOf(it.dateMs) != 0 }

        val maxDays = 7 * plan.totalWeeks + 120
        var day = -1
        while (day < maxDays) {
            day++
            r.simulatedDays = day
            val minute = if (p.hour == 23) 59 else rnd.nextInt(0, 50)
            val now = Calendar.getInstance().apply {
                timeInMillis = plusCalendarDays(start, day)
                set(Calendar.HOUR_OF_DAY, p.hour); set(Calendar.MINUTE, minute)
            }.timeInMillis
            val quit = p.quitsOnDay != null && day >= p.quitsOnDay
            val onBreak = p.breaks.any { day in it }

            // What the app does on open.
            val rec = PlanHygiene.reconcile(plan, now)
            plan = rec.plan
            r.skippedByHygiene += rec.retired.size
            if (rec.changed && PlanHygiene.retiredMessage(rec.retired).isNullOrBlank())
                r.violations += "day $day: retired workouts without a message"

            val overdue = plan.overdueWorkouts(now)
            r.maxBacklog = maxOf(r.maxBacklog, overdue.size)
            if (overdue.size > PlanHygiene.MAX_BACKLOG) r.violations += "day $day: backlog ${overdue.size}"
            overdue.firstOrNull { it.daysLate(now) > PlanHygiene.GRACE_DAYS }?.let {
                r.violations += "day $day: ${it.title} offered ${it.daysLate(now)} days late"
            }
            val wk = plan.displayWeek(now)
            val maxWeek = plan.workouts.maxOf { it.weekNumber }
            if (wk !in 1..maxOf(plan.totalWeeks, maxWeek)) r.violations += "day $day: displayWeek $wk"

            if (plan.workouts.none { it.pending }) break
            if (quit || onBreak) continue

            if (p.realigns && overdue.isNotEmpty()) {
                val oldest = overdue.first()
                val shifted = plan.shiftedForward(oldest.daysLate(now))
                r.realignments++
                val moved = shifted.findWorkout(oldest.id)!!
                if (!moved.isSameDay(now)) r.violations += "day $day: realigned workout not on today (${calendarFields(moved.dateMs)})"
                val pendingDays = shifted.workouts.filter { it.pending }.map { calendarFields(it.dateMs) }
                if (pendingDays.size != pendingDays.toSet().size) r.violations += "day $day: realign put two workouts on one day"
                plan = shifted
            }

            var sessionsToday = 0
            while (true) {
                val due = TrainingPlanStore.nextDue(plan, now) ?: break
                if (!due.pending) { r.violations += "day $day: nextDue returned ${due.status}"; break }
                if (due.dateMs > plusCalendarDays(start, day + 1)) { r.violations += "day $day: nextDue is in the future"; break }
                if (rnd.nextDouble() > p.adherence) break

                due.status = WorkoutStatus.DONE
                due.completedAtMs = now
                sessionsToday++
                r.done++
                history += sessionRecord(due.items, p, now + sessionsToday * 3_600_000L, rnd)
                    .also { r.totalKcal += it.caloriesKcal }
                if (history.last().caloriesKcal !in 1..2500) r.violations += "day $day: ${history.last().caloriesKcal} kcal"
                if (!p.catchUpEagerly) break
            }
            if (sessionsToday > 1) r.stackedDays++

            val lines = runCatching { WorkoutInsights.compute(history, now) }
            if (lines.isFailure) r.violations += "day $day: insights threw ${lines.exceptionOrNull()}"
        }
        r.pendingAtEnd = plan.workouts.count { it.pending }
        r.history = history.size
        if (r.pendingAtEnd > 0) r.violations += "plan still has ${r.pendingAtEnd} pending workouts after $maxDays days (zombie plan)"
        if (r.pendingAtEnd == 0 && r.done + r.skippedByHygiene != r.totalWorkouts)
            r.violations += "accounting: done ${r.done} + retired ${r.skippedByHygiene} ≠ ${r.totalWorkouts}"
        return r
    }

    private fun sessionRecord(
        items: List<com.steptracker.app.ScheduleItem>, p: Persona, dateMs: Long, rnd: Random
    ): WorkoutRecord {
        val frac = p.completionFraction
        val walkMin = items.filter { it.type == ActivityType.WALKING }.sumOf { it.durationMinutes } * frac
        val runMin = items.filter { it.type != ActivityType.WALKING }.sumOf { it.durationMinutes } * frac
        val beginner = p.profile.currentLongestRunKm < 1.0
        val runPace = (if (beginner) 8.5 else 6.5) + rnd.nextDouble(-0.5, 0.5)   // min/km
        val walkPace = 11.0 + rnd.nextDouble(-1.0, 1.0)
        val walkM = walkMin / walkPace * 1000
        val runM = runMin / runPace * 1000
        val walkMs = (walkMin * 60_000).toLong()
        val runMs = (runMin * 60_000).toLong()
        return WorkoutRecord(
            id = dateMs, dateMs = dateMs,
            walkSteps = (walkMin * 110).toInt(), runSteps = (runMin * 165).toInt(),
            walkDistM = walkM, runDistM = runM, walkDurationMs = walkMs, runDurationMs = runMs,
            caloriesKcal = CaloriesCalculator.estimateKcal(p.profile.weightKg, walkMs, runMs, walkM, runM)
        )
    }

    private fun hourOf(ms: Long) = Calendar.getInstance().apply { timeInMillis = ms }.get(Calendar.HOUR_OF_DAY)

    private fun writeReport(reports: List<Report>) {
        val sb = StringBuilder()
        sb.appendLine("Y Walk — virtual users timeline report")
        sb.appendLine("=".repeat(118))
        sb.appendLine("%-34s %5s %5s %7s %6s %7s %7s %7s %8s %6s %6s".format(
            "persona", "plan", "done", "retired", "maxBL", "stacked", "realign", "offMid", "kcal", "days", "viol"))
        for (r in reports) {
            sb.appendLine("%-34s %5d %5d %7d %6d %7d %7d %7d %8d %6d %6d".format(
                r.persona.name.take(34), r.totalWorkouts, r.done, r.skippedByHygiene, r.maxBacklog,
                r.stackedDays, r.realignments, r.offMidnightWorkouts, r.totalKcal, r.simulatedDays, r.violations.size))
        }
        sb.appendLine()
        sb.appendLine("offMid = workouts whose stored date is not local midnight (DST arithmetic defect).")
        sb.appendLine("stacked = days the user completed more than one session (catch-up stacking).")
        for (r in reports.filter { it.violations.isNotEmpty() }) {
            sb.appendLine()
            sb.appendLine("[${r.persona.name}]")
            r.violations.take(15).forEach { sb.appendLine("  - $it") }
        }
        lastReport = sb.toString()
        println(lastReport)
    }

    companion object {
        private var lastReport: String? = null

        @JvmStatic @AfterClass
        fun saveReport() {
            val text = lastReport ?: return
            File("build/qa-reports").apply { mkdirs() }.resolve("virtual_users.txt").writeText(text)
        }
    }
}
