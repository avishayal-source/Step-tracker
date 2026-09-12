package com.steptracker.app

import kotlin.math.ceil
import kotlin.math.ln
import kotlin.math.pow

/**
 * On-device running coach — the "AI Goal Coach".
 *
 * Deterministic rules (no cloud LLM). This is **not** a trained ML model and is
 * **not** medical advice. Week counts and ramps are hand-coded heuristics drawn from
 * mainstream recreational coaching practice:
 *
 *  • ~10% weekly load increase (common coaching rule of thumb; not a hard
 *    physiological law — used here as a conservative default for non-beginners).
 *  • Periodic recovery / cutback weeks (every 4th–5th week) to reduce overuse risk.
 *  • Couch-to-5K-style fixed walk/run ladders for true beginners (longest &lt; 1 km)
 *    aiming ≤ 5 km (~9 weeks, or ~12 if high-risk) and ≤ 10 km (~16 / ~20 weeks).
 *  • BMI and age screens that lengthen the beginner ladder or tighten growth caps;
 *    they flag caution, they do not diagnose.
 *  • Adaptation floors so connective tissue gets weeks to adapt even for fit runners.
 *
 * Sources of the *ideas* (not copied plans): common C25K programme lengths,
 * ACSM / recreational running progressive-overload guidance as popularly summarised
 * in coaching literature, and Y Walk product choices when pure 10%-math produced
 * unrealistic 20+ week beginner 5K plans.
 *
 * If a goal cannot be reached in the requested time under these rules, the engine
 * does not compress dangerously: it offers a longer safe plan and/or a nearer
 * distance reachable in the asked window.
 */
object RunPlanCoach {

    private const val MAX_WEEKLY_GROWTH = 0.10
    private const val BEGINNER_WEEKLY_GROWTH = 0.15
    private const val SAFE_WEEKLY_GROWTH = 0.07
    private const val LONG_RUN_FRACTION = 0.38

    /** Fixed walk/run programmes for true beginners. */
    private const val C25K_WEEKS = 9
    private const val C25K_HIGH_RISK_WEEKS = 12
    private const val C25K_MAX_TARGET_KM = 5.0
    /** Zero → 10 km beginner ladder (after C25K band). */
    private const val C210K_WEEKS = 16
    private const val C210K_HIGH_RISK_WEEKS = 20
    private const val C210K_MAX_TARGET_KM = 10.0

    enum class Verdict { REALISTIC, AMBITIOUS, NOT_REALISTIC }

    data class Profile(
        val ageYears: Int,
        val sex: String,                  // "M", "F", "other"
        val weightKg: Double,
        val heightCm: Double,
        val currentLongestRunKm: Double,  // 0.0 if cannot yet run continuously
        val daysPerWeekAvailable: Int
    )

    data class Goal(
        val targetDistanceKm: Double,
        val horizonWeeks: Int,
        val targetTimeMinutes: Double? = null
    )

    data class WeekPlan(
        val week: Int,
        val phase: String,
        val longRunKm: Double,
        val weeklyVolumeKm: Double,
        val runningDays: Int,
        val focus: String,
        val isCutback: Boolean
    )

    data class Result(
        val verdict: Verdict,
        val headline: String,
        val rationale: List<String>,
        val cautions: List<String>,
        val bmi: Double,
        val requestedHorizonWeeks: Int,
        val recommendedHorizonWeeks: Int,
        val safeTargetInHorizonKm: Double,
        val plan: List<WeekPlan>
    )

