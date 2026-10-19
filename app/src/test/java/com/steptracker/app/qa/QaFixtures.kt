package com.steptracker.app.qa

import com.steptracker.app.ActivityType
import com.steptracker.app.PlanScheduler
import com.steptracker.app.RunPlanCoach
import com.steptracker.app.StepDetector
import com.steptracker.app.TrainingPlan
import org.junit.rules.ExternalResource
import java.util.Calendar
import java.util.TimeZone
import kotlin.random.Random

const val DAY_MS = 86_400_000L
const val MIN_MS = 60_000L

/** Pins the JVM default time zone for one test class and restores it afterwards. */
class TimeZoneRule(private val zoneId: String) : ExternalResource() {
    private lateinit var saved: TimeZone
    override fun before() {
        saved = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone(zoneId))
    }
    override fun after() = TimeZone.setDefault(saved)
}

/** Local midnight of the given calendar date in the current default zone. */
fun localMidnight(year: Int, month1: Int, day: Int): Long =
    Calendar.getInstance().apply {
        clear()
        set(year, month1 - 1, day, 0, 0, 0)
    }.timeInMillis

/** Local wall-clock instant in the current default zone. */
fun localTime(year: Int, month1: Int, day: Int, hour: Int, minute: Int = 0): Long =
    Calendar.getInstance().apply {
        clear()
        set(year, month1 - 1, day, hour, minute, 0)
    }.timeInMillis

/** Local midnight [days] calendar days after [midnightMs] (DST-aware, unlike + days * 24h). */
fun plusCalendarDays(midnightMs: Long, days: Int): Long =
    Calendar.getInstance().apply {
        timeInMillis = midnightMs
        add(Calendar.DAY_OF_YEAR, days)
    }.timeInMillis

fun calendarFields(ms: Long): Triple<Int, Int, Int> {
    val c = Calendar.getInstance().apply { timeInMillis = ms }
    return Triple(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))
}

fun profile(
    age: Int = 35,
    weightKg: Double = 72.0,
    heightCm: Double = 175.0,
    longestRunKm: Double = 0.0,
    days: Int = 3,
    sex: String = "M"
) = RunPlanCoach.Profile(age, sex, weightKg, heightCm, longestRunKm, days)

fun buildPlan(
    p: RunPlanCoach.Profile,
    g: RunPlanCoach.Goal,
    startMidnightMs: Long,
    warmup: Int = 5,
    cooldown: Int = 5
): Pair<RunPlanCoach.Result, TrainingPlan> {
    val result = RunPlanCoach.evaluate(p, g)
    return result to PlanScheduler.generate(result, p, g, startMidnightMs, warmup, cooldown)
}

// ── Classifier driver ────────────────────────────────────────────────────────

/**
 * One stretch of synthetic movement fed to [StepDetector]: GPS speed (null = no fix),
 * step interval and landing impact, with seeded jitter.
 */
data class Segment(
    val label: String,
    val durationMs: Long,
    val gpsMps: Double?,
    val stepIntervalMs: Long?,
    val peakMag: Float = 3.0f,
    val gpsNoise: Double = 0.15,
    val intervalJitterMs: Long = 20,
    val truth: ActivityType
)

class ClassifierRecorder : StepDetector.StepListener {
    val changes = mutableListOf<Pair<Long, ActivityType>>()
    val stepsByActivity = mutableMapOf<ActivityType, Int>()
    var steps = 0
    override fun onStep(wallTimeMs: Long, activity: ActivityType) {
        steps++
        stepsByActivity[activity] = (stepsByActivity[activity] ?: 0) + 1
    }
    override fun onActivityChanged(newActivity: ActivityType, wallTimeMs: Long) {
        changes += wallTimeMs to newActivity
    }
    override fun onClassifierDebug(sample: StepDetector.DebugSample) {}
}

