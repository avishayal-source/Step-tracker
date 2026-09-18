package com.steptracker.wear.workout

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/** Rising whistle + pulse = run; falling = walk. Vibration is the reliable Wear cue. */
object WorkoutCues {

    enum class SoundType { TO_WALK, TO_RUN, COMPLETE }

    fun play(context: Context, type: SoundType) {
        vibrate(context, type)
        AudioCues.play(context, type)
    }

    fun forPeriodType(type: String): SoundType = when (type) {
        "RUNNING", "JOGGING" -> SoundType.TO_RUN
        else -> SoundType.TO_WALK
    }

    private fun vibrate(context: Context, type: SoundType) {
        val vibrator = vibrator(context) ?: return
        val (timings, amps) = when (type) {
            SoundType.TO_RUN -> longArrayOf(0, 90, 70, 90, 70, 280) to
                intArrayOf(0, 200, 0, 220, 0, 255)
            SoundType.TO_WALK -> longArrayOf(0, 260, 80, 90, 70, 90) to
                intArrayOf(0, 255, 0, 180, 0, 160)
            SoundType.COMPLETE -> longArrayOf(0, 90, 60, 90, 60, 90, 60, 320) to
                intArrayOf(0, 180, 0, 200, 0, 220, 0, 255)
        }
        try {
            vibrator.vibrate(VibrationEffect.createWaveform(timings, amps, -1))
        } catch (_: Exception) {
            try {
                vibrator.vibrate(VibrationEffect.createWaveform(timings, -1))
            } catch (_: Exception) { }
        }
    }

    private fun vibrator(context: Context): Vibrator? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    } catch (_: Exception) {
        null
    }
}
