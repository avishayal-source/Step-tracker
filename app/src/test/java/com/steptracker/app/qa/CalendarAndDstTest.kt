package com.steptracker.app.qa

import com.steptracker.app.ActivityType
import com.steptracker.app.PlanHygiene
import com.steptracker.app.PlannedWorkout
import com.steptracker.app.RunPlanCoach
import com.steptracker.app.ScheduleItem
import com.steptracker.app.TrainingPlan
import com.steptracker.app.TrainingPlanStore
import com.steptracker.app.WorkoutStatus
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.TimeZone

/**
 * Calendar-day logic in the user's real zone. Israel leaves DST on Sun 25 Oct 2026
 * (a 25-hour day) and enters it on Fri 27 Mar 2026 (a 23-hour day).
 */
class CalendarAndDstTest {

    @get:Rule val tz = TimeZoneRule("Asia/Jerusalem")

    private fun workout(dateMs: Long, id: Long = dateMs) = PlannedWorkout(
        id, id.toInt(), dateMs, 1, "Base", "W $id", 3.0, false,
        listOf(ScheduleItem(ActivityType.RUNNING, 20))
    )

    // ── Due / overdue boundaries ──────────────────────────────────────────────

    @Test
    fun workoutIsDueAllDay_andOverdueOneMsAfterMidnight() {
        val day = localMidnight(2026, 11, 2)
        val w = workout(day)
        assertFalse(w.isOverdue(day))
        assertFalse(w.isOverdue(localTime(2026, 11, 2, 23, 59)))
        assertTrue(w.isOverdue(localMidnight(2026, 11, 3) + 1))
        assertEquals("Today", w.dueLabel(localTime(2026, 11, 2, 23, 59)))
    }

    @Test
    fun daysLate_isWholeCalendarDays_acrossDstEnd() {
        val w = workout(localMidnight(2026, 10, 24))
        assertEquals(1, w.daysLate(localTime(2026, 10, 25, 23, 30)))
        assertEquals(3, w.daysLate(localTime(2026, 10, 27, 0, 10)))
    }

    @Test
    fun hygiene_graceWindowAndBacklogCap() {
        val mon = localMidnight(2026, 11, 2)
        val plan = TrainingPlan(0, "g", 3, 3, (0..3).map { workout(mon + it * 2 * DAY_MS, it.toLong()) })
        // Thu 5 Nov: Mon is 3 days late (kept), Wed is 1 day late (kept)
        val r1 = PlanHygiene.reconcile(plan, localTime(2026, 11, 5, 8))
        assertTrue(r1.retired.isEmpty())
        // Sun 8 Nov: Mon (6d) and Wed (4d) are past grace; Fri (2d) kept; Sun is today
        val r2 = PlanHygiene.reconcile(plan, localTime(2026, 11, 8, 8))
        assertEquals(listOf(0L, 1L), r2.retired.map { it.id })
        assertTrue(plan.overdueWorkouts(localTime(2026, 11, 8, 8)).size <= PlanHygiene.MAX_BACKLOG)
        assertTrue(PlanHygiene.retiredMessage(r2.retired)!!.contains("2 missed workouts"))
        assertNull(PlanHygiene.retiredMessage(emptyList()))
    }

    @Test
    fun nextDue_prefersTodayOverBacklog() {
        val today = localMidnight(2026, 11, 4)
        val plan = TrainingPlan(0, "g", 3, 3, listOf(workout(today - 2 * DAY_MS, 1), workout(today, 2)))
        assertEquals(2L, TrainingPlanStore.nextDue(plan, localTime(2026, 11, 4, 18))!!.id)
        plan.workouts[1].status = WorkoutStatus.DONE
        assertEquals(1L, TrainingPlanStore.nextDue(plan, localTime(2026, 11, 4, 19))!!.id)
    }

    // ── Defects: 24-hour arithmetic on calendar days ──────────────────────────

    /**
     * PlanScheduler dates workouts as start + n × 24 h. After the clocks go back that
     * lands at 23:00 the *previous* evening, so every workout after 25 Oct shows (and
     * reminds) one day early.
     */
    @Test
    fun defect_workoutsStayOnTheirCalendarDay_acrossDstEnd() {
        val start = localMidnight(2026, 10, 19)
        val (_, plan) = buildPlan(profile(), RunPlanCoach.Goal(5.0, 9), start)
        val wrong = plan.workouts.filter { w ->
            val expected = plusCalendarDays(start, ((w.dateMs - start + DAY_MS / 2) / DAY_MS).toInt())
            calendarFields(w.dateMs) != calendarFields(expected)
        }
        assertTrue("${wrong.size}/${plan.workouts.size} workouts on the wrong day, e.g. " +
            wrong.take(3).joinToString { "${it.title} → ${calendarFields(it.dateMs)}" }, wrong.isEmpty())
    }

    @Test
    fun defect_shiftPlanForward_keepsCalendarDays_acrossDstEnd() {
        val w = workout(localMidnight(2026, 10, 24))
        val shifted = TrainingPlan(0, "g", 3, 3, listOf(w)).shiftedForward(3)
        assertEquals(Triple(2026, 10, 27), calendarFields(shifted.workouts[0].dateMs))
    }

    /** Plans saved by v1.0.17 and earlier hold 23:00-the-evening-before dates after DST end; loading repairs them. */
    @Test
    fun storedPlanWithShiftedDates_isRepairedOnLoad() {
        val intended = localMidnight(2026, 10, 26)
        val legacy = workout(localMidnight(2026, 10, 19) + 7 * DAY_MS)   // = 25 Oct 23:00
        assertEquals(Triple(2026, 10, 25), calendarFields(legacy.dateMs))
        val loaded = TrainingPlan.fromJson(JSONObject(TrainingPlan(0, "g", 3, 3, listOf(legacy)).toJson().toString()))
        assertEquals(intended, loaded.workouts[0].dateMs)
    }

    /** displayWeek divides elapsed ms by 24 h: in the first hour after spring-forward it reports the previous week. */
    @Test
    fun defect_displayWeek_acrossDstStart() {
        val start = localMidnight(2026, 3, 23)
        val plan = TrainingPlan(0, "g", 3, 3, listOf(workout(start)), startDateMs = start, totalWeeks = 9)
        assertEquals(2, plan.displayWeek(localTime(2026, 3, 30, 0, 30)))
    }

    /**
     * Workout dates are absolute instants of Israeli midnight. The app reloads the plan
     * from storage on every screen, so after flying west the reloaded plan must still
     * show each workout on its calendar day.
     */
    @Test
    fun defect_travellerSeesWorkoutsOnTheSameCalendarDay() {
        val start = localMidnight(2026, 11, 2)
        val (_, plan) = buildPlan(profile(), RunPlanCoach.Goal(5.0, 9), start)
        val homeDays = plan.workouts.map { calendarFields(it.dateMs) }
        val stored = plan.toJson().toString()
        val saved = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
            val awayDays = TrainingPlan.fromJson(JSONObject(stored)).workouts.map { calendarFields(it.dateMs) }
            assertEquals("workout calendar days moved after changing time zone", homeDays, awayDays)
        } finally {
            TimeZone.setDefault(saved)
        }
    }
}
