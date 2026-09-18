package com.steptracker.wear.workout

data class WorkoutSnapshot(
    val phase: Phase = Phase.IDLE,
    val mode: String = WorkoutService.MODE_FREE,
    val scheduled: Boolean = false,
    val title: String = "",
    val periodLabel: String = "",
    val nextLabel: String = "",
    val periodLeftMs: Long = 0L,
    val steps: Int = 0,
    val distM: Double = 0.0,
    val elapsedMs: Long = 0L
) {
    enum class Phase { IDLE, ACTIVE, PAUSED, FINISHED }

    val paused: Boolean get() = phase == Phase.PAUSED
    val finished: Boolean get() = phase == Phase.FINISHED
    val inProgress: Boolean get() = phase == Phase.ACTIVE || phase == Phase.PAUSED
}
