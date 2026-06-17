package com.steptracker.app

import android.content.Context
import android.graphics.Color
import android.view.View
import android.widget.EditText
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton

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
    private val prefs = activity.getSharedPreferences("coach_prefs", Context.MODE_PRIVATE)

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

        restoreInputs()
        btnEvaluate.setOnClickListener { evaluate() }
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
        render(RunPlanCoach.evaluate(profile, goal))
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