data class SegmentOutcome(
    val segment: Segment,
    val startMs: Long,
    /** ms from segment start until the classifier first matched the truth; null = never. */
    val latencyMs: Long?,
    /** share of steps in the segment's second half labelled with the truth. */
    val settledAccuracy: Double,
    val stepsCounted: Int,
    val peaksFed: Int
)

class ClassifierRun(seed: Int) {
    val rec = ClassifierRecorder()
    val detector = StepDetector(rec)
    private val rnd = Random(seed)
    private var now = 1_800_000_000_000L
    val outcomes = mutableListOf<SegmentOutcome>()

    fun play(vararg segments: Segment): ClassifierRun {
        for (s in segments) playOne(s)
        return this
    }

    private fun playOne(s: Segment) {
        val start = now
        val end = start + s.durationMs
        var nextGps = now
        var nextStep = now + (s.stepIntervalMs ?: Long.MAX_VALUE / 4)
        var lastStep = now
        var latency: Long? = if (detector.currentActivity == s.truth) 0L else null
        val stepsBefore = rec.steps
        var peaks = 0
        var secondHalfSteps = 0
        var secondHalfCorrect = 0

        while (now < end) {
            val t = minOf(nextGps, nextStep, end)
            now = t
            if (t >= end) break
            if (t == nextGps) {
                s.gpsMps?.let { v ->
                    val noise = if (s.gpsNoise > 0) rnd.nextDouble(-s.gpsNoise, s.gpsNoise) else 0.0
                    detector.updateGpsSpeed((v + noise).coerceAtLeast(0.0), t)
                }
                nextGps += 1_000L
            }
            if (t == nextStep && s.stepIntervalMs != null) {
                val interval = t - lastStep
                detector.registerStep(t, interval, s.peakMag + rnd.nextDouble(-0.3, 0.3).toFloat())
                peaks++
                lastStep = t
                if (t - start > s.durationMs / 2) {
                    secondHalfSteps++
                    if (detector.currentActivity == s.truth) secondHalfCorrect++
                }
                val j = if (s.intervalJitterMs > 0) rnd.nextLong(-s.intervalJitterMs, s.intervalJitterMs + 1) else 0L
                nextStep = t + (s.stepIntervalMs + j).coerceAtLeast(1L)
            }
            if (latency == null && detector.currentActivity == s.truth) latency = t - start
        }
        now = end
        outcomes += SegmentOutcome(
            segment = s,
            startMs = start,
            latencyMs = latency,
            settledAccuracy = if (secondHalfSteps == 0) 1.0 else secondHalfCorrect.toDouble() / secondHalfSteps,
            stepsCounted = rec.steps - stepsBefore,
            peaksFed = peaks
        )
    }

    fun report(): String = outcomes.joinToString("\n") {
        "%-28s truth=%-8s latency=%-7s settled=%3.0f%% steps=%d/%d".format(
            it.segment.label, it.segment.truth,
            it.latencyMs?.let { l -> "${l / 1000}s" } ?: "NEVER",
            it.settledAccuracy * 100, it.stepsCounted, it.peaksFed
        )
    }
}

// Gait profiles. Interval ms → SPM: 545≈110, 290≈207 (doubled walk), 350≈171, 320≈188.
// Peak impacts are the medians measured on the 2026-09-22 log: walk ≈ 2.7, jog ≈ 10, run ≈ 19.
fun walk(sec: Int, mps: Double = 1.35, label: String = "walk") =
    Segment(label, sec * 1000L, mps, 545, 2.8f, truth = ActivityType.WALKING)
fun doubledWalk(sec: Int, mps: Double, label: String = "walk (doubled peaks)") =
    Segment(label, sec * 1000L, mps, 290, 2.8f, truth = ActivityType.WALKING)
fun jog(sec: Int, mps: Double = 2.1, label: String = "jog") =
    Segment(label, sec * 1000L, mps, 350, 10.0f, truth = ActivityType.JOGGING)
fun run(sec: Int, mps: Double = 3.2, label: String = "run") =
    Segment(label, sec * 1000L, mps, 320, 19.0f, truth = ActivityType.RUNNING)
