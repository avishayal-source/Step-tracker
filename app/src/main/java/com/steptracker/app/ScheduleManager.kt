package com.steptracker.app

import android.content.Context
import android.os.CountDownTimer

class ScheduleManager(
    context: Context,
    var listener: Listener
) {
    private val context: Context = context.applicationContext

    interface Listener {
        fun onTick(periodIndex: Int, remainingMs: Long)
        fun onPeriodComplete(completedIndex: Int, nextIndex: Int?)
        fun onScheduleComplete()
        fun onScheduleEdited()
        /** Seconds left before the first period (3, 2, 1). */
        fun onPrepCountdown(secondsLeft: Int) {}
        /** Prep finished; first period timer is starting. */
        fun onPrepFinished() {}
    }

    private var items: MutableList<ScheduleItem> = mutableListOf()
    var currentIndex = 0
        private set
    private var timer: CountDownTimer? = null
    private var prepTimer: CountDownTimer? = null
    var isRunning = false
        private set
    var isPaused = false
        private set
    /** Remaining time in the active period while paused (or last tick while running). */
    var pausedRemainingMs = 0L
        private set

    /** True during the 3-2-1 countdown, before the first period exists. */
    var isPreparing = false
        private set
    /** When the countdown ends and the first period starts (wall clock). */
    var prepEndsAtMs = 0L
        private set
    var prepItems: List<ScheduleItem> = emptyList()
        private set

    /** Begin 3-2-1 countdown, then run the schedule. */
    fun startWithPrep(schedule: List<ScheduleItem>) {
        prepTimer?.cancel()
        isPaused = false
        pausedRemainingMs = 0L
        isPreparing = true
        prepItems = schedule
        prepEndsAtMs = System.currentTimeMillis() + PREP_MS
        prepTimer = object : CountDownTimer(PREP_MS, 1000L) {
            override fun onTick(remaining: Long) {
                val secs = ((remaining + 999) / 1000).toInt().coerceIn(1, 3)
                playSound(SoundType.COUNTDOWN)
                listener.onPrepCountdown(secs)
            }
            override fun onFinish() {
                isPreparing = false
                prepTimer = null
                start(schedule)
                listener.onPrepFinished()
            }
        }.start()
    }

    fun start(schedule: List<ScheduleItem>) {
        items = schedule.map { it.copy(state = ScheduleState.PENDING,
            actualStartTime = 0L, actualEndTime = 0L) }.toMutableList()
        currentIndex = 0
        isRunning = true
        isPaused = false
        startPeriod(0, items[0].durationMs)
    }

    /** Resume after activity was recreated. */
    fun resume(snapshot: ScheduleRunPersistence.Snapshot) {
        items = snapshot.items.toMutableList()
        isRunning = true
        if (items.isEmpty()) {
            isRunning = false
            listener.onScheduleComplete()
            return
        }

        if (snapshot.paused && snapshot.remainingMs > 0L) {
            currentIndex = snapshot.currentIndex.coerceIn(0, items.lastIndex)
            isPaused = true
            pausedRemainingMs = snapshot.remainingMs
            // Keep ACTIVE; UI shows frozen countdown until Resume.
            items[currentIndex] = items[currentIndex].copy(state = ScheduleState.ACTIVE)
            listener.onTick(currentIndex, pausedRemainingMs)
            return
        }

        isPaused = false
        val index = snapshot.currentIndex.coerceIn(0, items.lastIndex)
        val now = System.currentTimeMillis()
        val pos = ScheduleTiming.positionAfter(
            items.map { it.durationMs }, index, now - snapshot.periodStartMs)
        val firstUnfinished = pos?.index ?: items.size
        for (i in index until firstUnfinished) {
            items[i] = items[i].copy(state = ScheduleState.DONE, actualEndTime = now)
        }
        if (pos != null) {
            startPeriod(pos.index, pos.remainingMs.coerceAtLeast(500L))
            return
        }

        isRunning = false
        listener.onScheduleComplete()
    }

    /** Freeze the current period timer without aborting the session. */
    fun pause(): Boolean {
        if (!isRunning || isPaused) return false
        prepTimer?.cancel(); prepTimer = null
        timer?.cancel(); timer = null
        isPaused = true
        // pausedRemainingMs already set from the last onTick
        return true
    }

    /** Continue from the frozen remaining time. */
    fun resumeFromPause(): Boolean {
        if (!isRunning || !isPaused) return false
        isPaused = false
        val remaining = pausedRemainingMs.coerceAtLeast(500L)
        startPeriod(currentIndex, remaining, restartClock = false)
        return true
    }

    /** Abort the run. Editor list is owned by the view — not cleared here. */
    fun stop() {
        prepTimer?.cancel(); prepTimer = null
        timer?.cancel(); timer = null
        isRunning = false
        isPaused = false
        isPreparing = false
        pausedRemainingMs = 0L
        if (currentIndex < items.size)
            items[currentIndex] = items[currentIndex].copy(state = ScheduleState.PENDING)
    }

    fun getItems(): List<ScheduleItem> = items

    fun editPeriodDuration(index: Int, newMinutes: Int) {
        if (index < 0 || index >= items.size) return
        val oldMs = items[index].durationMs
        items[index] = items[index].copy(durationMinutes = newMinutes)
        val remaining = ScheduleTiming.remainingAfterEdit(oldMs, pausedRemainingMs, items[index].durationMs)
        if (index == currentIndex && isRunning && !isPaused) {
            timer?.cancel()
            startPeriod(currentIndex, remaining, restartClock = false)
        } else if (index == currentIndex && isPaused) {
            pausedRemainingMs = remaining
            listener.onTick(currentIndex, pausedRemainingMs)
        }
        listener.onScheduleEdited()
    }

    fun appendPeriod(item: ScheduleItem) {
        items.add(item.copy(state = ScheduleState.PENDING))
        listener.onScheduleEdited()
    }

    fun removePeriod(index: Int) {
        if (index <= currentIndex) return
        items.removeAt(index)
        listener.onScheduleEdited()
    }

    private fun startPeriod(index: Int, durationMs: Long, restartClock: Boolean = true) {
        if (index >= items.size) {
            isRunning = false
            isPaused = false
            playSound(SoundType.COMPLETE)
            listener.onScheduleComplete()
            return
        }
        currentIndex = index
        val startMs = if (restartClock || items[index].actualStartTime == 0L)
            System.currentTimeMillis()
        else
            items[index].actualStartTime
        items[index] = items[index].copy(state = ScheduleState.ACTIVE, actualStartTime = startMs)
        for (i in 0 until index)
            if (items[i].state != ScheduleState.DONE)
                items[i] = items[i].copy(state = ScheduleState.DONE)

        timer?.cancel()
        pausedRemainingMs = durationMs
        val nextType = if (index + 1 < items.size) items[index + 1].type else null

        timer = object : CountDownTimer(durationMs, 250) {
            override fun onTick(remaining: Long) {
                pausedRemainingMs = remaining
                listener.onTick(index, remaining)
            }
            override fun onFinish() {
                pausedRemainingMs = 0L
                items[index] = items[index].copy(state = ScheduleState.DONE,
                    actualEndTime = System.currentTimeMillis())
                when (nextType) {
                    ActivityType.RUNNING -> playSound(SoundType.TO_RUN)
                    ActivityType.WALKING -> playSound(SoundType.TO_WALK)
                    null                 -> playSound(SoundType.COMPLETE)
                    else                 -> playSound(SoundType.TO_WALK)
                }
                listener.onPeriodComplete(index, if (index + 1 < items.size) index + 1 else null)
                startPeriod(index + 1,
                    if (index + 1 < items.size) items[index + 1].durationMs else 0L)
            }
        }.start()
    }

    enum class SoundType { COUNTDOWN, TO_WALK, TO_RUN, COMPLETE }

    companion object {
        const val PREP_MS = 3000L
    }

    fun playSound(type: SoundType) {
        if (!UserPrefs(context).soundCuesEnabled) return
        AudioCues.play(context, type)
    }
}
