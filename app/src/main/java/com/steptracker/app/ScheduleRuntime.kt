package com.steptracker.app

/**
 * Schedule state that must outlive the Activity. The timer keeps running when the UI is
 * recreated, so there is exactly one [ScheduleManager] per process and a new screen
 * re-attaches to it instead of starting a second timer from the saved snapshot.
 */
object ScheduleRuntime {
    var manager: ScheduleManager? = null
    var planWorkoutId: Long? = null
    var aheadOfSchedule = false
    val spokenMarkers = mutableSetOf<Int>()
}
