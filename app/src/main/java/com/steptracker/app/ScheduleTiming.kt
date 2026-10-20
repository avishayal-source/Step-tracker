package com.steptracker.app

/** Period arithmetic for a running schedule, kept free of timers so it can be tested directly. */
object ScheduleTiming {

    /** Shortest period left after a live edit, so shrinking a period never ends it abruptly. */
    const val MIN_REMAINING_AFTER_EDIT_MS = 5_000L

    data class Position(val index: Int, val remainingMs: Long)

    /**
     * Time left in the current period after its length changes from [oldDurationMs] to
     * [newDurationMs]. Elapsed time comes from the countdown itself, so pauses and earlier
     * edits are already accounted for.
     */
    fun remainingAfterEdit(oldDurationMs: Long, remainingMs: Long, newDurationMs: Long): Long {
        val elapsed = (oldDurationMs - remainingMs).coerceAtLeast(0L)
        return (newDurationMs - elapsed).coerceAtLeast(MIN_REMAINING_AFTER_EDIT_MS)
    }

    /** The start time that, without pauses, would leave exactly [remainingMs] at [nowMs]. */
    fun effectiveStart(nowMs: Long, durationMs: Long, remainingMs: Long): Long =
        nowMs - (durationMs - remainingMs).coerceAtLeast(0L)

    /**
     * Where a run stands [elapsedInPeriodMs] after period [startIndex] began, or null when
     * every period has finished. A negative elapsed time (start still in the future, e.g.
     * saved during the 3-2-1 countdown) means the period hasn't started yet.
     */
    fun positionAfter(durationsMs: List<Long>, startIndex: Int, elapsedInPeriodMs: Long): Position? {
        var elapsed = elapsedInPeriodMs.coerceAtLeast(0L)
        var i = startIndex.coerceAtLeast(0)
        while (i < durationsMs.size) {
            val d = durationsMs[i]
            if (elapsed < d) return Position(i, d - elapsed)
            elapsed -= d
            i++
        }
        return null
    }
}
