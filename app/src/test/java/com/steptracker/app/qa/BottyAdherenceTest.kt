package com.steptracker.app.qa

import com.steptracker.app.PlanHygiene
import com.steptracker.app.PlannedWorkout
import com.steptracker.app.RunPlanCoach
import com.steptracker.app.TrainingPlan
import com.steptracker.app.TrainingPlanStore
import com.steptracker.app.WorkoutStatus
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.Calendar
import kotlin.random.Random

/**
 * How Botty treats users who don't follow the plan: someone who trains only now and
 * then, and someone who gets injured and stops for a while.
 *
 * The simulator does what the app does on every open (PlanHygiene.reconcile, then the
 * Schedule tab's "today / oldest overdue, else tomorrow's preview") and what the Botty
 * overdue card offers ("shift plan to today" when something is overdue).
 */
class BottyAdherenceTest {

    @get:Rule val tz = TimeZoneRule("Asia/Jerusalem")

    data class Done(val day: Int, val w: PlannedWorkout)

    class Sim(p: RunPlanCoach.Profile, g: RunPlanCoach.Goal) {
        val start = localMidnight(2026, 11, 2)
        var plan: TrainingPlan = buildPlan(p, g, start).second
        val total = plan.workouts.size
        val done = mutableListOf<Done>()
        val messages = mutableListOf<Pair<Int, String>>()
        val log = StringBuilder()
        var maxBacklog = 0

        fun now(day: Int, hour: Int = 18): Long = Calendar.getInstance().apply {
            timeInMillis = plusCalendarDays(start, day); set(Calendar.HOUR_OF_DAY, hour)
        }.timeInMillis

        /** Opens the app; returns what the Schedule tab would load. */
        fun open(day: Int): PlannedWorkout? {
            val now = now(day)
            val r = PlanHygiene.reconcile(plan, now)
            plan = r.plan
            PlanHygiene.retiredMessage(r.retired)?.let { messages += day to it }
            maxBacklog = maxOf(maxBacklog, plan.overdueWorkouts(now).size)
            return TrainingPlanStore.nextDue(plan, now) ?: TrainingPlanStore.preview(plan, now, 1)
        }

        fun train(day: Int): PlannedWorkout? {
            val w = open(day) ?: run { log.appendLine("day %3d  wanted to train — nothing offered".format(day)); return null }
            w.status = WorkoutStatus.DONE
            w.completedAtMs = now(day)
            done += Done(day, w)
            log.appendLine("day %3d  did %-40s (scheduled day %d)".format(day, w.title, scheduledDay(w)))
            return w
        }

        fun canRealign(day: Int): Boolean =
            plan.overdueWorkouts(now(day)).firstOrNull()?.let { it.daysLate(now(day)) > 0 } == true

        fun realign(day: Int) {
            val oldest = plan.overdueWorkouts(now(day)).first()
            plan = plan.shiftedForward(oldest.daysLate(now(day)))
            log.appendLine("day %3d  shifted plan forward %d days".format(day, oldest.daysLate(now(day))))
        }

        fun scheduledDay(w: PlannedWorkout): Int = ((w.dateMs - start + DAY_MS / 2) / DAY_MS).toInt()
        val goalWorkout: PlannedWorkout get() = plan.workouts.maxBy { it.dateMs }
        val lastPlanDay: Int get() = scheduledDay(goalWorkout)
    }

    private fun occasional(seed: Int): Sim {
        val sim = Sim(profile(age = 34, longestRunKm = 0.0, days = 3), RunPlanCoach.Goal(5.0, 9))
        val rnd = Random(seed)
        val trainingDays = (0..12).map { wk -> wk * 7 + rnd.nextInt(0, 7) }.toSet()
        for (day in 0..sim.lastPlanDay + 14) if (day in trainingDays) sim.train(day) else sim.open(day)
        return sim
    }

    private fun injured(breakDays: IntRange, realignOnReturn: Boolean): Sim {
        val sim = Sim(profile(age = 38, longestRunKm = 3.0, days = 3), RunPlanCoach.Goal(10.0, 12))
        var day = 0
        while (day <= sim.lastPlanDay + 14) {
            when {
                day in breakDays -> Unit
                day == breakDays.last + 1 && realignOnReturn && sim.canRealign(day) -> { sim.realign(day); trainIfScheduled(sim, day) }
                else -> trainIfScheduled(sim, day)
            }
            day++
        }
        return sim
    }

    /** A disciplined user: trains whenever the app has a workout due today or overdue. */
    private fun trainIfScheduled(sim: Sim, day: Int) {
        val offered = sim.open(day)
        if (offered != null && sim.scheduledDay(offered) <= day) sim.train(day)
    }

    private fun report(name: String, sim: Sim) {
        val text = buildString {
            appendLine("── $name: done ${sim.done.size}/${sim.total}, max backlog ${sim.maxBacklog}")
            append(sim.log)
            sim.messages.forEach { (d, m) -> appendLine("day %3d  BOTTY: %s".format(d, m.lineSequence().first())) }
        }
        println(text)
        reports += text
    }

    // ── Occasional exerciser: one session a week on a 3-a-week plan ───────────

    @Test
    fun occasionalUser_backlogStaysBoundedAndRetirementsAreExplained() {
        val sim = occasional(1).also { report("occasional user (seed 1)", it) }
        assertTrue(sim.maxBacklog <= PlanHygiene.MAX_BACKLOG)
        val retired = sim.plan.workouts.count { it.status == WorkoutStatus.SKIPPED }
        assertTrue(retired > 0 && sim.messages.isNotEmpty())
        assertEquals(sim.plan.workouts.size, sim.done.size + retired + sim.plan.workouts.count { it.pending })
    }

