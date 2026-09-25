package com.steptracker.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.*
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.google.android.material.button.MaterialButton

/**
 * Live step length calibration wizard.
 *
 * Hardening (2026-08):
 * - Higher peak threshold + longer min step gap to cut double-counted steps
 *   (stored strides ~0.41 / 0.73 m were ~½ of norms for a 1.85 m adult).
 * - Require meaningful GPS distance (≥20 m) before accepting GPS stride.
 * - Stricter accept ranges; optional height for anthropometric cross-check.
 */
class CalibrationActivity : AppCompatActivity(), SensorEventListener {

    private enum class Phase { SELECT, COUNTING, RESULT }

    private lateinit var tvTitle: TextView
    private lateinit var tvInstructions: TextView
    private lateinit var tvStepCount: TextView
    private lateinit var tvDistance: TextView
    private lateinit var tvStride: TextView
    private lateinit var tvGpsStatus: TextView
    private lateinit var etHeight: EditText
    private lateinit var btnWalk: MaterialButton
    private lateinit var btnJog: MaterialButton
    private lateinit var btnStart: MaterialButton
    private lateinit var btnStop: MaterialButton
    private lateinit var btnAccept: MaterialButton
    private lateinit var btnRetry: MaterialButton
    private lateinit var layoutSelect: View
    private lateinit var layoutCounting: View
    private lateinit var layoutResult: View

    private lateinit var sensorManager: SensorManager
    private var locationManager: LocationManager? = null
    private lateinit var userPrefs: UserPrefs

    // Must be the tracker's counter, not a stricter one: we are measuring metres per
    // step *as the tracker counts steps*, so a different counter here silently scales
    // every distance the app reports.
    private val peaks = StepPeakDetector()

