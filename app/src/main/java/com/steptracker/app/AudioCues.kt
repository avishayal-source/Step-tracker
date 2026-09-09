package com.steptracker.app

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Coach's-whistle transition cues, synthesised at playback time (no audio assets).
 *
 * These replace the original square-wave bursts, which were harsh for two reasons: a square
 * wave is all odd harmonics, and they played at 95% volume on the *alarm* stream, ignoring
 * the user's media volume. Here the tone is a warbling sine — the trill of a pea whistle —
 * played on the media stream at a moderate level, briefly ducking any music.
 */
object AudioCues {

    private const val SAMPLE_RATE = 44100
    private const val DEFAULT_VOLUME = 0.55

    /** One whistle note: [freq] Hz for [durSec], then [gapSec] of silence. */
    private data class Note(
        val freq: Double,
        val durSec: Double,
        val gapSec: Double = 0.0,
        val volume: Double = DEFAULT_VOLUME
    )

    fun play(context: Context, type: ScheduleManager.SoundType) {
        Thread {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            var focus: AudioFocusRequest? = null
            try {
                focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(attrs)
                    .setWillPauseWhenDucked(false)
                    .build()
                am?.requestAudioFocus(focus)
                playPcm(render(notesFor(type)), attrs)
            } catch (_: Exception) {
                // A cue is never important enough to disturb the workout.
            } finally {
                try { focus?.let { am?.abandonAudioFocusRequest(it) } } catch (_: Exception) {}
            }
        }.start()
    }

    /** Rising for "go", falling for "ease off" — readable without looking at the phone. */
    private fun notesFor(type: ScheduleManager.SoundType): List<Note> = when (type) {
        ScheduleManager.SoundType.COUNTDOWN -> listOf(
            Note(2000.0, 0.09, volume = 0.34)
        )
        ScheduleManager.SoundType.TO_RUN -> listOf(
            Note(1900.0, 0.13, gapSec = 0.06),
            Note(2500.0, 0.26)
        )
        ScheduleManager.SoundType.TO_WALK -> listOf(
            Note(2500.0, 0.13, gapSec = 0.06),
            Note(1800.0, 0.30)
        )
        ScheduleManager.SoundType.COMPLETE -> listOf(
            Note(1900.0, 0.12, gapSec = 0.05),
            Note(2200.0, 0.12, gapSec = 0.05),
            Note(2600.0, 0.45)
        )
    }

    private fun render(notes: List<Note>): ShortArray {
        val total = notes.sumOf { ((it.durSec + it.gapSec) * SAMPLE_RATE).toInt() }
        val buf = ShortArray(total)
        var offset = 0
        for (note in notes) {
            val n = (note.durSec * SAMPLE_RATE).toInt()
            val attack = (SAMPLE_RATE * 0.012).toInt().coerceAtMost(n / 2)
            val release = (SAMPLE_RATE * 0.045).toInt().coerceAtMost(n / 2)
            var phase = 0.0
            for (i in 0 until n) {
                val t = i.toDouble() / SAMPLE_RATE
                // Warble: the trill that makes a whistle sound like a whistle.
                val instantaneous = note.freq * (1.0 + 0.02 * sin(2 * PI * 22.0 * t))
                phase += 2 * PI * instantaneous / SAMPLE_RATE
                // A touch of second harmonic for body, well below the fundamental.
                val sample = sin(phase) + 0.12 * sin(2 * phase)
                val env = when {
                    i < attack -> 0.5 - 0.5 * cos(PI * i / attack)
                    i >= n - release -> 0.5 - 0.5 * cos(PI * (n - i) / release)
                    else -> 1.0
                }
                buf[offset + i] = (env * sample * note.volume * 0.9 * Short.MAX_VALUE)
                    .toInt()
                    .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                    .toShort()
            }
            offset += n + (note.gapSec * SAMPLE_RATE).toInt()
        }
        return buf
    }

    private fun playPcm(buf: ShortArray, attrs: AudioAttributes) {
        if (buf.isEmpty()) return
        val minBuf = AudioTrack.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val track = AudioTrack.Builder()
            .setAudioAttributes(attrs)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(buf.size * 2, minBuf))
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        try {
            track.write(buf, 0, buf.size)
            track.play()
            Thread.sleep((buf.size * 1000L / SAMPLE_RATE) + 80L)
        } finally {
            try { track.stop() } catch (_: Exception) {}
            track.release()
        }
    }
}
