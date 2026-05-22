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
import kotlin.math.sqrt

/**
 * Live step length calibration wizard.
 *
 * Bugs fixed vs previous version:
 * 1. event.timestamp is nanoseconds-since-boot — used wall clock instead
 *    (System.currentTimeMillis()) for MIN_STEP_MS comparisons.
 * 2. GPS fallback threshold reduced to 2m so short calibration walks
 *    (indoors/covered area) still use GPS rather than prompting manually.
 * 3. Instruction text now correctly says "jog" when calibrating jog.
 * 4. MIN_STEPS raised to 30 for more accurate averaging.
 * 5. GPS accuracy filter added — reject fixes worse than 20m.
 */
class CalibrationActivity : AppCompatActivity(), SensorEventListener {

    private enum class Phase { SELECT, COUNTING, RESULT }

    // ── UI ────────────────────────────────────────────────────────────────────
    private lateinit var tvTitle: TextView
    private lateinit var tvInstructions: TextView
    private lateinit var tvStepCount: TextView
    private lateinit var tvDistance: TextView
    private lateinit var tvStride: TextView
    private lateinit var tvGpsStatus: TextView
    private lateinit var btnWalk: MaterialButton
    private lateinit var btnJog: MaterialButton
    private lateinit var btnStart: MaterialButton
    private lateinit var btnStop: MaterialButton
    private lateinit var btnAccept: MaterialButton
    private lateinit var btnRetry: MaterialButton
    private lateinit var layoutSelect: View
    private lateinit var layoutCounting: View
    private lateinit var layoutResult: View

    // ── Sensors ───────────────────────────────────────────────────────────────
    private lateinit var sensorManager: SensorManager
    private var locationManager: LocationManager? = null
    private lateinit var userPrefs: UserPrefs

    // Accelerometer step detection
    private val GRAVITY_ALPHA  = 0.80f
    private val PEAK_THRESHOLD = 1.5f
    private val MIN_STEP_MS    = 220L
    private var gx = 0f; private var gy = 0f; private var gz = 9.81f
    private var lastMag = 0f
    private var rising = false
    // FIX #1: use wall-clock ms, not sensor boot timestamp
    private var lastStepWallMs = 0L

    // ── State ─────────────────────────────────────────────────────────────────
    private var calibratingJog = false
    private var phase = Phase.SELECT
    private var stepCount = 0
    private var gpsDistanceM = 0.0
    private var lastLocation: Location? = null
    private var computedStride = 0.0
    // FIX #4: more steps = better average
    private val MIN_STEPS = 30

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(loc: Location) {
            // FIX #5: reject inaccurate fixes
            if (loc.hasAccuracy() && loc.accuracy > 20f) return
            lastLocation?.let { prev ->
                val delta = prev.distanceTo(loc).toDouble()
                if (delta in 0.3..50.0) {
                    gpsDistanceM += delta
                    runOnUiThread { updateCountingUI() }
                }
            }
            lastLocation = loc
            // Update GPS status once we have a real fix
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

    // ─────────────────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_calibration)

        sensorManager   = getSystemService(SENSOR_SERVICE) as SensorManager
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        userPrefs       = UserPrefs(this)

        tvTitle        = findViewById(R.id.tvCalibTitle)
        tvInstructions = findViewById(R.id.tvCalibInstructions)
        tvStepCount    = findViewById(R.id.tvCalibStepCount)
        tvDistance     = findViewById(R.id.tvCalibDistance)
        tvStride       = findViewById(R.id.tvCalibStride)
        tvGpsStatus    = findViewById(R.id.tvCalibGpsStatus)
        btnWalk        = findViewById(R.id.btnCalibWalk)
        btnJog         = findViewById(R.id.btnCalibJog)
        btnStart       = findViewById(R.id.btnCalibStart)
        btnStop        = findViewById(R.id.btnCalibStop)
        btnAccept      = findViewById(R.id.btnCalibAccept)
        btnRetry       = findViewById(R.id.btnCalibRetry)
        layoutSelect   = findViewById(R.id.layoutCalibSelect)
        layoutCounting = findViewById(R.id.layoutCalibCounting)
        layoutResult   = findViewById(R.id.layoutCalibResult)

        btnWalk.setOnClickListener   { calibratingJog = false; showPhase(Phase.COUNTING) }
        btnJog.setOnClickListener    { calibratingJog = true;  showPhase(Phase.COUNTING) }
        btnStart.setOnClickListener  { startCounting() }
        btnStop.setOnClickListener   { stopCounting() }
        btnAccept.setOnClickListener { acceptResult() }
        btnRetry.setOnClickListener  { showPhase(Phase.COUNTING) }
        findViewById<View>(R.id.btnCalibBack).setOnClickListener { finish() }

        showPhase(Phase.SELECT)
    }

    override fun onDestroy() {
        super.onDestroy()
        sensorManager.unregisterListener(this)
        try { locationManager?.removeUpdates(locationListener) } catch (_: Exception) {}
    }

    // ── Phase management ──────────────────────────────────────────────────────

