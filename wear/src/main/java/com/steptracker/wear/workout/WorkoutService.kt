package com.steptracker.wear.workout

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.steptracker.wear.StepPeakDetector
import com.steptracker.wear.sync.SessionSync
import com.steptracker.wear.sync.TodayCache
import com.steptracker.wear.sync.TodaySnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Owns the live workout so tracking continues when the screen goes ambient
 * or the user leaves [WorkoutActivity].
 */
class WorkoutService : Service(), SensorEventListener {

    private val uiHandler = Handler(Looper.getMainLooper())
    private val peaks = StepPeakDetector()

    private lateinit var sensorManager: SensorManager
    private var accel: Sensor? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var sensorsRegistered = false

    private var mode = MODE_FREE
    private var startedAtMs = 0L
    private var walkStride = 0.39
    private var runStride = 0.60
    private var periods: List<TodaySnapshot.Period> = emptyList()
    private var title = "Free workout"
    private var workoutId = 0L
    private var sessionId = ""

    private var activeElapsedMs = 0L
    private var lastTickElapsed = 0L
    private var periodIndex = 0
    private var lastCuedIndex = 0

    private var steps = 0
    private var distM = 0.0
    private var walkSteps = 0
    private var runSteps = 0
    private var walkDistM = 0.0
    private var runDistM = 0.0
    private var walkDurationMs = 0L
    private var runDurationMs = 0L

    private var paused = false
    private var finished = false
    private var lastCheckpointMs = 0L

    private val tick = object : Runnable {
        override fun run() {
            if (finished) return
            val now = SystemClock.elapsedRealtime()
            if (!paused) {
                val delta = (now - lastTickElapsed).coerceAtLeast(0L)
                activeElapsedMs += delta
                accumulateDuration(delta)
                if (mode == MODE_SCHEDULED && periods.isNotEmpty()) {
                    advanceSchedule(activeElapsedMs)
                }
            }
            lastTickElapsed = now
            if (finished) return
            publish()
            maybeCheckpoint()
            updateNotification()
            uiHandler.postDelayed(this, 500L)
        }
    }

    override fun onBind(intent: Intent?) = null