    fun evaluate(p: Profile, g: Goal): Result {
        val cautions = mutableListOf<String>()
        val rationale = mutableListOf<String>()

        val heightM = (p.heightCm / 100.0).coerceAtLeast(0.5)
        val bmi = p.weightKg / (heightM * heightM)

        var highRisk = false
        when {
            bmi < 18.5 -> cautions.add("Your BMI (${fmt(bmi)}) is in the underweight range — make sure you're fuelling enough to support training.")
            bmi >= 35 -> { highRisk = true; cautions.add("Your BMI (${fmt(bmi)}) is high. Running is high-impact — please consult a doctor before starting. This plan uses a gentler Couch-to-5K-style ramp.") }
            bmi >= 30 -> { highRisk = true; cautions.add("Your BMI (${fmt(bmi)}) is in the obese range — get medical clearance if unsure. Early weeks stay walk-heavy to protect joints and heart.") }
            bmi >= 27 -> cautions.add("Your BMI (${fmt(bmi)}) is slightly elevated; early sessions stay low-impact.")
        }
        when {
            p.ageYears >= 65 -> { highRisk = true; cautions.add("You're 65+: please get medical clearance before beginning. The plan uses a longer beginner ramp and extra recovery.") }
            p.ageYears >= 50 -> cautions.add("You're 50+: a quick medical check-up is wise before ramping up.")
        }

        val beginner = p.currentLongestRunKm < 1.0
        if (beginner) cautions.add("You're starting from little or no continuous running, so the plan uses run/walk intervals rather than non-stop running.")

        val start = if (beginner) 1.0 else p.currentLongestRunKm
        val target = g.targetDistanceKm
        val horizon = g.horizonWeeks.coerceAtLeast(1)
        val runDays = p.daysPerWeekAvailable.coerceIn(2, if (highRisk) 4 else 5)

        // ── Fixed beginner ladders (not the 10%-rule stretch) ─────────────────
        if (beginner && target <= C25K_MAX_TARGET_KM) {
            return evaluateBeginnerLadder(
                g = g, bmi = bmi, highRisk = highRisk,
                start = start, target = target, horizon = horizon, runDays = runDays,
                safeWeeks = if (highRisk) C25K_HIGH_RISK_WEEKS else C25K_WEEKS,
                label = "Couch-to-5K-style",
                cautions = cautions, rationale = rationale
            )
        }
        if (beginner && target <= C210K_MAX_TARGET_KM) {
            return evaluateBeginnerLadder(
                g = g, bmi = bmi, highRisk = highRisk,
                start = start, target = target, horizon = horizon, runDays = runDays,
                safeWeeks = if (highRisk) C210K_HIGH_RISK_WEEKS else C210K_WEEKS,
                label = "beginner 10K-style",
                cautions = cautions, rationale = rationale
            )
        }

        // ── Distance ramp (beginners >10 km, intermediate, advanced) ──────────
        val growthCap = when {
            beginner && !highRisk -> BEGINNER_WEEKLY_GROWTH
            beginner && highRisk -> MAX_WEEKLY_GROWTH   // 10%, not the old 7% trap
            highRisk -> SAFE_WEEKLY_GROWTH
            else -> MAX_WEEKLY_GROWTH
        }
        val cutbackEvery = if (beginner) 5 else 4

        val taper = if (target >= 5.0) 1 else 0
        val adaptationFloor = if (beginner) 8 else 4
        val progressNeeded = if (target <= start) 0
            else ceil(ln(target / start) / ln(1.0 + growthCap)).toInt()
        val cutbacksNeeded = progressNeeded / (cutbackEvery - 1).coerceAtLeast(1)
        val safeWeeks = maxOf(progressNeeded + cutbacksNeeded + taper, adaptationFloor)

        val progressingInHorizon = ((1..horizon).count { it % cutbackEvery != 0 } - taper).coerceAtLeast(0)
        val safeTargetInHorizon = if (target <= start) target
            else minOf(target, start * (1.0 + growthCap).pow(progressingInHorizon))

        val verdict = when {
            target <= start -> Verdict.REALISTIC
            horizon >= ceil(safeWeeks * 1.15).toInt() -> Verdict.REALISTIC
            horizon >= safeWeeks -> Verdict.AMBITIOUS
            else -> Verdict.NOT_REALISTIC
        }
        val planWeeks = if (verdict == Verdict.NOT_REALISTIC) safeWeeks else horizon
        val recommendedHorizon = maxOf(horizon, safeWeeks)

        val startLabel = if (beginner) "run/walk basics" else "${fmt(start)} km continuous"
        val headline: String
        when (verdict) {
            Verdict.REALISTIC -> {
                headline = "✅ Realistic goal"
                if (target <= start) {
                    rationale.add("You can already cover ${fmt(target)} km — this plan sharpens endurance and consistency rather than just building distance.")
                } else {
                    rationale.add("Going from $startLabel to ${fmt(target)} km in $horizon weeks fits a safe ${"%d".format(safeWeeks)}-week build.")
                }
                rationale.add("Progression stays within about ${pct(growthCap)} per progressing week, with recovery weeks built in.")
            }
            Verdict.AMBITIOUS -> {
                headline = "⚠️ Ambitious — but achievable"
                rationale.add("Reaching ${fmt(target)} km from $startLabel safely needs about ${"%d".format(safeWeeks)} weeks; your $horizon-week target is right at that edge.")
                rationale.add("It's doable only with consistency — don't skip runs or recovery weeks, and stop if you feel pain.")
            }
            Verdict.NOT_REALISTIC -> {
                headline = "🛑 Too soon to be safe — here's a safer path"
                rationale.add("Building from $startLabel to ${fmt(target)} km safely takes about ${"%d".format(safeWeeks)} weeks, more than the $horizon you asked for. Rushing it raises injury risk, so I won't compress it.")
                rationale.add("Option A — keep the distance: follow the ${"%d".format(safeWeeks)}-week plan below.")
                rationale.add("Option B — keep your $horizon-week deadline: aim for about ${fmt(safeTargetInHorizon)} km instead.")
            }
        }

        appendPaceNotes(g, target, cautions, rationale)
        rationale.add("Most runs should be at an easy, conversational pace — that's where safe fitness is built.")

        val plan = buildRampPlan(start, target, planWeeks, growthCap, runDays, beginner, cutbackEvery)
        return Result(
            verdict = verdict,
            headline = headline,
            rationale = rationale,
            cautions = cautions,
            bmi = bmi,
            requestedHorizonWeeks = horizon,
            recommendedHorizonWeeks = recommendedHorizon,
            safeTargetInHorizonKm = round1(safeTargetInHorizon),
            plan = plan
        )
    }

