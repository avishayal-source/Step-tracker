package com.steptracker.app

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorManager
import android.location.*
import android.os.*
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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

            if (prev == null) return   // need a reference point before measuring a delta

            val delta        = prev.distanceTo(loc).toDouble()
            val timeDeltaSec = ((loc.time - prev.time) / 1000.0).coerceAtLeast(0.001)
            val impliedSpeed = delta / timeDeltaSec   // m/s

            // Sanity: reject fixes that imply > 29 km/h (GPS bounce / multipath)
            if (impliedSpeed > 8.0) return
            if (delta < 0.5 || delta > 200.0) return

            // Feed speed into the classifier. Prefer the hardware speed from the fix
            // (Doppler-based, more accurate than delta/time). Fall back to computed speed.
            val speedForClassifier = if (loc.hasSpeed()) loc.speed.toDouble() else impliedSpeed
            stepDetector.updateGpsSpeed(speedForClassifier, loc.time)

            // Capture each period's baseline the first time it receives a GPS delta.
            // This works across activity switches (a new period gets its own baseline).
            currentPeriod?.let { p ->
                if (!p.gpsEngaged) {
                    p.stepDistanceAtGpsStartM = p.stepDistanceM
                    p.gpsEngaged = true
                }
                p.gpsDistanceM += delta
            }
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
    var walkSteps = 0;  var runSteps = 0;  var jogSteps = 0
    var walkDistM = 0.0; var runDistM = 0.0; var jogDistM = 0.0
    var sessionStartMs = 0L;  private set

    // FIX #5: guard so history is saved exactly once per session
    private var historySaved = false

    // Throttle: the notification is a system call; rebuilding it on every step
    // (~3/sec while running) wastes CPU/battery and gets rate-limited by Android.
    private var lastNotifUpdateMs = 0L

    // Local CSV debug logs for outdoor walk/run tests. Stored in app-specific
    // external files so they can be pulled later with adb or Device Explorer.
    private var classifierDebugWriter: FileWriter? = null
    private var classifierDebugFile: File? = null

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
        // PARTIAL_WAKE_LOCK keeps the CPU running for sensor delivery without forcing
        // the screen on (SCREEN_DIM_WAKE_LOCK was deprecated and wasted battery).
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "letsgo:tracking")
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

    override fun onDestroy() {
        closeClassifierDebugLog()
        super.onDestroy()
    }

    // ── Public API ────────────────────────────────────────────────────────────

    fun startTracking(initialType: ActivityType = ActivityType.WALKING) {
        if (isTracking) return
        isTracking     = true
        historySaved   = false
        sessionStartMs = System.currentTimeMillis()
        val seed = if (initialType == ActivityType.IDLE) ActivityType.WALKING else initialType
        stepDetector.reset(seed)
        openClassifierDebugLog()
        sensorManager.registerListener(stepDetector,
            sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER),
            SensorManager.SENSOR_DELAY_GAME)
        startGps()
        if (!wakeLock.isHeld) wakeLock.acquire(4 * 60 * 60 * 1000L)
        openNewPeriod(seed, System.currentTimeMillis())
        startInForeground(buildNotification("Tracking…"))
    }

    /**
     * Starts the foreground service with a service type that matches the permissions
     * actually granted. On Android 14 (API 34) calling startForeground with a
     * `location` type while ACCESS_*_LOCATION is denied throws SecurityException and
     * crashes the app. We therefore build the type bitmask from granted permissions.
     */
    private fun startInForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification)
            return
        }
        var type = 0
        val hasLocation =
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (hasLocation) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION

        // HEALTH type exists from API 34 and requires ACTIVITY_RECOGNITION (or BODY_SENSORS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val hasActivity = ContextCompat.checkSelfPermission(
                this, Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED
            if (hasActivity) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH
        }

        try {
            if (type != 0) startForeground(NOTIFICATION_ID, notification, type)
            else startForeground(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            try { startForeground(NOTIFICATION_ID, notification) } catch (_: Exception) {}
        }
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
        updateNotification("Stopped – $totalSteps steps · ${formatDist(walkDistM + jogDistM + runDistM)}")
        onUpdateListener?.invoke()
        closeClassifierDebugLog()
        stopForeground(STOP_FOREGROUND_DETACH)
    }

    fun resetData() {
        if (isTracking) return
        totalSteps = 0; walkSteps = 0; runSteps = 0; jogSteps = 0
        walkDistM = 0.0; runDistM = 0.0; jogDistM = 0.0; historySaved = false
        activityPeriods.clear(); currentPeriod = null; lastGpsLocation = null
        onUpdateListener?.invoke()
    }

    // ── History ───────────────────────────────────────────────────────────────

    private fun saveWorkoutToHistory() {
        val walkDurMs = activityPeriods.filter { it.type == ActivityType.WALKING }.sumOf { it.durationMs }
        val jogDurMs  = activityPeriods.filter { it.type == ActivityType.JOGGING }.sumOf { it.durationMs }
        val runDurMs  = activityPeriods.filter { it.type == ActivityType.RUNNING }.sumOf { it.durationMs }
        // History keeps a walk/run split (no DB migration); jogging is part of the
        // running family, so it folds into the run totals here.
        workoutHistory.save(WorkoutRecord(
            dateMs         = sessionStartMs,
            walkSteps      = walkSteps,
            runSteps       = runSteps + jogSteps,
            walkDistM      = walkDistM,
            runDistM       = runDistM + jogDistM,
            walkDurationMs = walkDurMs,
            runDurationMs  = runDurMs + jogDurMs
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
        val now = System.currentTimeMillis()
        if (now - lastNotifUpdateMs >= 1000L) {
            lastNotifUpdateMs = now
            updateNotification(buildStatusText())
        }
        onUpdateListener?.invoke()
    }

    override fun onActivityChanged(newActivity: ActivityType, wallTimeMs: Long) {
        if (!isTracking || newActivity == ActivityType.IDLE) return
        val now = wallTimeMs
        val prev = currentPeriod
        if (prev?.type == newActivity) return

        val closedSteps = prev?.steps ?: 0

        prev?.let { p ->
            p.endTime = now
            if (p.endTime <= p.startTime) p.endTime = p.startTime + 1000
        }
        val closedDurMs = prev?.durationMs ?: 0L

        openNewPeriod(newActivity, now)
        appendClassifierDebugRow(
            wallTimeMs = now,
            event = "period_switch",
            stepNumber = totalSteps,
            intervalMs = 0L,
            spm = stepDetector.currentSpm,
            rawGpsMps = null,
            filteredGpsMps = null,
            kalmanGain = null,
            gpsAgeMs = -1L,
            inPlaceHold = false,
            signalSource = "service",
            desiredActivity = newActivity,
            candidate = null,
            dwellMs = 0L,
            dwellRequiredMs = 0L,
            cadenceRunVotes = 0,
            cadenceWalkVotes = 0,
            recentImpact = 0.0,
            walkImpactBase = null,
            runImpactBase = null,
            decisionReason = "period_open:${prev?.type ?: ActivityType.IDLE}->$newActivity " +
                "closed_steps=$closedSteps closed_dur_ms=$closedDurMs period_count=${activityPeriods.size}",
            previousActivity = prev?.type ?: ActivityType.IDLE,
            currentActivity = newActivity
        )
        recomputeTotals()
        onUpdateListener?.invoke()
    }

    override fun onClassifierDebug(sample: StepDetector.DebugSample) {
        appendClassifierDebugRow(
            wallTimeMs = sample.wallTimeMs,
            event = sample.event,
            stepNumber = if (sample.event == "gps_update") totalSteps else totalSteps + 1,
            intervalMs = sample.intervalMs,
            spm = sample.spm,
            rawGpsMps = sample.rawGpsMps,
            filteredGpsMps = sample.filteredGpsMps,
            kalmanGain = sample.kalmanGain,
            gpsAgeMs = sample.gpsAgeMs,
            inPlaceHold = sample.inPlaceHold,
            signalSource = sample.signalSource,
            desiredActivity = sample.desiredActivity,
            candidate = sample.candidate,
            dwellMs = sample.dwellMs,
            dwellRequiredMs = sample.dwellRequiredMs,
            cadenceRunVotes = sample.cadenceRunVotes,
            cadenceWalkVotes = sample.cadenceWalkVotes,
            recentImpact = sample.recentImpact,
            walkImpactBase = sample.walkImpactBase,
            runImpactBase = sample.runImpactBase,
            decisionReason = sample.decisionReason,
            previousActivity = sample.previousActivity,
            currentActivity = sample.currentActivity
        )
    }

    private fun appendClassifierDebugRow(
        wallTimeMs: Long,
        event: String,
        stepNumber: Int,
        intervalMs: Long,
        spm: Int,
        rawGpsMps: Double?,
        filteredGpsMps: Double?,
        kalmanGain: Double?,
        gpsAgeMs: Long,
        inPlaceHold: Boolean,
        signalSource: String,
        desiredActivity: ActivityType?,
        candidate: ActivityType?,
        dwellMs: Long,
        dwellRequiredMs: Long,
        cadenceRunVotes: Int,
        cadenceWalkVotes: Int,
        recentImpact: Double,
        walkImpactBase: Double?,
        runImpactBase: Double?,
        decisionReason: String,
        previousActivity: ActivityType,
        currentActivity: ActivityType
    ) {
        if (!BuildConfig.DEBUG) return
        val writer = classifierDebugWriter ?: return
        try {
            writer.append(
                listOf(
                    wallTimeMs,
                    wallTimeMs - sessionStartMs,
                    event,
                    stepNumber,
                    intervalMs,
                    spm,
                    rawGpsMps?.let { "%.2f".format(Locale.US, it) } ?: "",
                    filteredGpsMps?.let { "%.2f".format(Locale.US, it) } ?: "",
                    kalmanGain?.let { "%.4f".format(Locale.US, it) } ?: "",
                    gpsAgeMs,
                    inPlaceHold,
                    signalSource,
                    desiredActivity ?: "",
                    candidate ?: "",
                    dwellMs,
                    dwellRequiredMs,
                    cadenceRunVotes,
                    cadenceWalkVotes,
                    "%.2f".format(Locale.US, recentImpact),
                    walkImpactBase?.let { "%.2f".format(Locale.US, it) } ?: "",
                    runImpactBase?.let { "%.2f".format(Locale.US, it) } ?: "",
                    decisionReason.replace(',', ';'),
                    previousActivity,
                    currentActivity,
                    currentPeriod?.type ?: ActivityType.IDLE,
                    walkSteps,
                    jogSteps,
                    runSteps,
                    totalSteps,
                    "%.2f".format(Locale.US, walkDistM),
                    "%.2f".format(Locale.US, jogDistM),
                    "%.2f".format(Locale.US, runDistM),
                    gpsAvailable
                ).joinToString(",")
            )
            writer.append('\n')
            writer.flush()
        } catch (_: Exception) {
            closeClassifierDebugLog()
        }
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
        // NOTE: currentPeriod is already inside activityPeriods (added in openNewPeriod).
        // Do NOT add listOfNotNull(currentPeriod) — that was double-counting the live
        // period the entire session, causing the displayed distance to be ~2× too high.
        walkSteps = 0; runSteps = 0; jogSteps = 0
        walkDistM = 0.0; runDistM = 0.0; jogDistM = 0.0
        for (p in activityPeriods) when (p.type) {
            ActivityType.WALKING -> { walkSteps += p.steps; walkDistM += p.distanceMeters }
            ActivityType.JOGGING -> { jogSteps  += p.steps; jogDistM  += p.distanceMeters }
            ActivityType.RUNNING -> { runSteps  += p.steps; runDistM  += p.distanceMeters }
            else -> {}
        }
    }

    private fun openClassifierDebugLog() {
        if (!BuildConfig.DEBUG) return
        closeClassifierDebugLog()
        try {
            val dir = File(filesDir, "debug_logs")
            if (!dir.exists()) dir.mkdirs()
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(sessionStartMs))
            classifierDebugFile = File(dir, "classifier_debug_$stamp.csv")
            classifierDebugWriter = FileWriter(classifierDebugFile, false).apply {
                append(
                    "wall_time_ms,session_elapsed_ms,event,step_number,interval_ms,spm," +
                        "raw_gps_mps,filtered_gps_mps,kalman_gain,gps_age_ms,in_place_hold," +
                        "signal_source,desired_activity,candidate,dwell_ms,dwell_required_ms," +
                        "cadence_run_votes,cadence_walk_votes," +
                        "recent_impact,walk_impact_base,run_impact_base," +
                        "decision_reason," +
                        "previous_activity,current_activity," +
                        "current_period,walk_steps,jog_steps,run_steps,total_steps," +
                        "walk_dist_m,jog_dist_m,run_dist_m," +
                        "gps_available\n"
                )
                flush()
            }
        } catch (_: Exception) {
            classifierDebugWriter = null
            classifierDebugFile = null
        }
    }

    private fun closeClassifierDebugLog() {
        try { classifierDebugWriter?.flush() } catch (_: Exception) {}
        try { classifierDebugWriter?.close() } catch (_: Exception) {}
        classifierDebugWriter = null
    }

    private fun buildStatusText(): String {
        val act = when (stepDetector.currentActivity) {
            ActivityType.RUNNING -> "🏃 Running"
            ActivityType.JOGGING -> "🏃 Jogging"
            ActivityType.WALKING -> "🚶 Walking"
            ActivityType.IDLE    -> "⏸ Idle"
        }
        val gps = if (gpsAvailable) " 📍" else ""
        return "$act · $totalSteps steps · ${formatDist(walkDistM + jogDistM + runDistM)}$gps"
    }

    fun formatDist(m: Double) = Format.dist(m)

    // ── Notification ──────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val ch = NotificationChannel(CHANNEL_ID, "Y Walk", NotificationManager.IMPORTANCE_LOW)
            .apply { description = "Step tracking status" }
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    private fun buildNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Y Walk").setContentText(text)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pi).setOngoing(true).build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text))
    }
}
