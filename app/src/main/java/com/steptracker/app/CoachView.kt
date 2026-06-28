package com.steptracker.app

import android.app.DatePickerDialog
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.NumberPicker
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Botty — personal coach tab. Evaluates goals, shows a short plan summary with a
 * mid-plan milestone, and switches to active-plan mode after the user schedules.
 */
class CoachView(
    private val activity: AppCompatActivity,
    private val root: View
) {
    private val prefs = SecurePrefs.open(activity, "coach_prefs")
    private val planStore = TrainingPlanStore(activity)

    var onPlanActivated: (() -> Unit)? = null

    private var lastResult: RunPlanCoach.Result? = null
    private var lastProfile: RunPlanCoach.Profile? = null
    private var lastGoal: RunPlanCoach.Goal? = null
    private var lastPresentation: PlanSummaryBuilder.Presentation? = null

    // Intake
    private lateinit var intakePanel: View
    private lateinit var etDistance: EditText
    private lateinit var etWeeks: EditText
    private lateinit var etTime: EditText
    private lateinit var etAge: EditText
    private lateinit var sexGroup: RadioGroup
    private lateinit var sexM: RadioButton
    private lateinit var sexF: RadioButton
    private lateinit var etWeight: EditText
    private lateinit var etHeight: EditText
    private lateinit var etCurrentRun: EditText
    private lateinit var etDays: EditText
    private lateinit var btnEvaluate: MaterialButton

    // Proposal results
    private lateinit var results: View
    private lateinit var tvVerdict: TextView
    private lateinit var tvRationale: TextView
    private lateinit var tvCautions: TextView
    private lateinit var tvCoachSummary: TextView
    private lateinit var tvCoachMilestone: TextView
    private lateinit var btnCoachViewFullPlan: MaterialButton
    private lateinit var btnApprove: MaterialButton
    private lateinit var tvPlanStatus: TextView

    // Active plan
    private lateinit var activePanel: View
    private lateinit var tvBottyGoalLabel: TextView
    private lateinit var tvBottySummary: TextView
    private lateinit var tvBottyMilestone: TextView
    private lateinit var tvBottyWeekHeader: TextView
    private lateinit var tvBottyWeekWorkouts: TextView
    private lateinit var btnBottyGoSchedule: MaterialButton
    private lateinit var btnBottyViewFullPlan: MaterialButton
    private lateinit var btnBottyChangeGoal: MaterialButton

    fun setup() {
        intakePanel = root.findViewById(R.id.coachIntakePanel)
        etDistance   = root.findViewById(R.id.coachTargetDistance)
        etWeeks      = root.findViewById(R.id.coachHorizonWeeks)
        etTime       = root.findViewById(R.id.coachTargetTime)
        etAge        = root.findViewById(R.id.coachAge)
        sexGroup     = root.findViewById(R.id.coachSex)
        sexM         = root.findViewById(R.id.coachSexM)
        sexF         = root.findViewById(R.id.coachSexF)
        etWeight     = root.findViewById(R.id.coachWeight)
        etHeight     = root.findViewById(R.id.coachHeight)
        etCurrentRun = root.findViewById(R.id.coachCurrentRun)
        etDays       = root.findViewById(R.id.coachDaysPerWeek)
        btnEvaluate  = root.findViewById(R.id.coachBtnEvaluate)

        results     = root.findViewById(R.id.coachResults)
        tvVerdict   = root.findViewById(R.id.coachVerdict)
        tvRationale = root.findViewById(R.id.coachRationale)
        tvCautions  = root.findViewById(R.id.coachCautions)
        tvCoachSummary = root.findViewById(R.id.tvCoachSummary)
        tvCoachMilestone = root.findViewById(R.id.tvCoachMilestone)
        btnCoachViewFullPlan = root.findViewById(R.id.btnCoachViewFullPlan)
        btnApprove  = root.findViewById(R.id.coachBtnApprove)
        tvPlanStatus = root.findViewById(R.id.coachPlanStatus)

        activePanel = root.findViewById(R.id.coachActivePanel)
        tvBottyGoalLabel = root.findViewById(R.id.tvBottyGoalLabel)
        tvBottySummary = root.findViewById(R.id.tvBottySummary)
        tvBottyMilestone = root.findViewById(R.id.tvBottyMilestone)
        tvBottyWeekHeader = root.findViewById(R.id.tvBottyWeekHeader)
        tvBottyWeekWorkouts = root.findViewById(R.id.tvBottyWeekWorkouts)
        btnBottyGoSchedule = root.findViewById(R.id.btnBottyGoSchedule)
        btnBottyViewFullPlan = root.findViewById(R.id.btnBottyViewFullPlan)
        btnBottyChangeGoal = root.findViewById(R.id.btnBottyChangeGoal)

        restoreInputs()
        btnEvaluate.setOnClickListener { evaluate() }
        btnApprove.setOnClickListener { askApproval() }
        btnCoachViewFullPlan.setOnClickListener { showProposalFullPlan() }
        btnBottyGoSchedule.setOnClickListener { onPlanActivated?.invoke() }
        btnBottyViewFullPlan.setOnClickListener { showActiveFullPlan() }
        btnBottyChangeGoal.setOnClickListener { confirmChangeGoal() }

        refreshMode()
    }

    /** Call when the Botty tab is selected — refreshes the current program week. */
    fun onTabVisible() {
        refreshMode()
    }

    private fun refreshMode() {
        val plan = planStore.load()
        if (plan != null && plan.workouts.isNotEmpty()) {
            showActivePlan(plan)
        } else {
            showIntakeMode()
        }
    }

    private fun showIntakeMode() {
        activePanel.visibility = View.GONE
        intakePanel.visibility = View.VISIBLE
        tvPlanStatus.visibility = View.GONE
    }

    private fun showActivePlan(plan: TrainingPlan) {
        activePanel.visibility = View.VISIBLE
        intakePanel.visibility = View.GONE
        results.visibility = View.GONE
        tvPlanStatus.visibility = View.GONE

        val now = System.currentTimeMillis()
        val displayWeek = plan.displayWeek(now)
        val weekWorkouts = plan.workoutsForDisplayWeek(now)

        tvBottyGoalLabel.text = plan.goalLabel

        tvBottySummary.text = plan.summaryOneLiner.ifBlank {
            "${plan.goalLabel} · ${plan.workouts.count { !it.done }} workouts remaining"
        }

        if (plan.milestoneTeaser.isNotBlank()) {
            tvBottyMilestone.visibility = View.VISIBLE
            tvBottyMilestone.text = "🎯 ${plan.milestoneTeaser}"
        } else {
            tvBottyMilestone.visibility = View.GONE
        }

        tvBottyWeekHeader.text = if (plan.hasStarted(now)) {
            activity.getString(R.string.botty_this_week) + " — Program week $displayWeek"
        } else {
            activity.getString(R.string.botty_week_preview)
        }

        tvBottyWeekWorkouts.text = if (weekWorkouts.isEmpty()) {
            "No workouts this week — you're caught up or between phases."
        } else {
            weekWorkouts.joinToString("\n\n") { w ->
                val mark = if (w.done) "✅" else "○"
                "$mark ${w.dateLabel}\n   ${w.title}"
            }
        }

        val next = planStore.nextWorkout(plan, now)
        btnBottyGoSchedule.text = if (next != null && next.isSameDay(now))
            activity.getString(R.string.botty_go_schedule)
        else if (next != null)
            "Next: ${next.dateLabel}"
        else
            "All workouts complete 🎉"
    }

    private fun evaluate() {
        val distance = etDistance.text.toString().toDoubleOrNull()
        val weeks    = etWeeks.text.toString().toIntOrNull()
        val age      = etAge.text.toString().toIntOrNull()
        val weight   = etWeight.text.toString().toDoubleOrNull()
        val height   = etHeight.text.toString().toDoubleOrNull()
        val days     = etDays.text.toString().toIntOrNull()
        val currentRun = etCurrentRun.text.toString().toDoubleOrNull() ?: 0.0
        val targetTime = etTime.text.toString().toDoubleOrNull()

        val missing = when {
            distance == null || distance <= 0 -> "a target distance"
            weeks == null || weeks <= 0       -> "how many weeks you have"
            age == null || age !in 10..100    -> "a valid age (10–100)"
            weight == null || weight !in 25.0..300.0 -> "a valid weight (25–300 kg)"
            height == null || height !in 100.0..230.0 -> "a valid height (100–230 cm)"
            days == null || days !in 1..7     -> "training days per week (1–7)"
            else -> null
        }
        if (missing != null) {
            Toast.makeText(activity, "Please enter $missing", Toast.LENGTH_LONG).show()
            return
        }

        val sex = when (sexGroup.checkedRadioButtonId) {
            R.id.coachSexM -> "M"
            R.id.coachSexF -> "F"
            else -> "other"
        }

        val profile = RunPlanCoach.Profile(
            ageYears = age!!,
            sex = sex,
            weightKg = weight!!,
            heightCm = height!!,
            currentLongestRunKm = currentRun,
            daysPerWeekAvailable = days!!
        )
        val goal = RunPlanCoach.Goal(
            targetDistanceKm = distance!!,
            horizonWeeks = weeks!!,
            targetTimeMinutes = targetTime
        )

        saveInputs()
        lastProfile = profile
        lastGoal = goal
        val result = RunPlanCoach.evaluate(profile, goal)
        lastResult = result
        lastPresentation = PlanSummaryBuilder.fromResult(result, profile, goal)
        renderProposal(result, lastPresentation!!)
    }

    private fun renderProposal(r: RunPlanCoach.Result, presentation: PlanSummaryBuilder.Presentation) {
        tvVerdict.text = r.headline
        tvVerdict.setTextColor(
            when (r.verdict) {
                RunPlanCoach.Verdict.REALISTIC -> Color.parseColor("#14C28A")
                RunPlanCoach.Verdict.AMBITIOUS -> Color.parseColor("#FFC861")
                RunPlanCoach.Verdict.NOT_REALISTIC -> Color.parseColor("#FF6B5C")
            }
        )

        tvRationale.text = r.rationale.joinToString("\n\n") { "• $it" }

        if (r.cautions.isEmpty()) {
            tvCautions.visibility = View.GONE
        } else {
            tvCautions.visibility = View.VISIBLE
            tvCautions.text = "⚠️ Safety notes\n\n" + r.cautions.joinToString("\n\n") { "• $it" }
        }

        tvCoachSummary.text = presentation.oneLiner
        if (presentation.milestoneTeaser != null) {
            tvCoachMilestone.visibility = View.VISIBLE
            tvCoachMilestone.text = "🎯 ${presentation.milestoneTeaser}"
        } else {
            tvCoachMilestone.visibility = View.GONE
        }

        results.visibility = View.VISIBLE
    }

    private fun showProposalFullPlan() {
        val result = lastResult ?: return
        AlertDialog.Builder(activity)
            .setTitle("Full plan")
            .setMessage(PlanSummaryBuilder.fullPlanText(result))
            .setPositiveButton("OK", null)
            .show()
    }

    private fun showActiveFullPlan() {
        val plan = planStore.load() ?: return
        AlertDialog.Builder(activity)
            .setTitle("Full plan")
            .setMessage(PlanSummaryBuilder.fullPlanTextFromWorkouts(plan))
            .setPositiveButton("OK", null)
            .show()
    }

    private fun confirmChangeGoal() {
        AlertDialog.Builder(activity)
            .setTitle("Change goal?")
            .setMessage("This clears your current scheduled plan and reminders. Completed workout history is kept.")
            .setPositiveButton("Change goal") { _, _ -> clearActivePlan() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun clearActivePlan() {
        planStore.load()?.let { WorkoutReminderReceiver.cancelAll(activity, it) }
        planStore.clear()
        lastResult = null
        lastPresentation = null
        showIntakeMode()
    }

    private fun askApproval() {
        val result = lastResult ?: run {
            Toast.makeText(activity, "Evaluate a goal first", Toast.LENGTH_SHORT).show(); return
        }
        if (result.plan.isEmpty()) {
            Toast.makeText(activity, "No plan to schedule", Toast.LENGTH_SHORT).show(); return
        }
        // Recommended safety measure: require a physician-consultation
        // acknowledgement before any plan can be scheduled.
        AlertDialog.Builder(activity)
            .setTitle(R.string.plan_consult_title)
            .setMessage(R.string.health_disclaimer_full)
            .setPositiveButton(R.string.plan_consult_continue) { _, _ -> showScheduleDialog(result) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showScheduleDialog(result: RunPlanCoach.Result) {
        val dp = activity.resources.displayMetrics.density
        fun pad(v: Int) = (v * dp).toInt()

        val chosenDate = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val dateFmt = SimpleDateFormat("EEE, d MMM yyyy", Locale.getDefault())

        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad(20), pad(8), pad(20), pad(8))
        }

        container.addView(label("Start date"))
        val dateBtn = MaterialButton(activity).apply {
            text = dateFmt.format(chosenDate.time)
            setOnClickListener {
                DatePickerDialog(
                    activity,
                    { _, y, m, d ->
                        chosenDate.set(y, m, d, 0, 0, 0); chosenDate.set(Calendar.MILLISECOND, 0)
                        text = dateFmt.format(chosenDate.time)
                    },
                    chosenDate.get(Calendar.YEAR), chosenDate.get(Calendar.MONTH), chosenDate.get(Calendar.DAY_OF_MONTH)
                ).apply { datePicker.minDate = System.currentTimeMillis() }.show()
            }
        }
        container.addView(dateBtn)

        container.addView(label("Warmup walk before each workout (minutes)"))
        val warmupPicker = NumberPicker(activity).apply { minValue = 2; maxValue = 5; value = 3 }
        container.addView(warmupPicker)

        container.addView(label("Cooldown walk after each workout (minutes)"))
        val cooldownPicker = NumberPicker(activity).apply { minValue = 2; maxValue = 5; value = 3 }
        container.addView(cooldownPicker)

        AlertDialog.Builder(activity)
            .setTitle("Approve & schedule plan")
            .setMessage("Botty will add dated workouts to your Schedule tab and remind you the evening before each one.")
            .setView(container)
            .setPositiveButton("Schedule it") { _, _ ->
                activatePlan(result, chosenDate.timeInMillis, warmupPicker.value, cooldownPicker.value)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun label(text: String): TextView = TextView(activity).apply {
        this.text = text
        setTextColor(Color.parseColor("#51607A"))
        textSize = 12f
        gravity = Gravity.START
        val dp = activity.resources.displayMetrics.density
        setPadding(0, (10 * dp).toInt(), 0, (2 * dp).toInt())
    }

    private fun activatePlan(result: RunPlanCoach.Result, startMidnightMs: Long, warmupMin: Int, cooldownMin: Int) {
        val profile = lastProfile ?: return
        val goal = lastGoal ?: return
        val presentation = lastPresentation
            ?: PlanSummaryBuilder.fromResult(result, profile, goal)

        planStore.load()?.let { WorkoutReminderReceiver.cancelAll(activity, it) }

        val plan = PlanScheduler.generate(
            result, profile, goal, startMidnightMs, warmupMin, cooldownMin, presentation
        )
        planStore.save(plan)
        WorkoutReminderReceiver.scheduleAll(activity, plan)

        refreshMode()

        val first = plan.workouts.firstOrNull()
        AlertDialog.Builder(activity)
            .setTitle("✅ Plan scheduled")
            .setMessage(
                "${plan.workouts.size} workouts over ${presentation.totalWeeks} weeks.\n\n" +
                presentation.oneLiner + "\n\n" +
                (first?.let { "First workout: ${it.title} on ${it.dateLabel}.\n\n" } ?: "") +
                "Botty will remind you at 6 PM the day before each session."
            )
            .setPositiveButton("Go to Schedule") { _, _ -> onPlanActivated?.invoke() }
            .setNegativeButton("OK", null)
            .show()
    }

    private fun saveInputs() {
        prefs.edit()
            .putString("distance", etDistance.text.toString())
            .putString("weeks", etWeeks.text.toString())
            .putString("time", etTime.text.toString())
            .putString("age", etAge.text.toString())
            .putString("sex", if (sexF.isChecked) "F" else "M")
            .putString("weight", etWeight.text.toString())
            .putString("height", etHeight.text.toString())
            .putString("currentRun", etCurrentRun.text.toString())
            .putString("days", etDays.text.toString())
            .apply()
    }

    private fun restoreInputs() {
        if (!prefs.contains("age")) return
        etDistance.setText(prefs.getString("distance", ""))
        etWeeks.setText(prefs.getString("weeks", ""))
        etTime.setText(prefs.getString("time", ""))
        etAge.setText(prefs.getString("age", ""))
        etWeight.setText(prefs.getString("weight", ""))
        etHeight.setText(prefs.getString("height", ""))
        etCurrentRun.setText(prefs.getString("currentRun", ""))
        etDays.setText(prefs.getString("days", ""))
        if (prefs.getString("sex", "M") == "F") sexF.isChecked = true else sexM.isChecked = true
    }
}