    private fun evaluateBeginnerLadder(
        g: Goal, bmi: Double, highRisk: Boolean,
        start: Double, target: Double, horizon: Int, runDays: Int,
        safeWeeks: Int, label: String,
        cautions: MutableList<String>, rationale: MutableList<String>
    ): Result {
        // Cap at the fixed ladder length — asking for more weeks does not inflate the plan.
        val weeks = safeWeeks

        val verdict = when {
            horizon >= ceil(safeWeeks * 1.15).toInt() -> Verdict.REALISTIC
            horizon >= safeWeeks -> Verdict.AMBITIOUS
            else -> Verdict.NOT_REALISTIC
        }

        val safeTargetInHorizon = if (horizon >= weeks) target
            else round1(start + (target - start) * (horizon.toDouble() / weeks))

        val headline: String
        when (verdict) {
            Verdict.REALISTIC -> {
                headline = "✅ Realistic goal"
                rationale.add("A $label walk/run plan reaches ${fmt(target)} km in $weeks weeks — a practical beginner path, not a multi-month mileage-only ramp.")
            }
            Verdict.AMBITIOUS -> {
                headline = "⚠️ Ambitious — but achievable"
                rationale.add("The standard beginner plan for ${fmt(target)} km is $weeks weeks; your $horizon-week ask sits right on that line. Consistency matters.")
            }
            Verdict.NOT_REALISTIC -> {
                headline = "🛑 A bit soon — here's a solid beginner plan"
                rationale.add("Jumping to ${fmt(target)} km in $horizon weeks is tighter than a standard beginner walk/run build. I recommend $weeks weeks instead.")
                rationale.add("Option A — follow the $weeks-week plan below to reach ${fmt(target)} km.")
                if (safeTargetInHorizon < target) {
                    rationale.add("Option B — keep your $horizon-week deadline: aim for about ${fmt(safeTargetInHorizon)} km first, then extend.")
                }
            }
        }
        if (highRisk) {
            rationale.add("Because of your risk profile, this uses the longer $weeks-week beginner ramp with extra easy days.")
        } else {
            rationale.add("Sessions stay mostly run/walk intervals that get longer each week until you cover ${fmt(target)} km.")
        }

        appendPaceNotes(g, target, cautions, rationale)
        rationale.add("Most sessions should feel easy enough to talk — stop if you feel pain.")

        val plan = buildBeginnerLadderPlan(target, weeks, runDays, highRisk)
        return Result(
            verdict = verdict,
            headline = headline,
            rationale = rationale,
            cautions = cautions,
            bmi = bmi,
            requestedHorizonWeeks = horizon,
            recommendedHorizonWeeks = maxOf(horizon, weeks),
            safeTargetInHorizonKm = safeTargetInHorizon,
            plan = plan
        )
    }

    /**
     * Fixed walk/run ladder ending at [target]. Mid-plan recovery week(s) on longer programmes.
     */
    private fun buildBeginnerLadderPlan(
        target: Double, weeks: Int, runDays: Int, highRisk: Boolean
    ): List<WeekPlan> {
        val plan = ArrayList<WeekPlan>(weeks)
        val recoveryWeeks = when {
            weeks >= 16 -> setOf(weeks / 3, (2 * weeks) / 3)
            weeks >= 12 -> setOf(6)
            weeks >= 9 -> setOf(5)
            else -> emptySet()
        }
        val buildSlots = (1..weeks).count { it !in recoveryWeeks && it != weeks }.coerceAtLeast(1)
        var slot = 0
        var lastProgress = 1.0
        for (w in 1..weeks) {
            val isGoal = w == weeks
            val isCut = w in recoveryWeeks
            val longRun: Double
            when {
                isGoal -> longRun = target
                isCut -> longRun = lastProgress * 0.85
                else -> {
                    slot++
                    val t = slot.toDouble() / buildSlots
                    lastProgress = 1.0 + (target - 1.0) * t
                    if (slot == buildSlots) lastProgress = minOf(lastProgress, target * 0.92)
                    longRun = lastProgress
                }
            }
            val days = when {
                highRisk && w <= 4 -> minOf(runDays, 3)
                w <= 3 -> minOf(runDays, 3)
                else -> runDays
            }
            val volume = if (isGoal) longRun * 1.4 else longRun / LONG_RUN_FRACTION
            val phase = when {
                isGoal -> "Goal week 🎯"
                isCut -> "Recovery"
                w <= weeks * 0.35 -> "Walk/run base"
                w <= weeks * 0.7 -> "Building intervals"
                else -> "Toward continuous"
            }
            val focus = when {
                isGoal -> "Easy days earlier in the week, then attempt your ${fmt(target)} km goal with walk breaks as needed."
                isCut -> "Recovery week — shorter sessions (~${fmt(longRun)} km equivalent), keep it gentle."
                longRun < 3.0 -> "Run/walk: run 1–2 min, walk 1–2 min, repeat to cover ~${fmt(longRun)} km. Conversational pace."
                longRun < 6.0 -> "Longer run intervals (3–5 min) with short walks to cover ~${fmt(longRun)} km."
                longRun < target * 0.85 -> "Mostly continuous easy running toward ~${fmt(longRun)} km; walk briefly if you need to."
                else -> "Mostly continuous easy running toward ~${fmt(longRun)} km; walk briefly if you need to."
            }
            plan.add(WeekPlan(w, phase, round1(longRun), round1(volume), days, focus, isCut))
        }
        return plan
    }