    @Test
    fun occasionalUser_alwaysHasSomethingToDoOnATrainingDay() {
        val sim = occasional(2)
        assertTrue(sim.log.toString(), !sim.log.contains("nothing offered"))
    }

    /**
     * The plan keeps marching by calendar, so each time this user shows up they get the
     * session for *this* week — far beyond anything they've actually done.
     */
    @Test
    fun defect_occasionalUser_isNeverHandedABigLoadJump() {
        val jumps = mutableListOf<String>()
        for (seed in 1..5) {
            val d = occasional(seed).done
            d.zipWithNext().forEach { (prev, next) ->
                val base = d.filter { it.day < next.day && it.day >= next.day - 21 }.maxOfOrNull { it.w.distanceKm } ?: prev.w.distanceKm
                if (next.w.distanceKm > base * 1.3 && next.w.distanceKm - base > 0.5)
                    jumps += "seed $seed day ${next.day}: %.1f km after a best of %.1f km".format(next.w.distanceKm, base)
            }
        }
        assertTrue("${jumps.size} jumps > 30%:\n" + jumps.take(10).joinToString("\n"), jumps.isEmpty())
    }

    /**
     * With fewer than half the sessions done, the goal run waits for extra build weeks; only
     * after [PlanHygiene.MAX_EXTRA_WEEKS] of them is it offered (shortened by the readiness cap).
     */
    @Test
    fun occasionalUser_goalRunWaitsForExtraBuildWeeks() {
        val bad = (1..5).mapNotNull { seed ->
            val sim = occasional(seed).also { if (seed == 1) report("occasional user (seed 1), goal", it) }
            val goal = sim.done.firstOrNull { it.w.id == sim.goalWorkout.id } ?: return@mapNotNull null
            val before = sim.done.count { it.day < goal.day }
            val scheduledBefore = sim.plan.workouts.count { it.dateMs < sim.goalWorkout.dateMs }
            if (before < scheduledBefore / 2 && sim.plan.extraWeeks < PlanHygiene.MAX_EXTRA_WEEKS)
                "seed $seed: goal run offered after $before/$scheduledBefore sessions, ${sim.plan.extraWeeks} extra weeks" else null
        }
        assertTrue(bad.joinToString("\n"), bad.isEmpty())
    }

    // ── Injured user: 3 good weeks, then stops ────────────────────────────────

    @Test
    fun shortBreak_userCanShiftThePlanAndResumeWhereTheyLeft() {
        val sim = injured(21..25, realignOnReturn = true).also { report("5-day break, shifts plan", it) }
        val before = bestBefore(sim, 21)
        val after = sim.done.first { it.day > 25 }
        assertTrue("first session back ${after.w.title} vs best %.1f km before".format(before),
            after.w.distanceKm <= before * 1.1 + 0.1)
        assertTrue(sim.done.size >= sim.total - 2)
    }

    @Test
    fun longBreak_retiredSessionsAreExplainedOnReturn() {
        val sim = injured(21..41, realignOnReturn = false).also { report("3-week injury, passive return", it) }
        val msg = sim.messages.firstOrNull { it.first >= 42 }
        assertNotNull("no explanation when coming back", msg)
        assertTrue(msg!!.second.contains("missed workouts"))
    }

    /**
     * After 3 weeks off the plan resumes at week 7 as if nothing happened — the first
     * session back is harder than anything done before the injury.
     */
    @Test
    fun defect_afterInjury_resumesNoHarderThanBefore() {
        val sim = injured(21..41, realignOnReturn = false)
        val before = bestBefore(sim, 21)
        val after = sim.done.first { it.day > 41 }
        assertTrue("back from 3 weeks off: ${after.w.title}, best before injury %.1f km".format(before),
            after.w.distanceKm <= before * 1.1)
    }

    /** Tapping "Shift plan to today" on return rewinds to the first missed week. */
    @Test
    fun afterInjury_shiftingThePlan_firstWeekBackIsNoHeavierThanLastWeekBefore() {
        val sim = injured(21..41, realignOnReturn = true).also { report("3-week injury, shifts plan", it) }
        fun load(days: IntRange) = sim.done.filter { it.day in days }.sumOf { it.w.distanceKm }
        val before = load(14..20)
        val after = load(42..48)
        assertTrue("week before injury %.1f km, first week back %.1f km".format(before, after), after <= before)
    }

    /** Longest session in the two weeks before [day]. */
    private fun bestBefore(sim: Sim, day: Int): Double =
        sim.done.filter { it.day in (day - 14) until day }.maxOf { it.w.distanceKm }

    /** PlanHygiene keeps up to [PlanHygiene.MAX_BACKLOG] overdue sessions, so "Shift plan" is still offered. */
    @Test
    fun afterLongBreak_shiftOptionIsStillOffered() {
        val sim = Sim(profile(age = 38, longestRunKm = 3.0, days = 3), RunPlanCoach.Goal(10.0, 12))
        for (day in 0..20) trainIfScheduled(sim, day)
        sim.open(42)
        assertTrue("on return after 21 days the shift option is unavailable", sim.canRealign(42))
    }

    companion object {
        private val reports = mutableListOf<String>()

        @JvmStatic @AfterClass
        fun save() {
            if (reports.isEmpty()) return
            File("build/qa-reports").apply { mkdirs() }.resolve("botty_adherence.txt").writeText(reports.joinToString("\n"))
        }
    }
}
