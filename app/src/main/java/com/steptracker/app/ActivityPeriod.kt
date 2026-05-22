package com.steptracker.app

import java.io.Serializable

enum class ActivityType { WALKING, RUNNING, IDLE }

data class ActivityPeriod(
    val type: ActivityType,
    val startTime: Long,
    var endTime: Long = 0L,
    var steps: Int = 0,
    var gpsDistanceM: Double = 0.0,
    var walkStride: Double = 0.78,   // raised default: avg adult walk ~0.78m
    var runStride: Double  = 1.20    // raised default: avg adult jog ~1.20m
) : Serializable {
    val durationMs: Long
        get() = if (endTime > 0) endTime - startTime else System.currentTimeMillis() - startTime

    val stepDistanceM: Double
        get() = steps * if (type == ActivityType.RUNNING) runStride else walkStride

    // GPS is authoritative when it has accumulated meaningful distance (>5m),
    // otherwise fall back to step-based estimate
    val distanceMeters: Double
        get() = if (gpsDistanceM > 5.0) gpsDistanceM else stepDistanceM

    val usingGps: Boolean
        get() = gpsDistanceM > 5.0
}
