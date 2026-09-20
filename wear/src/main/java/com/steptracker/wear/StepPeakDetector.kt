package com.steptracker.wear

import kotlin.math.sqrt

/** Accelerometer peak → step detection (same thresholds as the phone tracker). */
class StepPeakDetector {

    data class Step(val wallMs: Long, val intervalMs: Long, val peakMag: Float)

    private var gx = 0f; private var gy = 0f; private var gz = 9.81f
    private var lastMag = 0f
    private var rising = false
    private var lastStepWallMs = 0L

    fun onSample(ax: Float, ay: Float, az: Float, wallMs: Long): Step? {
        gx = GRAVITY_ALPHA * gx + (1 - GRAVITY_ALPHA) * ax
        gy = GRAVITY_ALPHA * gy + (1 - GRAVITY_ALPHA) * ay
        gz = GRAVITY_ALPHA * gz + (1 - GRAVITY_ALPHA) * az
        val mag = sqrt(
            ((ax - gx) * (ax - gx) + (ay - gy) * (ay - gy) + (az - gz) * (az - gz)).toDouble()
        ).toFloat()

        var step: Step? = null
        if (mag > lastMag) {
            rising = true
        } else if (rising && lastMag > STEP_THRESHOLD) {
            rising = false
            val gap = wallMs - lastStepWallMs
            if (gap >= MIN_STEP_MS) {
                step = Step(wallMs, gap, lastMag)
                lastStepWallMs = wallMs
            }
        } else {
            rising = false
        }
        lastMag = mag
        return step
    }

    fun reset() {
        gx = 0f; gy = 0f; gz = 9.81f
        lastMag = 0f; rising = false; lastStepWallMs = 0L
    }

    private companion object {
        const val GRAVITY_ALPHA = 0.80f
        const val STEP_THRESHOLD = 1.8f
        const val MIN_STEP_MS = 280L
    }
}