    private fun buildRampPlan(
        start: Double, target: Double, weeks: Int,
        growthCap: Double, runDays: Int, beginner: Boolean, cutbackEvery: Int
    ): List<WeekPlan> {
        val plan = ArrayList<WeekPlan>(weeks)
        val taper = if (target >= 5.0 && weeks >= 4) 1 else 0
        val buildWeeks = (weeks - taper).coerceAtLeast(1)
        val progressingWeeks = (1..buildWeeks).count { it % cutbackEvery != 0 }.coerceAtLeast(1)
        val neededMult = if (target > start) (target / start).pow(1.0 / progressingWeeks) else 1.0
        val mult = minOf(neededMult, 1.0 + growthCap)

        var lastProgressLong = start
        for (w in 1..weeks) {
            val isTaper = taper == 1 && w == weeks
            val isCut = !isTaper && w % cutbackEvery == 0

            val longRun: Double
            when {
                isTaper -> longRun = lastProgressLong
                isCut -> longRun = lastProgressLong * 0.8
                else -> {
                    if (w > 1) lastProgressLong = minOf(target, lastProgressLong * mult)
                    longRun = lastProgressLong
                }
            }

            val volume = if (isTaper) longRun * 1.3 else longRun / LONG_RUN_FRACTION
            val days = if (beginner && w <= 3) minOf(runDays, 3) else runDays
            val phase = when {
                isTaper -> "Goal week 🎯"
                w <= weeks * 0.4 -> "Base building"
                w <= weeks * 0.75 -> "Endurance"
                else -> "Peak"
            }
            val focus = focusText(longRun, target, beginner && longRun < 4.0, isCut, isTaper)
            plan.add(WeekPlan(w, phase, round1(longRun), round1(volume), days, focus, isCut))
        }
        return plan
    }

    private fun focusText(longRun: Double, target: Double, runWalk: Boolean, isCut: Boolean, isTaper: Boolean): String = when {
        isTaper -> "Easy and short all week, rest 2 days, then attempt your ${fmt(target)} km goal feeling fresh."
        isCut -> "Recovery week — ease back to ~${fmt(longRun)} km, keep the pace gentle and let your body absorb the work."
        runWalk -> "Run/walk intervals: run 2–3 min, walk 1 min, repeat to cover ~${fmt(longRun)} km. Keep it conversational."
        else -> "Long run ~${fmt(longRun)} km at an easy pace; fill other days with shorter easy runs."
    }

    private fun appendPaceNotes(
        g: Goal, target: Double,
        cautions: MutableList<String>, rationale: MutableList<String>
    ) {
        if (g.targetTimeMinutes != null && g.targetTimeMinutes > 0 && target > 0) {
            val pace = g.targetTimeMinutes / target
            when {
                pace < 4.0 -> cautions.add("Your target pace (${pace(pace)}/km) is elite-level. Make finishing the distance the goal first.")
                pace < 5.0 -> rationale.add("Target pace ${pace(pace)}/km is advanced; prioritise completing the distance, then work on speed.")
                else -> rationale.add("Target pace ${pace(pace)}/km is reasonable once the distance feels comfortable.")
            }
        }
    }

    private fun round1(v: Double): Double = Math.round(v * 10.0) / 10.0
    private fun fmt(v: Double): String = if (v >= 10) "%.0f".format(v) else "%.1f".format(v)
    private fun pct(frac: Double): String = "%.0f%%".format(frac * 100)
    private fun pace(minPerKm: Double): String {
        val m = minPerKm.toInt()
        val s = ((minPerKm - m) * 60).toInt()
        return "%d:%02d".format(m, s)
    }
}
