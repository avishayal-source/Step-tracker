package com.steptracker.wear.workout

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
 * Coach-whistle transition cues (same notes as the phone). Wear speakers are
 * quiet, so [WorkoutCues] also vibrates; this is the audible half.
 */
object AudioCues {

    private const val SAMPLE_RATE = 22050
    private const val DEFAULT_VOLUME = 0.72

    private const val WARBLE_DEPTH = 0.035
    private const val WARBLE_HZ = 15.0
    private const val HARMONIC_2 = 0.08
    private const val HARMONIC_3 = 0.04

    private data class Note(
        val freq: Double,
        val durSec: Double,
        val gapSec: Double = 0.0,
        val volume: Double = DEFAULT_VOLUME
    )

    fun play(context: Context, type: WorkoutCues.SoundType) {
        Thread {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
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
            } finally {
                try { focus?.let { am?.abandonAudioFocusRequest(it) } } catch (_: Exception) {}
            }
        }.start()
    }

    private fun notesFor(type: WorkoutCues.SoundType): List<Note> = when (type) {
        WorkoutCues.SoundType.TO_RUN -> listOf(
            Note(1450.0, 0.12, gapSec = 0.05),
            Note(1680.0, 0.12, gapSec = 0.05),
            Note(1910.0, 0.28)
        )
        WorkoutCues.SoundType.TO_WALK -> listOf(
            Note(1910.0, 0.12, gapSec = 0.05),
            Note(1680.0, 0.12, gapSec = 0.05),
            Note(1375.0, 0.32)
        )
        WorkoutCues.SoundType.COMPLETE -> listOf(
            Note(1450.0, 0.12, gapSec = 0.05),
            Note(1680.0, 0.12, gapSec = 0.05),
            Note(1910.0, 0.12, gapSec = 0.05),
            Note(2175.0, 0.48)
        )
    }

    private fun render(notes: List<Note>): ShortArray {
        val toneLen = notes.map { (it.durSec * SAMPLE_RATE).toInt() }
        val gapLen = notes.map { (it.gapSec * SAMPLE_RATE).toInt() }
        val buf = ShortArray(toneLen.indices.sumOf { toneLen[it] + gapLen[it] })
        var offset = 0
        for ((index, note) in notes.withIndex()) {
            val n = toneLen[index]
            val attack = (SAMPLE_RATE * 0.012).toInt().coerceAtMost(n / 2)
            val release = (SAMPLE_RATE * 0.045).toInt().coerceAtMost(n / 2)
            var phase = 0.0
            for (i in 0 until n) {
                val t = i.toDouble() / SAMPLE_RATE
                val instantaneous = note.freq * (1.0 + WARBLE_DEPTH * sin(2 * PI * WARBLE_HZ * t))
                phase += 2 * PI * instantaneous / SAMPLE_RATE
                val sample = (sin(phase) + HARMONIC_2 * sin(2 * phase) + HARMONIC_3 * sin(3 * phase)) /
                    (1.0 + HARMONIC_2 + HARMONIC_3)
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
            offset += n + gapLen[index]
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
