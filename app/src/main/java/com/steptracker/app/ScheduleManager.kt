package com.steptracker.app

import android.media.*
import android.os.CountDownTimer
import kotlin.math.*

class ScheduleManager(private val listener: Listener) {

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
        Thread {
            try { playWithAudioTrack(type) }
            catch (_: Exception) {
                try { playWithToneGenerator(type) } catch (_: Exception) {}
            }
        }.start()
    }

    private fun playWithAudioTrack(type: SoundType) {
        when (type) {
            SoundType.COUNTDOWN -> squareBurst(1200.0, 0.10)
            SoundType.TO_WALK -> {
                squareBurst(1400.0, 0.30); Thread.sleep(80)
                squareBurst(600.0,  0.55)
            }
            SoundType.TO_RUN -> {
                squareBurst(600.0,  0.20); Thread.sleep(60)
                squareBurst(1000.0, 0.20); Thread.sleep(60)
                squareBurst(1800.0, 0.55)
            }
            SoundType.COMPLETE -> {
                squareBurst(600.0,  0.15); Thread.sleep(50)
                squareBurst(800.0,  0.15); Thread.sleep(50)
                squareBurst(1000.0, 0.15); Thread.sleep(50)
                squareBurst(1400.0, 0.15); Thread.sleep(50)
                squareBurst(1800.0, 0.60)
            }
        }
    }

    private fun squareBurst(freq: Double, durSec: Double, volume: Double = 0.95) {
        val sr = 44100
        val n  = (sr * durSec).toInt()
        val buf = ShortArray(n)
        val period = sr / freq
        val attackSamples = (sr * 0.005).toInt()
        val releaseSamples = (sr * 0.03).toInt()
        for (i in 0 until n) {
            val squareVal = if ((i % period.toInt()) < (period / 2).toInt()) 1.0 else -1.0
            val env = when {
                i < attackSamples            -> i.toDouble() / attackSamples
                i >= n - releaseSamples      -> (n - i).toDouble() / releaseSamples
                else                         -> 1.0
            }
            buf[i] = (env * squareVal * volume * Short.MAX_VALUE).toInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        val minBuf = AudioTrack.getMinBufferSize(sr,
            AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            .setAudioFormat(AudioFormat.Builder()
                .setSampleRate(sr)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(maxOf(buf.size * 2, minBuf))
            .setTransferMode(AudioTrack.MODE_STATIC).build()
        track.write(buf, 0, buf.size)
        track.play()
        Thread.sleep((durSec * 1000 + 50).toLong())
        track.stop()
        track.release()
    }

    private fun playWithToneGenerator(type: SoundType) {
        val tg = ToneGenerator(AudioManager.STREAM_ALARM, ToneGenerator.MAX_VOLUME)
        when (type) {
            SoundType.COUNTDOWN -> {
                tg.startTone(ToneGenerator.TONE_PROP_BEEP, 100); Thread.sleep(180)
            }
            SoundType.TO_WALK -> {
                tg.startTone(ToneGenerator.TONE_CDMA_HIGH_PBX_L, 400); Thread.sleep(480)
                tg.startTone(ToneGenerator.TONE_CDMA_LOW_PBX_L,  600); Thread.sleep(680)
            }
            SoundType.TO_RUN -> {
                tg.startTone(ToneGenerator.TONE_CDMA_LOW_PBX_L,  220); Thread.sleep(280)
                tg.startTone(ToneGenerator.TONE_CDMA_MED_PBX_L,  220); Thread.sleep(280)
                tg.startTone(ToneGenerator.TONE_CDMA_HIGH_PBX_L, 500); Thread.sleep(580)
            }
            SoundType.COMPLETE -> {
                tg.startTone(ToneGenerator.TONE_CDMA_LOW_PBX_L,   180); Thread.sleep(240)
                tg.startTone(ToneGenerator.TONE_CDMA_MED_PBX_L,   180); Thread.sleep(240)
                tg.startTone(ToneGenerator.TONE_CDMA_HIGH_PBX_L,  180); Thread.sleep(240)
                tg.startTone(ToneGenerator.TONE_CDMA_HIGH_PBX_SSL, 800); Thread.sleep(880)
            }
        }
        tg.release()
    }
}
