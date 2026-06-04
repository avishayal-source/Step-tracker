package com.steptracker.app

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.location.*
import android.os.*
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat

class StepTrackerService : Service(), StepDetector.StepListener {

    companion object {
        const val CHANNEL_ID           = "step_tracker_channel"
        const val NOTIFICATION_ID      = 1
        const val ACTION_START         = "START"
        const val ACTION_STOP          = "STOP"
        const val EXTRA_INITIAL_TYPE   = "initial_type"
    }

    inner class LocalBinder : Binder() { fun getService() = this@StepTrackerService }

    private val binder = LocalBinder()
    private lateinit var sensorManager: SensorManager
    private lateinit var stepDetector: StepDetector
    private lateinit var userPrefs: UserPrefs
    private lateinit var workoutHistory: WorkoutHistory
    private lateinit var wakeLock: PowerManager.WakeLock

    // ── GPS ───────────────────────────────────────────────────────────────────
    private var locationManager: LocationManager? = null
    // Only GPS_PROVIDER fixes are used for distance accumulation.
    // Network/fused fixes can be hundreds of metres off and create phantom distance
    // when GPS eventually acquires a real fix from a different position.
    private var lastGpsLocation: Location? = null
    var gpsAvailable = false; private set

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(loc: Location) {
            // Only accumulate distance from GPS hardware fixes
            if (loc.provider != LocationManager.GPS_PROVIDER) return

            // Require good accuracy
            if (loc.hasAccuracy() && loc.accuracy > 15f) return

            gpsAvailable = true

            val prev = lastGpsLocation
            lastGpsLocation = loc

            if (prev == null) return   // first fix — no reference point yet

            val delta       = prev.distanceTo(loc).toDouble()
            val timeDeltaSec = ((loc.time - prev.time) / 1000.0).coerceAtLeast(0.001)
            val impliedSpeed = delta / timeDeltaSec   // m/s

            // Sanity: reject fixes that imply > 29 km/h (GPS bounce / multipath)
            if (impliedSpeed > 8.0) return
            if (delta < 0.5 || delta > 200.0) return

            currentPeriod?.gpsDistanceM = (currentPeriod?.gpsDistanceM ?: 0.0) + delta
            recomputeTotals()
            onUpdateListener?.invoke()
        }
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(p: String?, s: Int, e: Bundle?) {}
        override fun onProviderEnabled(p: String)  { if (p == LocationManager.GPS_PROVIDER) gpsAvailable = true }
        override fun onProviderDisabled(p: String) { if (p == LocationManager.GPS_PROVIDER) gpsAvailable = false }
    }

    // ── Tracking state ────────────────────────────────────────────────────────
    var isTracking = false;   private set
    var totalSteps = 0;       private set
    val activityPeriods       = mutableListOf<ActivityPeriod>()
    var currentPeriod: ActivityPeriod? = null; private set
    var walkSteps = 0;  var runSteps = 0
    var walkDistM = 0.0; var runDistM = 0.0
    var sessionStartMs = 0L;  private set

    // FIX #5: guard so history is saved exactly once per session
    private var historySaved = false

    var onUpdateListener: (() -> Unit)? = null

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        sensorManager   = getSystemService(SENSOR_SERVICE) as SensorManager
        userPrefs       = UserPrefs(this)
        workoutHistory  = WorkoutHistory(this)
        stepDetector    = StepDetector(this)
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        @Suppress("DEPRECATION")
        wakeLock = pm.newWakeLock(
            PowerManager.SCREEN_DIM_WAKE_LOCK or PowerManager.ON_AFTER_RELEASE,
            "letsgo:tracking")
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val typeName = intent.getStringExtra(EXTRA_INITIAL_TYPE)
                val initial = typeName?.let {
                    try { ActivityType.valueOf(it) } catch (_: Exception) { null }
                }
                startTracking(initial ?: ActivityType.WALKING)
            }
            ACTION_STOP  -> stopTracking()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent): IBinder = binder

    // ── Public API ────────────────────────────────────────────────────────────

    fun startTracking(initialType: ActivityType = ActivityType.WALKING) {
        if (isTracking) return
        isTracking     = true
        historySaved   = false
        sessionStartMs = System.currentTimeMillis()
        val seed = if (initialType == ActivityType.IDLE) ActivityType.WALKING else initialType
        stepDetector.reset(seed)
        sensorManager.registerListener(stepDetector,
            sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER),
            SensorManager.SENSOR_DELAY_GAME)
        startGps()
        if (!wakeLock.isHeld) wakeLock.acquire(4 * 60 * 60 * 1000L)
        openNewPeriod(seed, System.currentTimeMillis())
        startForeground(NOTIFICATION_ID, buildNotification("Tracking…"))
    }

    fun sessionElapsedMs(): Long =
        if (isTracking) System.currentTimeMillis() - sessionStartMs else 0L

    fun stopTracking() {
        if (!isTracking) return   // FIX #3: idempotent stop
        isTracking = false
        sensorManager.unregisterListener(stepDetector)
        stopGps()
        if (wakeLock.isHeld) wakeLock.release()
        val now = System.currentTimeMillis()
        currentPeriod?.let {
            it.endTime = now
            if (it.endTime <= it.startTime) it.endTime = it.startTime + 1000
        }
        currentPeriod = null
        recomputeTotals()
        // FIX #5: save only once, guarded by historySaved flag
        if (totalSteps > 0 && !historySaved) {
            historySaved = true
            saveWorkoutToHistory()
        }
        updateNotification("Stopped – $totalSteps steps · ${formatDist(walkDistM + runDistM)}")
        onUpdateListener?.invoke()
        stopForeground(STOP_FOREGROUND_DETACH)
    }

    fun resetData() {
        if (isTracking) return
        totalSteps = 0; walkSteps = 0; runSteps = 0
        walkDistM = 0.0; runDistM = 0.0; historySaved = false
        activityPeriods.clear(); currentPeriod = null; lastGpsLocation = null
        onUpdateListener?.invoke()
    }

    // ── History ───────────────────────────────────────────────────────────────

    private fun saveWorkoutToHistory() {
        val walkDurMs = activityPeriods.filter { it.type == ActivityType.WALKING }.sumOf { it.durationMs }
        val runDurMs  = activityPeriods.filter { it.type == ActivityType.RUNNING  }.sumOf { it.durationMs }
        workoutHistory.save(WorkoutRecord(
            dateMs         = sessionStartMs,
            walkSteps      = walkSteps,
            runSteps       = runSteps,
            walkDistM      = walkDistM,
            runDistM       = runDistM,
            walkDurationMs = walkDurMs,
            runDurationMs  = runDurMs
        ))
    }

    private fun startGps() {
        val lm = locationManager ?: return
        val hasFine = ActivityCompat.checkSelfPermission(
            this, android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val hasCoarse = ActivityCompat.checkSelfPermission(
            this, android.Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!hasFine && !hasCoarse) return

        lastGpsLocation = null
        gpsAvailable = false

        // Register with all available providers so the OS can feed us GPS fixes.
        // Distance accumulation only uses GPS_PROVIDER (see locationListener).
        val providers = lm.getProviders(true)
        var registered = false
        for (provider in providers) {
            if (provider == LocationManager.GPS_PROVIDER && !hasFine) continue
            if (provider != LocationManager.GPS_PROVIDER && !hasCoarse && !hasFine) continue
            try {
                lm.requestLocationUpdates(provider, 1000L, 1f, locationListener, Looper.getMainLooper())
                registered = true
            } catch (_: Exception) {}
        }
        if (!registered) gpsAvailable = false
    }

    private fun stopGps() {
        try { locationManager?.removeUpdates(locationListener) } catch (_: Exception) {}
        gpsAvailable = false
    }

    // ── StepDetector callbacks ────────────────────────────────────────────────

    override fun onStep(wallTimeMs: Long, activity: ActivityType) {
        totalSteps++
        currentPeriod?.steps = (currentPeriod?.steps ?: 0) + 1
        recomputeTotals()
        updateNotification(buildStatusText())
        onUpdateListener?.invoke()
    }

    override fun onActivityChanged(newActivity: ActivityType, wallTimeMs: Long) {
        if (!isTracking || newActivity == ActivityType.IDLE) return
        val now = wallTimeMs
        val prev = currentPeriod
        if (prev?.type == newActivity) return

        prev?.let { p ->
            p.endTime = now
            if (p.endTime <= p.startTime) p.endTime = p.startTime + 1000
        }
        if (prev != null && prev.type == ActivityType.IDLE && prev.durationMs < 2000)
            activityPeriods.remove(prev)
        openNewPeriod(newActivity, now)
        recomputeTotals()
        onUpdateListener?.invoke()
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun openNewPeriod(type: ActivityType, startMs: Long) {
        val period = ActivityPeriod(
            type       = type,
            startTime  = startMs,
            walkStride = userPrefs.walkStrideM,
            runStride  = userPrefs.runStrideM)
        activityPeriods.add(period); currentPeriod = period
    }

    private fun recomputeTotals() {
        walkSteps = 0; runSteps = 0; walkDistM = 0.0; runDistM = 0.0
        val all = activityPeriods + listOfNotNull(currentPeriod)
        for (p in all) when (p.type) {
            ActivityType.WALKING -> { walkSteps += p.steps; walkDistM += p.distanceMeters }
            ActivityType.RUNNING -> { runSteps  += p.steps; runDistM  += p.distanceMeters }
            else -> {}
        }
    }

    private fun buildStatusText(): String {
        val act = when (stepDetector.currentActivity) {
            ActivityType.RUNNING -> "🏃 Running"
            ActivityType.WALKING -> "🚶 Walking"
            ActivityType.IDLE    -> "⏸ Idle"
        }
        val gps = if (gpsAvailable) " 📍" else ""
        return "$act · $totalSteps steps · ${formatDist(walkDistM + runDistM)}$gps"
    }

    fun formatDist(m: Double) = if (m >= 1000) "${"%.2f".format(m/1000)} km" else "${m.toInt()} m"

    // ── Notification ──────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val ch = NotificationChannel(CHANNEL_ID, "Let's GO", NotificationManager.IMPORTANCE_LOW)
            .apply { description = "Step tracking status" }
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    private fun buildNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Let's GO").setContentText(text)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pi).setOngoing(true).build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text))
    }
}
