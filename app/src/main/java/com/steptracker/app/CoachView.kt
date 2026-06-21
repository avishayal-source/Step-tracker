package com.steptracker.app

import android.app.DatePickerDialog
import android.content.Context
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
import java.util.Date
import java.util.Locale

/**
 * Controller for the "Goal" tab (the AI Goal Coach). Collects the user's goal and
 * profile, runs the on-device [RunPlanCoach] engine, and renders the verdict,
 * rationale, safety cautions and week-by-week plan. Inputs are persisted so the
 * user doesn't have to re-enter them each visit.
 */
class CoachView(
    private val activity: AppCompatActivity,
    private val root: View
) {
    private val prefs = SecurePrefs.open(activity, "coach_prefs")
    private val planStore = TrainingPlanStore(activity)

    /** Invoked after the user approves a plan, so the host can surface today's workout. */
    var onPlanActivated: (() -> Unit)? = null

    // Last evaluation, kept so "Approve" can build a dated plan from it.
    private var lastResult: RunPlanCoach.Result? = null
    private var lastProfile: RunPlanCoach.Profile? = null
    private var lastGoal: RunPlanCoach.Goal? = null

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

    private lateinit var results: View
    private lateinit var tvVerdict: TextView
    private lateinit var tvRationale: TextView
    private lateinit var tvCautions: TextView
    private lateinit var tvPlan: TextView
    private lateinit var btnApprove: MaterialButton
    private lateinit var tvPlanStatus: TextView

    fun setup() {
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
        tvPlan      = root.findViewById(R.id.coachPlan)
        btnApprove  = root.findViewById(R.id.coachBtnApprove)
        tvPlanStatus = root.findViewById(R.id.coachPlanStatus)

        restoreInputs()
        showActivePlanStatus()
        btnEvaluate.setOnClickListener { evaluate() }
        btnApprove.setOnClickListener { askApproval() }
    }

    /** If a plan is already active, show its next-workout status at the top of results. */
    private fun showActivePlanStatus() {
        val plan = planStore.load() ?: return
        val next = planStore.nextWorkout(plan)
        tvPlanStatus.visibility = View.VISIBLE
        tvPlanStatus.text = if (next != null)
            "📅 Active plan: ${plan.goalLabel}\nNext: ${next.title} on ${next.dateLabel}"
        else
            "📅 Active plan: ${plan.goalLabel}\nAll scheduled workouts are complete — great job!"
    }

    private fun evaluate() {
        val distance = etDistance.text.toString().toDoubleOrNull()
        val weeks    = etWeeks.text.toString().toIntOrNull()
        val age      = etAge.text.toString().toIntOrNull()
        val weight   = etWeight.text.toString().toDoubleOrNull()
        val height   = etHeight.text.toString().toDoubleOrNull()
        val days     = etDays.text.toString().toIntOrNull()
        // Current run defaults to 0 (can't run continuously yet) when left blank.
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
        render(result)
    }

    private fun render(r: RunPlanCoach.Result) {
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

        tvPlan.text = buildPlanText(r)
        results.visibility = View.VISIBLE
    }

    private fun buildPlanText(r: RunPlanCoach.Result): String {
        val sb = StringBuilder()
        if (r.verdict == RunPlanCoach.Verdict.NOT_REALISTIC) {
            sb.append("Safe ${r.recommendedHorizonWeeks}-week plan to reach your distance:\n\n")
        }
        for (w in r.plan) {
            val tag = if (w.isCutback) "  (recovery)" else ""
            sb.append("Week ${w.week} · ${w.phase}$tag\n")
            sb.append("  ${fmt(w.longRunKm)} km long run · ~${fmt(w.weeklyVolumeKm)} km total · ${w.runningDays} days\n")
            sb.append("  ${w.focus}\n\n")
        }
        return sb.toString().trimEnd()
    }

    private fun fmt(v: Double): String = if (v >= 10) "%.0f".format(v) else "%.1f".format(v)

    // ── Approval → schedule the plan ───────────────────────────────────────────
    private fun askApproval() {
        val result = lastResult ?: run {
            Toast.makeText(activity, "Evaluate a goal first", Toast.LENGTH_SHORT).show(); return
        }
        if (result.plan.isEmpty()) {
            Toast.makeText(activity, "No plan to schedule", Toast.LENGTH_SHORT).show(); return
        }

        val dp = activity.resources.displayMetrics.density
        fun pad(v: Int) = (v * dp).toInt()

        // Start date defaults to tomorrow; user can pick another via DatePickerDialog.
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
            .setMessage("I'll put dated workouts in your Schedule tab and remind you the evening before each one. On each workout day, that day's session loads automatically.")
            .setView(container)
            .setPositiveButton("Schedule it") { _, _ ->
                activatePlan(result, chosenDate.timeInMillis, warmupPicker.value, cooldownPicker.value)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun label(text: String): TextView = TextView(activity).apply {
        this.text = text
        setTextColor(Color.parseColor("#8A90B8"))
        textSize = 12f
        gravity = Gravity.START
        val dp = activity.resources.displayMetrics.density
        setPadding(0, (10 * dp).toInt(), 0, (2 * dp).toInt())
    }

    private fun activatePlan(result: RunPlanCoach.Result, startMidnightMs: Long, warmupMin: Int, cooldownMin: Int) {
        val profile = lastProfile ?: return
        val goal = lastGoal ?: return

        // Replace any previous plan: cancel its reminders first.
        planStore.load()?.let { WorkoutReminderReceiver.cancelAll(activity, it) }

        val plan = PlanScheduler.generate(result, profile, goal, startMidnightMs, warmupMin, cooldownMin)
        planStore.save(plan)
        WorkoutReminderReceiver.scheduleAll(activity, plan)

        showActivePlanStatus()

        val first = plan.workouts.firstOrNull()
        AlertDialog.Builder(activity)
            .setTitle("✅ Plan scheduled")
            .setMessage(
                "${plan.workouts.size} workouts added to your Schedule tab over ${result.plan.size} weeks.\n\n" +
                "Each includes a ${warmupMin}-min warmup walk and a ${cooldownMin}-min cooldown walk.\n\n" +
                (first?.let { "First workout: ${it.title} on ${it.dateLabel}.\n\n" } ?: "") +
                "I'll remind you at 6 PM the day before each session. Today's workout loads into the Schedule tab automatically on its day."
            )
            .setPositiveButton("Go to Schedule") { _, _ -> onPlanActivated?.invoke() }
            .setNegativeButton("OK", null)
            .show()
    }

    // ── Persistence ──────────────────────────────────────────────────────────
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
