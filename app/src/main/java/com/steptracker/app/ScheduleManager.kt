package com.steptracker.app

import android.content.Context
import android.media.*
import android.os.CountDownTimer
import kotlin.math.*

class ScheduleManager(
    private val context: Context,
    private val listener: Listener
) {

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

    /** Begin 3-2-1 countdown, then run the schedule. */
    fun startWithPrep(schedule: List<ScheduleItem>) {
        prepTimer?.cancel()
        prepTimer = object : CountDownTimer(3000L, 1000L) {
            override fun onTick(remaining: Long) {
                val secs = ((remaining + 999) / 1000).toInt().coerceIn(1, 3)
                playSound(SoundType.COUNTDOWN)
                listener.onPrepCountdown(secs)
            }
            override fun onFinish() {
                listener.onPrepFinished()
                start(schedule)
            }
        }.start()
    }

    fun start(schedule: List<ScheduleItem>) {
        items = schedule.map { it.copy(state = ScheduleState.PENDING,
            actualStartTime = 0L, actualEndTime = 0L) }.toMutableList()
        currentIndex = 0
        isRunning = true
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

        var index = snapshot.currentIndex.coerceIn(0, items.lastIndex)
        var elapsedInPeriod = System.currentTimeMillis() - snapshot.periodStartMs

        while (index < items.size) {
            val dur = items[index].durationMs
            if (elapsedInPeriod < dur) {
                startPeriod(index, (dur - elapsedInPeriod).coerceAtLeast(500L))
                return
            }
            items[index] = items[index].copy(
                state = ScheduleState.DONE,
                actualEndTime = System.currentTimeMillis()
            )
            elapsedInPeriod -= dur
            index++
        }

        isRunning = false
        listener.onScheduleComplete()
    }

    fun stop() {
        prepTimer?.cancel(); prepTimer = null
        timer?.cancel(); timer = null; isRunning = false
        if (currentIndex < items.size)
            items[currentIndex] = items[currentIndex].copy(state = ScheduleState.PENDING)
    }

    fun getItems(): List<ScheduleItem> = items

    fun editPeriodDuration(index: Int, newMinutes: Int) {
        if (index < 0 || index >= items.size) return
        items[index] = items[index].copy(durationMinutes = newMinutes)
        if (index == currentIndex && isRunning) {
            val elapsed = items[index].actualStartTime.let {
                if (it > 0) System.currentTimeMillis() - it else 0L }
            val remaining = (newMinutes * 60_000L - elapsed).coerceAtLeast(5000L)
            timer?.cancel()
            startPeriod(currentIndex, remaining)
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

    private fun startPeriod(index: Int, durationMs: Long) {
        if (index >= items.size) {
            isRunning = false
            playSound(SoundType.COMPLETE)
            listener.onScheduleComplete()
            return
        }
        currentIndex = index
        items[index] = items[index].copy(state = ScheduleState.ACTIVE,
            actualStartTime = System.currentTimeMillis())
        for (i in 0 until index)
            if (items[i].state != ScheduleState.DONE)
                items[i] = items[i].copy(state = ScheduleState.DONE)

        timer?.cancel()
        val nextType = if (index + 1 < items.size) items[index + 1].type else null

        timer = object : CountDownTimer(durationMs, 250) {
            override fun onTick(remaining: Long) {
                listener.onTick(index, remaining)
            }
            override fun onFinish() {
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

    fun playSound(type: SoundType) {
        if (!UserPrefs(context).soundCuesEnabled) return
        AudioCues.play(context, type)
    }
}
