package com.steptracker.app.qa

import com.steptracker.app.ActivityType
import com.steptracker.app.StepDetector
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Replays recorded classifier_debug CSVs (device_logs/, pulled_logs/) through the current
 * StepDetector using the logged raw GPS speeds, step intervals and peak impacts, and
 * compares against the activity the app recorded at the time.
 *
 * The logs are personal workout data and are not committed; the test is skipped when
 * none are present. Logs recorded before peak impact was logged replay with impact 0.
 */
class ClassifierReplayTest {

    private val logDirs = listOf("../device_logs", "../pulled_logs").map(::File)

    data class Replay(val name: String, val steps: Int, val agree: Int, val diffs: List<String>)

    private fun replay(file: File): Replay? {
        val lines = file.readLines()
        if (lines.size < 2) return null
        val header = lines[0].split(',')
        fun idx(n: String) = header.indexOf(n)
        val iEvent = idx("event"); val iWall = idx("wall_time_ms"); val iInt = idx("interval_ms")
        val iRaw = idx("raw_gps_mps"); val iPeak = idx("peak_mag"); val iAct = idx("current_activity")
        val iElapsed = idx("session_elapsed_ms")
        if (listOf(iEvent, iWall, iInt, iRaw, iAct).any { it < 0 }) return null

        val rec = ClassifierRecorder()
        val det = StepDetector(rec)
        var steps = 0; var agree = 0
        val diffs = mutableListOf<String>()
        var diffStart: String? = null; var diffLen = 0
        for (line in lines.drop(1)) {
            val c = line.split(',')
            if (c.size < header.size) continue
            when (c[iEvent]) {
                "gps_update" -> c[iRaw].toDoubleOrNull()?.let { det.updateGpsSpeed(it, c[iWall].toLong()) }
                "step", "bootstrap", "switch" -> {
                    val peak = if (iPeak >= 0) c[iPeak].toFloatOrNull() ?: 0f else 0f
                    det.registerStep(c[iWall].toLong(), c[iInt].toLong(), peak)
                    val logged = runCatching { ActivityType.valueOf(c[iAct]) }.getOrNull() ?: continue
                    steps++
                    if (det.currentActivity == logged) {
                        agree++
                        if (diffLen >= 10) diffs += "$diffStart ($diffLen steps)"
                        diffStart = null; diffLen = 0
                    } else {
                        if (diffStart == null) diffStart = "${(c[iElapsed].toLong() / 1000)}s logged=$logged now=${det.currentActivity}"
                        diffLen++
                    }
                }
            }
        }
        if (diffLen >= 10) diffs += "$diffStart ($diffLen steps)"
        return Replay(file.name, steps, agree, diffs)
    }

    @Test
    fun replayRecordedWorkouts() {
        val files = logDirs.filter { it.isDirectory }
            .flatMap { d -> d.listFiles { f -> f.name.startsWith("classifier_debug_") && f.name.endsWith(".csv") }!!.toList() }
            .distinctBy { it.name.substringAfter("classifier_debug_").take(15) }
            .sortedBy { it.name }
        assumeTrue("no recorded classifier logs present", files.isNotEmpty())
        val results = files.mapNotNull(::replay)
        val sb = StringBuilder("Replay of recorded workouts vs. labels recorded at the time\n")
        for (r in results) {
            sb.appendLine("%-42s steps=%5d agree=%5.1f%%".format(r.name, r.steps, 100.0 * r.agree / maxOf(1, r.steps)))
            r.diffs.take(8).forEach { sb.appendLine("    differs from $it") }
        }
        println(sb)
        File("build/qa-reports").apply { mkdirs() }.resolve("classifier_replay.txt").writeText(sb.toString())
        assertTrue(results.all { it.steps >= 0 })
    }
}