    override fun onCreate() {
        super.onCreate()
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        accel = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        createChannel()
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ywalk:workout").also {
            it.setReferenceCounted(false)
            it.acquire(3 * 60 * 60 * 1000L)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action.isNullOrEmpty()) {
            return restartSticky()
        }
        when (intent?.action) {
            ACTION_START -> {
                if (!finished && sessionId.isNotEmpty()) {
                    startInForeground(buildNotification())
                    return START_STICKY
                }
                beginWorkout(intent?.getStringExtra(EXTRA_MODE) ?: MODE_FREE)
            }
            ACTION_PAUSE -> {
                if (!ensureLiveForeground()) return START_NOT_STICKY
                pauseWorkout()
            }
            ACTION_RESUME -> {
                if (!ensureLiveForeground()) return START_NOT_STICKY
                resumeWorkout()
            }
            ACTION_TOGGLE_PAUSE -> {
                if (!ensureLiveForeground()) return START_NOT_STICKY
                if (paused) resumeWorkout() else pauseWorkout()
            }
            ACTION_END -> {
                if (!ensureLiveForeground()) return START_NOT_STICKY
                endWorkout()
            }
            else -> {
                if (sessionId.isEmpty()) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                startInForeground(buildNotification())
            }
        }
        return START_STICKY
    }

    private fun restartSticky(): Int {
        if (sessionId.isNotEmpty() && !finished) {
            startInForeground(buildNotification())
            lastTickElapsed = SystemClock.elapsedRealtime()
            if (!paused) registerSensors()
            uiHandler.removeCallbacks(tick)
            uiHandler.post(tick)
            publish()
            return START_STICKY
        }
        val live = SessionStore.loadLive(this)
        if (live != null) {
            restoreFromLive(live)
            return START_STICKY
        }
        stopSelf()
        return START_NOT_STICKY
    }

    private fun restoreFromLive(live: WatchSession) {
        sessionId = live.id
        startedAtMs = live.startedAtMs
        mode = live.mode
        workoutId = live.workoutId
        title = live.title
        steps = live.steps
        distM = live.distM
        activeElapsedMs = live.durationMs
        walkSteps = live.walkSteps
        runSteps = live.runSteps
        walkDistM = live.walkDistM
        runDistM = live.runDistM
        walkDurationMs = live.walkDurationMs
        runDurationMs = live.runDurationMs
        val snap = TodayCache.load(this)
        walkStride = snap?.walkStrideM ?: 0.39
        runStride = snap?.runStrideM ?: 0.60
        periods = if (mode == MODE_SCHEDULED) snap?.periods.orEmpty() else emptyList()
        restorePeriodIndex()
        paused = false
        finished = false
        lastTickElapsed = SystemClock.elapsedRealtime()
        peaks.reset()
        startInForeground(buildNotification())
        registerSensors()
        uiHandler.removeCallbacks(tick)
        uiHandler.post(tick)
        publish()
    }

    private fun restorePeriodIndex() {
        if (periods.isEmpty()) {
            periodIndex = 0
            lastCuedIndex = 0
            return
        }
        var cursor = 0L
        for (i in periods.indices) {
            val end = cursor + periods[i].mins * 60_000L
            if (activeElapsedMs < end) {
                periodIndex = i
                lastCuedIndex = i
                return
            }
            cursor = end
        }
        periodIndex = periods.lastIndex
        lastCuedIndex = periodIndex
    }

    /** Avoid startForegroundService crash if a command arrives after the session ended. */
    private fun ensureLiveForeground(): Boolean {
        if (sessionId.isEmpty() || finished) {
            stopSelf()
            return false
        }
        startInForeground(buildNotification())
        return true
    }

    override fun onDestroy() {
        uiHandler.removeCallbacks(tick)
        unregisterSensors()
        wakeLock?.let { if (it.isHeld) it.release() }
        if (!finished && sessionId.isNotEmpty() && (steps > 0 || activeElapsedMs > 15_000L)) {
            finalizeSession(endedAtMs = System.currentTimeMillis())
        }
        if (!_state.value.inProgress && !_state.value.finished) {
            _state.value = WorkoutSnapshot()
        }
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Activity swipe must not kill the workout.
    }

    private fun beginWorkout(requestedMode: String) {
        val snap = TodayCache.load(this)
        walkStride = snap?.walkStrideM ?: 0.39
        runStride = snap?.runStrideM ?: 0.60
        mode = requestedMode
        if (mode == MODE_SCHEDULED && snap != null && snap.periods.isNotEmpty()) {
            periods = snap.periods
            title = snap.title.ifBlank { "Scheduled" }
            workoutId = snap.workoutId
        } else {
            mode = MODE_FREE
            periods = emptyList()
            title = "Free workout"
            workoutId = 0L
        }

        sessionId = SessionStore.newId()
        startedAtMs = System.currentTimeMillis()
        lastTickElapsed = SystemClock.elapsedRealtime()
        activeElapsedMs = 0L
        periodIndex = 0
        lastCuedIndex = 0
        steps = 0
        distM = 0.0
        walkSteps = 0
        runSteps = 0
        walkDistM = 0.0
        runDistM = 0.0
        walkDurationMs = 0L
        runDurationMs = 0L
        paused = false
        finished = false
        peaks.reset()

        startInForeground(buildNotification())
        registerSensors()
        uiHandler.removeCallbacks(tick)
        uiHandler.post(tick)
        publish()
        checkpoint()
    }

    private fun pauseWorkout() {
        if (finished || paused || sessionId.isEmpty()) return
        val now = SystemClock.elapsedRealtime()
        val delta = (now - lastTickElapsed).coerceAtLeast(0L)
        activeElapsedMs += delta
        accumulateDuration(delta)
        lastTickElapsed = now
        paused = true
        unregisterSensors()
        publish()
        updateNotification()
        checkpoint()
    }

    private fun resumeWorkout() {
        if (finished || !paused) return
        paused = false
        lastTickElapsed = SystemClock.elapsedRealtime()
        peaks.reset()
        registerSensors()
        publish()
        updateNotification()
    }

    private fun endWorkout() {
        if (finished) return
        if (!paused) {
            val now = SystemClock.elapsedRealtime()
            val delta = (now - lastTickElapsed).coerceAtLeast(0L)
            activeElapsedMs += delta
            accumulateDuration(delta)
            lastTickElapsed = now
        }
        finished = true
        paused = false
        uiHandler.removeCallbacks(tick)
        unregisterSensors()
        val session = finalizeSession(endedAtMs = System.currentTimeMillis())
        publish()
        if (session != null) {
            SessionSync.pushAsync(applicationContext, session)
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun finalizeSession(endedAtMs: Long): WatchSession? {
        if (sessionId.isEmpty()) return null
        val session = WatchSession(
            id = sessionId,
            startedAtMs = startedAtMs,
            endedAtMs = endedAtMs,
            mode = mode,
            workoutId = workoutId,
            title = title,
            steps = steps,
            distM = distM,
            durationMs = activeElapsedMs,
            walkSteps = walkSteps,
            runSteps = runSteps,
            walkDistM = walkDistM,
            runDistM = runDistM,
            walkDurationMs = walkDurationMs,
            runDurationMs = runDurationMs,
            syncedToPhone = false
        )
        SessionStore.add(this, session)
        SessionStore.clearLive(this)
        return session
    }

    private fun advanceSchedule(activeMs: Long) {
        var cursor = 0L
        for (i in periods.indices) {
            val dur = periods[i].mins * 60_000L
            val end = cursor + dur
            if (activeMs < end) {
                if (i != periodIndex) {
                    periodIndex = i
                    if (i != lastCuedIndex) {
                        lastCuedIndex = i
                        WorkoutCues.play(this, WorkoutCues.forPeriodType(periods[i].type))
                    }
                }
                return
            }
            cursor = end
        }
        periodIndex = periods.lastIndex
        WorkoutCues.play(this, WorkoutCues.SoundType.COMPLETE)
        endWorkout()
    }

    private fun currentPeriod(): TodaySnapshot.Period? {
        if (mode != MODE_SCHEDULED || periods.isEmpty()) return null
        return periods[periodIndex.coerceIn(0, periods.lastIndex)]
    }

    private fun isRunPeriod(): Boolean {
        val type = currentPeriod()?.type ?: return false
        return type == "RUNNING" || type == "JOGGING"
    }

    private fun currentStrideM(): Double =
        if (isRunPeriod()) runStride else walkStride

    private fun accumulateDuration(deltaMs: Long) {
        if (isRunPeriod()) runDurationMs += deltaMs else walkDurationMs += deltaMs
    }

    private fun periodLeftMs(): Long {
        if (mode != MODE_SCHEDULED || periods.isEmpty()) return 0L
        var cursor = 0L
        for (i in periods.indices) {
            val dur = periods[i].mins * 60_000L
            val end = cursor + dur
            if (i == periodIndex.coerceIn(0, periods.lastIndex) && activeElapsedMs < end) {
                return (end - activeElapsedMs).coerceAtLeast(0L)
            }
            cursor = end
        }
        return 0L
    }

    private fun nextLabel(): String {
        if (mode != MODE_SCHEDULED || periods.isEmpty()) return ""
        val next = periodIndex + 1
        if (next !in periods.indices) return ""
        return "Next: ${periods[next].label}"
    }

    private fun periodLabel(): String {
        if (finished) return "Saved"
        if (mode != MODE_SCHEDULED || periods.isEmpty()) return "Free"
        return currentPeriod()?.label ?: "Free"
    }

    private fun publish() {
        val phase = when {
            finished -> WorkoutSnapshot.Phase.FINISHED
            paused -> WorkoutSnapshot.Phase.PAUSED
            sessionId.isEmpty() -> WorkoutSnapshot.Phase.IDLE
            else -> WorkoutSnapshot.Phase.ACTIVE
        }
        _state.value = WorkoutSnapshot(
            phase = phase,
            mode = mode,
            scheduled = mode == MODE_SCHEDULED,
            title = title,
            periodLabel = periodLabel(),
            nextLabel = if (finished) "" else nextLabel(),
            periodLeftMs = periodLeftMs(),
            steps = steps,
            distM = distM,
            elapsedMs = activeElapsedMs
        )
    }

    private fun maybeCheckpoint() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastCheckpointMs < 10_000L) return
        checkpoint()
    }

    private fun checkpoint() {
        lastCheckpointMs = SystemClock.elapsedRealtime()
        if (finished || sessionId.isEmpty()) return
        SessionStore.saveLive(
            this,
            WatchSession(
                id = sessionId,
                startedAtMs = startedAtMs,
                endedAtMs = System.currentTimeMillis(),
                mode = mode,
                workoutId = workoutId,
                title = title,
                steps = steps,
                distM = distM,
                durationMs = activeElapsedMs,
                walkSteps = walkSteps,
                runSteps = runSteps,
                walkDistM = walkDistM,
                runDistM = runDistM,
                walkDurationMs = walkDurationMs,
                runDurationMs = runDurationMs,
                syncedToPhone = false
            )
        )
    }

    private fun registerSensors() {
        if (sensorsRegistered) return
        accel?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
            sensorsRegistered = true
        }
    }

    private fun unregisterSensors() {
        if (!sensorsRegistered) return
        sensorManager.unregisterListener(this)
        sensorsRegistered = false
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (finished || paused) return
        if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) return
        val wall = System.currentTimeMillis()
        if (peaks.onSample(event.values[0], event.values[1], event.values[2], wall) == null) return
        uiHandler.post {
            if (finished || paused) return@post
            steps += 1
            val stride = currentStrideM()
            distM += stride
            if (isRunPeriod()) {
                runSteps += 1
                runDistM += stride
            } else {
                walkSteps += 1
                walkDistM += stride
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun startInForeground(notification: Notification) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH
                )
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, 0)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (_: Exception) {
            try { startForeground(NOTIFICATION_ID, notification) } catch (_: Exception) {}
        }
    }

    private fun createChannel() {
        val ch = NotificationChannel(
            CHANNEL_ID,
            "Workout",
            NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    private fun updateNotification() {
        if (finished) return
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, WorkoutActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val status = when {
            paused -> "Paused"
            mode == MODE_SCHEDULED -> periodLabel()
            else -> title
        }
        val km = "%.2f".format(distM / 1000.0)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(status)
            .setContentText("${formatMs(activeElapsedMs)} · $steps steps · $km km")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        const val ACTION_START = "com.steptracker.wear.workout.START"
        const val ACTION_PAUSE = "com.steptracker.wear.workout.PAUSE"
        const val ACTION_RESUME = "com.steptracker.wear.workout.RESUME"
        const val ACTION_TOGGLE_PAUSE = "com.steptracker.wear.workout.TOGGLE_PAUSE"
        const val ACTION_END = "com.steptracker.wear.workout.END"
        const val EXTRA_MODE = "mode"
        const val MODE_FREE = "FREE"
        const val MODE_SCHEDULED = "SCHEDULED"

        private const val CHANNEL_ID = "ywalk_workout"
        private const val NOTIFICATION_ID = 42

        private val _state = MutableStateFlow(WorkoutSnapshot())
        val state: StateFlow<WorkoutSnapshot> = _state.asStateFlow()

        val isActive: Boolean get() = _state.value.inProgress

        fun start(context: Context, mode: String) {
            val i = Intent(context, WorkoutService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_MODE, mode)
            ContextCompat.startForegroundService(context, i)
        }

        fun send(context: Context, action: String) {
            if (action != ACTION_START && !isActive) return
            ContextCompat.startForegroundService(
                context,
                Intent(context, WorkoutService::class.java).setAction(action)
            )
        }
    }
}

internal fun formatMs(ms: Long): String {
    val totalSec = (ms / 1000L).coerceAtLeast(0L)
    val m = totalSec / 60
    val s = totalSec % 60
    return "%d:%02d".format(m, s)
}
