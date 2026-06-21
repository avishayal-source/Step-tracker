package com.steptracker.app

import java.io.Serializable

enum class ActivityType { WALKING, JOGGING, RUNNING, IDLE }

data class ActivityPeriod(
    val type: ActivityType,
    val startTime: Long,
    var endTime: Long = 0L,
    var steps: Int = 0,
    var gpsDistanceM: Double = 0.0,
    // Step-estimated distance accumulated BEFORE the first GPS fix arrived.
    // Once GPS engages it drives distance; this baseline accounts for the
    // opening segment GPS never measured, so there is no jump at switchover.
    var stepDistanceAtGpsStartM: Double = 0.0,
    var gpsEngaged: Boolean = false,
    var walkStride: Double = 0.78,   // raised default: avg adult walk ~0.78m
    var runStride: Double  = 1.20    // raised default: avg adult jog ~1.20m
) : Serializable {
    val durationMs: Long
        get() = if (endTime > 0) endTime - startTime else System.currentTimeMillis() - startTime

    // Jogging is a running gait, so it uses the run stride for step-estimated distance.
    val stepDistanceM: Double
        get() = steps * if (type == ActivityType.RUNNING || type == ActivityType.JOGGING) runStride else walkStride

    // Once GPS engages: pre-GPS segment is step-estimated, the rest is GPS-measured.
    // This is continuous at switchover (gpsDistanceM starts at 0) and avoids the
    // old behaviour of dropping the opening segment when crossing a fixed threshold.
    val distanceMeters: Double
        get() = if (gpsEngaged) stepDistanceAtGpsStartM + gpsDistanceM else stepDistanceM

    val usingGps: Boolean
        get() = gpsEngaged
}
