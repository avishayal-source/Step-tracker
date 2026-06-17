package com.steptracker.app

import kotlin.math.ceil
import kotlin.math.ln
import kotlin.math.pow

/**
 * On-device running coach — the "AI Goal Coach".
 *
 * This is a deterministic, evidence-based engine (no cloud LLM, no network). The
 * product requirement is that it must NEVER hand back an unrealistic or risky plan,
 * which is far easier to guarantee with hard rules than with a generative model.
 * The rules encode mainstream endurance-training science:
 *
 *  • The "10% rule": weekly training load should not grow more than ~10% per week.
 *    High-risk profiles (high BMI, older age) are capped tighter (~7%).
 *  • Recovery weeks: every 4th week is a cut-back week (reduced volume) to let the
 *    body adapt and avoid overuse injury.
 *  • Beginners start with run/walk intervals (Couch-to-5K style) rather than
 *    continuous running.
 *  • An adaptation floor: connective tissue needs weeks to adapt, so even fit
 *    runners get a minimum programme length.
 *  • Risk screening: BMI and age raise cautions and force a gentler ramp, and we
 *    recommend medical clearance where appropriate.
 *
 * If a goal cannot be reached safely in the requested time, the engine does NOT
 * compress the plan into something dangerous. Instead it reports the minimum safe
 * timeframe AND the distance that *is* safely reachable in the requested window,
 * and the generated plan always respects the safe ramp.
 */
object RunPlanCoach {

    private const val MAX_WEEKLY_GROWTH = 0.10   // standard 10%-per-week rule
    private const val SAFE_WEEKLY_GROWTH = 0.07  // tighter cap for higher-risk profiles
    private const val LONG_RUN_FRACTION = 0.38   // long run as a share of weekly volume

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
        val targetTimeMinutes: Double? = null   // optional finish-time goal
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

        // ── Risk screening ────────────────────────────────────────────────────
        var growthCap = MAX_WEEKLY_GROWTH
        var highRisk = false

        when {
            bmi < 18.5 -> cautions.add("Your BMI (${fmt(bmi)}) is in the underweight range — make sure you're fuelling enough to support training.")
            bmi >= 35 -> { highRisk = true; cautions.add("Your BMI (${fmt(bmi)}) is high. Running is high-impact, so this plan leans heavily on walking first and ramps very gently. Please consult a doctor before starting.") }
            bmi >= 30 -> { highRisk = true; cautions.add("Your BMI (${fmt(bmi)}) is in the obese range — the plan keeps early impact low and progresses slowly to protect your joints and heart.") }
            bmi >= 27 -> cautions.add("Your BMI (${fmt(bmi)}) is slightly elevated; the plan keeps early sessions low-impact.")
        }
        when {
            p.ageYears >= 65 -> { highRisk = true; cautions.add("You're 65+: please get medical clearance before beginning, and expect to need extra recovery between sessions.") }
            p.ageYears >= 50 -> cautions.add("You're 50+: a quick medical check-up is wise before ramping up, and the plan allows extra recovery.")
        }

        val beginner = p.currentLongestRunKm < 1.0
        if (beginner) cautions.add("You're starting from little or no continuous running, so the plan begins with run/walk intervals rather than non-stop running.")
        if (highRisk) growthCap = SAFE_WEEKLY_GROWTH

        // ── Starting point & target ─────────────────────────────────────────────
        val start = if (beginner) 1.0 else p.currentLongestRunKm   // beginners ≈ 1 km run/walk equivalent
        val target = g.targetDistanceKm
        val horizon = g.horizonWeeks.coerceAtLeast(1)
        val runDays = p.daysPerWeekAvailable.coerceIn(2, if (highRisk) 4 else 5)

        // ── How long does a SAFE build to the target take? ──────────────────────
        val taper = if (target >= 5.0) 1 else 0
        val adaptationFloor = if (beginner) 8 else 4
        val progressNeeded = if (target <= start) 0
            else ceil(ln(target / start) / ln(1.0 + growthCap)).toInt()
        val cutbacksNeeded = progressNeeded / 3
        val safeWeeks = maxOf(progressNeeded + cutbacksNeeded + taper, adaptationFloor)

        // Distance safely reachable within the requested horizon (for the fallback offer)
        val progressingInHorizon = ((1..horizon).count { it % 4 != 0 } - taper).coerceAtLeast(0)
        val safeTargetInHorizon = if (target <= start) target
            else minOf(target, start * (1.0 + growthCap).pow(progressingInHorizon))

        // ── Verdict ─────────────────────────────────────────────────────────────
        val verdict = when {
            target <= start -> Verdict.REALISTIC
            horizon >= ceil(safeWeeks * 1.15).toInt() -> Verdict.REALISTIC
            horizon >= safeWeeks -> Verdict.AMBITIOUS
            else -> Verdict.NOT_REALISTIC
        }

