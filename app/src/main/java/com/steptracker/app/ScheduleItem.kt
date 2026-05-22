package com.steptracker.app

import java.io.Serializable

data class ScheduleItem(
    val type: ActivityType,          // WALKING or RUNNING
    val durationMinutes: Int,
    var state: ScheduleState = ScheduleState.PENDING,
    var actualStartTime: Long = 0L,
    var actualEndTime: Long = 0L
) : Serializable {
    val durationMs: Long get() = durationMinutes * 60_000L
    val label: String get() = if (type == ActivityType.WALKING) "Walking" else "Jogging"
}

enum class ScheduleState { PENDING, ACTIVE, DONE }