    private var calibratingJog = false
    private var phase = Phase.SELECT
    private var counting = false
    private var stepCount = 0
    private var gpsDistanceM = 0.0
    private var lastLocation: Location? = null
    private var computedStride = 0.0
    /** Detected peaks (tracker units). UI also shows ~footfalls ≈ peaks/2. */
    private val MIN_STEPS = 40
    private val MIN_GPS_DIST_M = 20.0

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(loc: Location) {
            if (!counting) return
            if (loc.hasAccuracy() && loc.accuracy > 15f) return
            lastLocation?.let { prev ->
                val delta = prev.distanceTo(loc).toDouble()
                if (delta in 0.5..40.0) {
                    gpsDistanceM += delta
                    runOnUiThread { updateCountingUI() }
                }
            }
            lastLocation = loc
            runOnUiThread {
                val acc = if (loc.hasAccuracy()) " (±${loc.accuracy.toInt()}m)" else ""
                tvGpsStatus.text = "📍 GPS fix$acc  —  ${gpsDistanceM.toInt()} m accumulated"
            }
        }
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(p: String?, s: Int, e: android.os.Bundle?) {}
        override fun onProviderEnabled(p: String) {}
        override fun onProviderDisabled(p: String) {}
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_calibration)
        applyRootSystemBarInsets()

        sensorManager   = getSystemService(SENSOR_SERVICE) as SensorManager
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        userPrefs       = UserPrefs(this)

        tvTitle        = findViewById(R.id.tvCalibTitle)
        tvInstructions = findViewById(R.id.tvCalibInstructions)
        tvStepCount    = findViewById(R.id.tvCalibStepCount)
        tvDistance     = findViewById(R.id.tvCalibDistance)
        tvStride       = findViewById(R.id.tvCalibStride)
        tvGpsStatus    = findViewById(R.id.tvCalibGpsStatus)
        etHeight       = findViewById(R.id.etCalibHeight)
        btnWalk        = findViewById(R.id.btnCalibWalk)
        btnJog         = findViewById(R.id.btnCalibJog)
        btnStart       = findViewById(R.id.btnCalibStart)
        btnStop        = findViewById(R.id.btnCalibStop)
        btnAccept      = findViewById(R.id.btnCalibAccept)
        btnRetry       = findViewById(R.id.btnCalibRetry)
        layoutSelect   = findViewById(R.id.layoutCalibSelect)
        layoutCounting = findViewById(R.id.layoutCalibCounting)
        layoutResult   = findViewById(R.id.layoutCalibResult)

        if (userPrefs.heightCm > 0f) {
            etHeight.setText("%.0f".format(userPrefs.heightCm))
        }

        btnWalk.setOnClickListener   { if (saveHeightOrWarn()) { calibratingJog = false; showPhase(Phase.COUNTING) } }
        btnJog.setOnClickListener    { if (saveHeightOrWarn()) { calibratingJog = true;  showPhase(Phase.COUNTING) } }
        btnStart.setOnClickListener  { startCounting() }
        btnStop.setOnClickListener   { stopCounting() }
        btnAccept.setOnClickListener { acceptResult() }
        btnRetry.setOnClickListener  { showPhase(Phase.COUNTING) }
        findViewById<View>(R.id.btnCalibBack).setOnClickListener { finish() }

        showPhase(Phase.SELECT)
    }

    /** Height is optional but strongly recommended; empty is allowed with a toast. */
    private fun saveHeightOrWarn(): Boolean {
        val h = etHeight.text.toString().toFloatOrNull()
        if (h != null) {
            if (h !in 120f..230f) {
                Toast.makeText(this, "Enter a height between 120 and 230 cm", Toast.LENGTH_LONG).show()
                return false
            }
            userPrefs.heightCm = h
        } else {
            Toast.makeText(
                this,
                "Tip: enter your height so we can reject unrealistic step lengths",
                Toast.LENGTH_LONG
            ).show()
        }
        return true
    }

    override fun onDestroy() {
        counting = false
        super.onDestroy()
        sensorManager.unregisterListener(this)
        try { locationManager?.removeUpdates(locationListener) } catch (_: Exception) {}
    }

    private fun showPhase(p: Phase) {
        counting = false
        phase = p
        sensorManager.unregisterListener(this)
        try { locationManager?.removeUpdates(locationListener) } catch (_: Exception) {}

        layoutSelect.visibility   = if (p == Phase.SELECT)   View.VISIBLE else View.GONE
        layoutCounting.visibility = if (p == Phase.COUNTING) View.VISIBLE else View.GONE
        layoutResult.visibility   = if (p == Phase.RESULT)   View.VISIBLE else View.GONE

        when (p) {
            Phase.SELECT -> {
                tvTitle.text = "Step Length Calibration"
            }
            Phase.COUNTING -> {
                stepCount = 0; gpsDistanceM = 0.0; lastLocation = null
                computedStride = 0.0; peaks.reset()
                val verb = if (calibratingJog) "jog" else "walk"
                val Verb = if (calibratingJog) "Jog" else "Walk"
                tvTitle.text = "Calibrate $Verb Step Length"
                tvInstructions.text =
                    "Tap Start, then $verb in a straight line on flat open ground for about " +
                    "40–50 footfalls (aim for 40–80 m). Tap Stop when done.\n\n" +
                    "Keep the phone in your pocket (or the same place you carry it on runs). " +
                    "Holding it in your hand changes how impacts are counted.\n\n" +
                    "The counter shows detected impacts — usually about twice your footfalls " +
                    "(so ~40 footfalls may read near 80). That is expected.\n\n" +
                    "GPS needs ~${MIN_GPS_DIST_M.toInt()} m of good signal. If GPS is weak, " +
                    "${verb} a known marked distance instead."
                tvStepCount.text = "0 impacts (~0 footfalls)"
                tvDistance.text  = "—"
                tvGpsStatus.text = "GPS: waiting for fix…"
                btnStart.visibility = View.VISIBLE
                btnStop.visibility  = View.GONE
                btnStop.isEnabled   = false
            }
            Phase.RESULT -> {
                val Verb = if (calibratingJog) "Jog" else "Walk"
                tvTitle.text = "Result — $Verb Step Length"
                val current = if (calibratingJog) userPrefs.runStrideM else userPrefs.walkStrideM
                val distUsed = if (gpsDistanceM >= MIN_GPS_DIST_M) gpsDistanceM else computedStride * stepCount
                val heightNote = expectedStrideNote()
                val footfalls = (stepCount / UserPrefs.PEAKS_PER_STRIDE).toInt()
                val bounds = acceptablePeakLengthRange()
                val inRange = computedStride in bounds.minOk..bounds.maxOk
                btnAccept.isEnabled = inRange
                btnAccept.alpha = if (inRange) 1f else 0.45f
                tvStride.text =
                    "Measured:  ${"%.3f".format(computedStride)} m per detected step\n" +
                    "Detected impacts:  $stepCount  (~$footfalls footfalls)\n" +
                    "Distance used:  ${"%.1f".format(distUsed)} m\n" +
                    "Acceptable range:  ${"%.2f".format(bounds.minOk)}–${"%.2f".format(bounds.maxOk)} m\n\n" +
                    "Previously saved:  ${"%.3f".format(current)} m/step" +
                    heightNote
            }
        }
    }

    private fun expectedStrideNote(): String {
        val h = userPrefs.heightCm
        val bounds = acceptablePeakLengthRange()
        val ideal = bounds.ideal
        if (h < 120f) {
            return "\n\nFor walking, a usable pocket result is usually about " +
                "${"%.2f".format(bounds.minOk)}–${"%.2f".format(bounds.maxOk)} m per detected impact."
        }
        val exp = if (calibratingJog)
            UserPrefs.expectedRunStrideM(h) else UserPrefs.expectedWalkStrideM(h)
        val ratioToFull = computedStride / exp
        val ratioToIdeal = if (ideal > 0) computedStride / ideal else 0.0
        return when {
            computedStride > bounds.maxOk ->
                "\n\n⚠ ${"%.2f".format(computedStride)} m is too long for a ${h.toInt()} cm " +
                    "${if (calibratingJog) "jog" else "walk"}.\n" +
                    "A full stride is only ~${"%.2f".format(exp)} m; Tracking needs about half of that " +
                    "per impact (~${"%.2f".format(ideal)} m in a pocket).\n" +
                    "This usually means missed impacts (hand carry) or noisy GPS — do not save. " +
                    "Retry with the phone in your pocket on a straight outdoor path."
            ratioToFull > 0.75 ->
                "\n\n⚠ Close to a full stride (~${"%.2f".format(exp)} m). That often means the phone " +
                    "was in your hand and under-counted impacts. Prefer pocket carry " +
                    "(~${"%.2f".format(ideal)} m)."
            ratioToIdeal < 0.70 ->
                "\n\n⚠ Shorter than expected for pocket carry (~${"%.2f".format(ideal)} m). " +
                    "Retry outdoors on a longer straight path."
            else ->
                "\n\nLooks plausible for pocket carry. At ${h.toInt()} cm a full stride is " +
                    "~${"%.2f".format(exp)} m; ~${"%.2f".format(ideal)} m per impact is the target."
        }
    }

    /**
     * Acceptable metres-per-detected-impact. With height, cap just below a full
     * anatomical stride so values like 0.94 m for a 1.75 m walker are rejected —
     * that is longer than a real step and cannot be a valid pocket (or hand) walk length.
     */
    private data class PeakLengthBounds(val minOk: Double, val maxOk: Double, val ideal: Double)

    private fun acceptablePeakLengthRange(): PeakLengthBounds {
        val h = userPrefs.heightCm
        if (calibratingJog) {
            if (h >= 120f) {
                val full = UserPrefs.expectedRunStrideM(h)
                val ideal = full / UserPrefs.PEAKS_PER_STRIDE
                return PeakLengthBounds(
                    minOk = (ideal * 0.55).coerceAtLeast(0.30),
                    // Allow slightly over half-stride; never above a full stride.
                    maxOk = (ideal * 1.55).coerceAtMost(full * 0.95),
                    ideal = ideal
                )
            }
            return PeakLengthBounds(0.35, 0.95, 0.60)
        }
        if (h >= 120f) {
            val full = UserPrefs.expectedWalkStrideM(h)
            val ideal = full / UserPrefs.PEAKS_PER_STRIDE
            return PeakLengthBounds(
                minOk = (ideal * 0.55).coerceAtLeast(0.22),
                // e.g. 1.75 m → full≈0.73, ideal≈0.36, max≈0.56 — rejects 0.94
                maxOk = (ideal * 1.55).coerceAtMost(full * 0.85),
                ideal = ideal
            )
        }
        // No height: still reject "almost a metre" walk lengths.
        return PeakLengthBounds(0.25, 0.65, 0.40)
    }

    private fun startCounting() {
        counting = false
        stepCount = 0; gpsDistanceM = 0.0; lastLocation = null; peaks.reset()
        btnStart.visibility = View.GONE
        btnStop.visibility  = View.VISIBLE
        btnStop.isEnabled   = false

        val accel = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        sensorManager.registerListener(this, accel, SensorManager.SENSOR_DELAY_GAME)
        counting = true

        val hasFine = ActivityCompat.checkSelfPermission(this,
            Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!hasFine) {
            ActivityCompat.requestPermissions(this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION), 200)
            tvGpsStatus.text = "Requesting GPS permission…"
        } else {
            startGpsUpdates()
        }
    }

    private fun startGpsUpdates() {
        val lm = locationManager ?: return
        try {
            val providers = lm.getProviders(true)
            var started = false
            val ordered = providers.sortedByDescending {
                when (it) {
                    LocationManager.GPS_PROVIDER     -> 2
                    LocationManager.NETWORK_PROVIDER -> 1
                    else                             -> 0
                }
            }
            for (provider in ordered) {
                try {
                    lm.requestLocationUpdates(provider, 500L, 0.5f,
                        locationListener, Looper.getMainLooper())
                    started = true
                    break
                } catch (_: Exception) {}
            }
            tvGpsStatus.text = if (started) "📍 GPS acquiring fix…" else "⚠ No location provider — use known distance"
        } catch (_: Exception) {
            tvGpsStatus.text = "⚠ Could not start GPS"
        }
    }

    override fun onRequestPermissionsResult(rc: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(rc, perms, results)
        if (rc == 200) {
            if (results.isNotEmpty() && results[0] == PackageManager.PERMISSION_GRANTED) startGpsUpdates()
            else tvGpsStatus.text = "⚠ GPS denied — use a known distance"
        }
    }

    private fun stopCounting() {
        counting = false
        sensorManager.unregisterListener(this)
        try { locationManager?.removeUpdates(locationListener) } catch (_: Exception) {}
        btnStart.visibility = View.VISIBLE
        btnStop.visibility  = View.GONE

        val finalSteps = stepCount
        val finalDist = gpsDistanceM

        if (finalSteps < MIN_STEPS) {
            Toast.makeText(
                this,
                "Need at least $MIN_STEPS detected impacts (~${MIN_STEPS / 2} footfalls). Got $finalSteps — keep going!",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        if (finalDist >= MIN_GPS_DIST_M) {
            computedStride = finalDist / finalSteps
            showPhase(Phase.RESULT)
        } else {
            askManualDistance(finalSteps, finalDist)
        }
    }

    private fun askManualDistance(finalSteps: Int = stepCount, finalDist: Double = gpsDistanceM) {
        val verb = if (calibratingJog) "jogged" else "walked"
        val et = EditText(this).apply {
            hint = "e.g. 50.0"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                        android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            setPadding(60, 20, 60, 20)
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("Enter distance $verb (metres)")
            .setMessage(
                "GPS only recorded ${"%.1f".format(finalDist)} m " +
                    "(need ≥${MIN_GPS_DIST_M.toInt()} m outdoors).\n\n" +
                    "How many metres did you $verb?\nDetected impacts: $finalSteps " +
                    "(~${(finalSteps / UserPrefs.PEAKS_PER_STRIDE).toInt()} footfalls)"
            )
            .setView(et)
            .setPositiveButton("Calculate") { _, _ ->
                val dist = et.text.toString().toDoubleOrNull()
                if (dist != null && dist >= 15.0) {
                    computedStride = dist / finalSteps
                    showPhase(Phase.RESULT)
                } else {
                    Toast.makeText(this, "Enter at least 15 metres for a usable average",
                        Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun acceptResult() {
        if (computedStride <= 0.0) return
        val bounds = acceptablePeakLengthRange()
        if (computedStride < bounds.minOk || computedStride > bounds.maxOk) {
            Toast.makeText(
                this,
                "Cannot save ${"%.2f".format(computedStride)} m — outside " +
                    "${"%.2f".format(bounds.minOk)}–${"%.2f".format(bounds.maxOk)} m. " +
                    "Retry with the phone in your pocket.",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        if (calibratingJog) {
            userPrefs.runStrideM = computedStride
            userPrefs.runCalibrated = true
        } else {
            userPrefs.walkStrideM = computedStride
            userPrefs.walkCalibrated = true
        }
        val verb = if (calibratingJog) "jog" else "walk"
        Toast.makeText(this, "✓ Saved $verb step length: ${"%.3f".format(computedStride)} m",
            Toast.LENGTH_LONG).show()
        showPhase(Phase.SELECT)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onSensorChanged(event: SensorEvent) {
        if (!counting) return
        if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) return
        peaks.onSample(
            event.values[0], event.values[1], event.values[2], System.currentTimeMillis()
        ) ?: return
        stepCount++
        runOnUiThread { updateCountingUI() }
    }

    private fun updateCountingUI() {
        val footfalls = (stepCount / UserPrefs.PEAKS_PER_STRIDE).toInt()
        tvStepCount.text = "$stepCount impacts (~$footfalls footfalls)"
        tvDistance.text  = if (gpsDistanceM >= 0.5)
            "${"%.1f".format(gpsDistanceM)} m (GPS)"
        else
            "Waiting for GPS fix…"
        btnStop.isEnabled = (stepCount >= MIN_STEPS)
    }
}