        // The plan always respects the safe ramp: for an over-aggressive goal we
        // span the minimum safe number of weeks instead of compressing dangerously.
        val planWeeks = if (verdict == Verdict.NOT_REALISTIC) safeWeeks else horizon
        val recommendedHorizon = maxOf(horizon, safeWeeks)

        // ── Headline + rationale ────────────────────────────────────────────────
        val startLabel = if (beginner) "run/walk basics" else "${fmt(start)} km continuous"
        val headline: String
        when (verdict) {
            Verdict.REALISTIC -> {
                headline = "✅ Realistic goal"
                if (target <= start) {
                    rationale.add("You can already cover ${fmt(target)} km — this plan sharpens endurance and consistency rather than just building distance.")
                } else {
                    rationale.add("Going from $startLabel to ${fmt(target)} km in $horizon weeks fits comfortably within a safe ${"%d".format(safeWeeks)}-week build.")
                }
                rationale.add("Progression stays within the ${pct(growthCap)} weekly increase guideline, with recovery weeks built in.")
            }
            Verdict.AMBITIOUS -> {
                headline = "⚠️ Ambitious — but achievable"
                rationale.add("Reaching ${fmt(target)} km from $startLabel safely needs about ${"%d".format(safeWeeks)} weeks; your $horizon-week target is right at that edge.")
                rationale.add("It's doable only with consistency — don't skip runs or recovery weeks, and stop if you feel pain.")
            }
            Verdict.NOT_REALISTIC -> {
                headline = "🛑 Too soon to be safe — here's a safer path"
                rationale.add("Building from $startLabel to ${fmt(target)} km safely takes about ${"%d".format(safeWeeks)} weeks, more than the $horizon you asked for. Rushing it sharply raises injury risk, so I won't compress it.")
                rationale.add("Option A — keep the distance: follow the ${"%d".format(safeWeeks)}-week plan below to reach ${fmt(target)} km safely.")
                rationale.add("Option B — keep your $horizon-week deadline: aim for about ${fmt(safeTargetInHorizon)} km instead, which is safely reachable in that time.")
            }
        }

        // ── Optional finish-time realism note ───────────────────────────────────
        if (g.targetTimeMinutes != null && g.targetTimeMinutes > 0 && target > 0) {
            val pace = g.targetTimeMinutes / target   // minutes per km
            when {
                pace < 4.0 -> cautions.add("Your target pace (${pace(pace)}/km) is elite-level. Chasing it on this timeline is unsafe — make finishing the distance the goal first.")
                pace < 5.0 -> rationale.add("Target pace ${pace(pace)}/km is advanced; prioritise completing the distance, then work on speed once the distance is comfortable.")
                else -> rationale.add("Target pace ${pace(pace)}/km is reasonable — the plan's easy mileage builds the aerobic base to get there.")
            }
        }

        rationale.add("Most runs should be at an easy, conversational pace — that's where safe fitness is built.")

        val plan = buildPlan(start, target, planWeeks, growthCap, runDays, beginner)
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

    private fun buildPlan(
        start: Double, target: Double, weeks: Int,
        growthCap: Double, runDays: Int, beginner: Boolean
    ): List<WeekPlan> {
        val plan = ArrayList<WeekPlan>(weeks)
        val taper = if (target >= 5.0 && weeks >= 4) 1 else 0
        val buildWeeks = (weeks - taper).coerceAtLeast(1)
        val progressingWeeks = (1..buildWeeks).count { it % 4 != 0 }.coerceAtLeast(1)
        // Per-progressing-week multiplier to reach the target, never exceeding the cap.
        val neededMult = if (target > start) (target / start).pow(1.0 / progressingWeeks) else 1.0
        val mult = minOf(neededMult, 1.0 + growthCap)

        var lastProgressLong = start
        for (w in 1..weeks) {
            val isTaper = taper == 1 && w == weeks
            val isCut = !isTaper && w % 4 == 0

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
            val focus = focusText(longRun, target, beginner && longRun < 3.0, isCut, isTaper)
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

    // ── formatting helpers ──────────────────────────────────────────────────────
    private fun round1(v: Double): Double = Math.round(v * 10.0) / 10.0
    private fun fmt(v: Double): String = if (v >= 10) "%.0f".format(v) else "%.1f".format(v)
    private fun pct(frac: Double): String = "%.0f%%".format(frac * 100)
    private fun pace(minPerKm: Double): String {
        val m = minPerKm.toInt()
        val s = ((minPerKm - m) * 60).toInt()
        return "%d:%02d".format(m, s)
    }
}
