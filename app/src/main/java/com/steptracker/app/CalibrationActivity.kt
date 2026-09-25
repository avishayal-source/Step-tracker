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
                tvStride.text =
                    "Measured:  ${"%.3f".format(computedStride)} m per detected step\n" +
                    "Detected impacts:  $stepCount  (~$footfalls footfalls)\n" +
                    "Distance used:  ${"%.1f".format(distUsed)} m\n\n" +
                    "Previously saved:  ${"%.3f".format(current)} m/step" +
                    heightNote
            }
        }
    }

    private fun expectedStrideNote(): String {
        val h = userPrefs.heightCm
        if (h < 120f) return ""
        val exp = if (calibratingJog)
            UserPrefs.expectedRunStrideM(h) else UserPrefs.expectedWalkStrideM(h)
        // Compared against the walking stride, not the measurement: the app registers about
        // two peaks per stride, so a healthy result lands near half the anatomical figure.
        val ratio = computedStride / exp
        val flag = when {
            ratio < 0.35 -> "\n\n⚠ Much shorter than expected for ${h.toInt()} cm " +
                "(stride ~${"%.2f".format(exp)} m). Retry outdoors on a longer straight path."
            // ~1.0× anatomical stride usually means ~1 impact per footfall (common when
            // holding the phone). Pocket carry is ~0.5× and matches how Tracking counts.
            ratio > 0.90 -> "\n\n⚠ ${"%.2f".format(computedStride)} m looks like a full stride " +
                "(~${"%.2f".format(exp)} m for ${h.toInt()} cm). That often happens when the " +
                "phone is in your hand. Recalibrate with the phone in your pocket — Tracking " +
                "expects about half a stride per detected impact."
            else -> "\n\nYour stride at ${h.toInt()} cm is ~${"%.2f".format(exp)} m; " +
                "the app measures metres per detected step, so about half that " +
                "(~${"%.2f".format(exp / UserPrefs.PEAKS_PER_STRIDE)} m) is normal for pocket carry."
        }
        return flag
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
        // These bounds only reject nonsense (a lost GPS fix, a pocketed phone). They are
        // deliberately not anatomical stride ranges: the counter registers about two peaks
        // per stride when walking, so a correct result is near half a person's stride, and
        // rejecting that left users unable to calibrate at all.
        val minOk = if (calibratingJog) 0.35 else 0.25
        val maxOk = if (calibratingJog) 2.00 else 1.20
        if (computedStride < minOk || computedStride > maxOk) {
            Toast.makeText(this,
                "Result (${"%.2f".format(computedStride)} m) is outside the usable " +
                    "${"%.2f".format(minOk)}–${"%.2f".format(maxOk)} m range. " +
                    "Retry outdoors with a longer straight path.",
                Toast.LENGTH_LONG).show()
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