    private fun showPhase(p: Phase) {
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
                computedStride = 0.0; lastStepWallMs = 0L
                // FIX #3: correct verb for jog vs walk
                val verb = if (calibratingJog) "jog" else "walk"
                val Verb = if (calibratingJog) "Jog" else "Walk"
                tvTitle.text = "Calibrate $Verb Step Length"
                tvInstructions.text =
                    "Tap Start, then $verb in a straight line on flat ground for at " +
                    "least $MIN_STEPS steps. Tap Stop when done.\n\n" +
                    "GPS measures the distance automatically. If GPS is unavailable, " +
                    "${verb} along a known distance (e.g. a marked 30m path)."
                tvStepCount.text = "0 steps"
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
                tvStride.text =
                    "Measured:  ${"%.3f".format(computedStride)} m/step\n" +
                    "Steps taken:  $stepCount\n" +
                    "Distance used:  ${"%.2f".format(if (gpsDistanceM >= 2.0) gpsDistanceM else computedStride * stepCount)} m\n\n" +
                    "Previously saved:  ${"%.3f".format(current)} m/step"
            }
        }
    }

    // ── Counting phase ────────────────────────────────────────────────────────

    private fun startCounting() {
        stepCount = 0; gpsDistanceM = 0.0; lastLocation = null; lastStepWallMs = 0L
        btnStart.visibility = View.GONE
        btnStop.visibility  = View.VISIBLE
        btnStop.isEnabled   = false

        val accel = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        sensorManager.registerListener(this, accel, SensorManager.SENSOR_DELAY_GAME)

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
            val providers = lm.getProviders(true)   // all currently-enabled providers
            var started = false
            // Prefer GPS, then fall back to anything available
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
                    break   // use best provider only
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
        sensorManager.unregisterListener(this)
        try { locationManager?.removeUpdates(locationListener) } catch (_: Exception) {}
        btnStart.visibility = View.VISIBLE
        btnStop.visibility  = View.GONE

        if (stepCount < MIN_STEPS) {
            Toast.makeText(this, "Need at least $MIN_STEPS steps. Got $stepCount — keep going!",
                Toast.LENGTH_SHORT).show()
            return
        }

        // FIX #2: use GPS if we have at least 2m (not 5m as before)
        if (gpsDistanceM >= 2.0) {
            computedStride = gpsDistanceM / stepCount
            showPhase(Phase.RESULT)
        } else {
            askManualDistance()
        }
    }

    private fun askManualDistance() {
        val verb = if (calibratingJog) "jogged" else "walked"
        val et = EditText(this).apply {
            hint = "e.g. 25.0"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                        android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            setPadding(60, 20, 60, 20)
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("Enter distance $verb (metres)")
            .setMessage("GPS wasn't available. How many metres did you $verb?\n" +
                        "Steps counted: $stepCount")
            .setView(et)
            .setPositiveButton("Calculate") { _, _ ->
                val dist = et.text.toString().toDoubleOrNull()
                if (dist != null && dist > 1.0) {
                    computedStride = dist / stepCount
                    showPhase(Phase.RESULT)
                } else {
                    Toast.makeText(this, "Enter a valid distance in metres",
                        Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun acceptResult() {
        if (computedStride <= 0.0) return
        // Sanity-check the result
        val minOk = if (calibratingJog) 0.5 else 0.4
        val maxOk = if (calibratingJog) 2.0 else 1.2
        if (computedStride < minOk || computedStride > maxOk) {
            Toast.makeText(this,
                "Result (${"%.2f".format(computedStride)}m) looks unusual. " +
                "Try again with more steps.", Toast.LENGTH_LONG).show()
            return
        }
        if (calibratingJog) userPrefs.runStrideM  = computedStride
        else                userPrefs.walkStrideM = computedStride
        userPrefs.isCalibrated = true
        val verb = if (calibratingJog) "jog" else "walk"
        Toast.makeText(this, "✓ Saved $verb step length: ${"%.3f".format(computedStride)} m",
            Toast.LENGTH_LONG).show()
        showPhase(Phase.SELECT)
    }

    // ── SensorEventListener ───────────────────────────────────────────────────

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) return
        val ax = event.values[0]; val ay = event.values[1]; val az = event.values[2]
        gx = GRAVITY_ALPHA * gx + (1 - GRAVITY_ALPHA) * ax
        gy = GRAVITY_ALPHA * gy + (1 - GRAVITY_ALPHA) * ay
        gz = GRAVITY_ALPHA * gz + (1 - GRAVITY_ALPHA) * az
        val mag = sqrt(((ax-gx)*(ax-gx) + (ay-gy)*(ay-gy) + (az-gz)*(az-gz)).toDouble()).toFloat()

        if (mag > lastMag) { rising = true }
        else if (rising && lastMag > PEAK_THRESHOLD) {
            rising = false
            // FIX #1: wall-clock time, NOT sensor boot timestamp
            val nowMs = System.currentTimeMillis()
            if (nowMs - lastStepWallMs >= MIN_STEP_MS) {
                lastStepWallMs = nowMs
                stepCount++
                runOnUiThread { updateCountingUI() }
            }
        } else { rising = false }
        lastMag = mag
    }

    private fun updateCountingUI() {
        tvStepCount.text = "$stepCount steps"
        tvDistance.text  = if (gpsDistanceM >= 0.3)
            "${"%.1f".format(gpsDistanceM)} m (GPS)"
        else
            "Waiting for GPS fix…"
        btnStop.isEnabled = (stepCount >= MIN_STEPS)
    }
}
