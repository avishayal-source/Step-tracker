package com.steptracker.app

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.*
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.tabs.TabLayout
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private var service: StepTrackerService? = null
    private var isBound = false
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(n: ComponentName?, b: IBinder?) {
            service = (b as StepTrackerService.LocalBinder).getService()
            isBound = true
            service?.onUpdateListener = { runOnUiThread { updateTrackingUI() } }
            updateTrackingUI()
            if (service?.isTracking == true) {
                stopwatchHandler.removeCallbacks(stopwatchRunnable)
                stopwatchHandler.post(stopwatchRunnable)
            }
        }
        override fun onServiceDisconnected(n: ComponentName?) { isBound = false; service = null }
    }

    // ── Views ─────────────────────────────────────────────────────────────────
    private lateinit var tabLayout: TabLayout
    private lateinit var pageTracking: View
    private lateinit var pageSchedule: View
    private lateinit var pageHistory: View

    // Tracking
    private lateinit var tvStepCount: TextView
    private lateinit var tvSessionElapsed: TextView
    private lateinit var tvSessionElapsedLabel: TextView
    private lateinit var tvCurrentActivity: TextView
    private lateinit var tvTotalDist: TextView
    private lateinit var tvGpsIndicator: TextView
    private lateinit var tvStepSizes: TextView
    private lateinit var tvWalkStats: TextView
    private lateinit var tvRunStats: TextView
    private lateinit var btnStartStop: MaterialButton
    private lateinit var btnReset: MaterialButton
    private lateinit var btnCalibrate: MaterialButton
    private lateinit var rvActivityLog: RecyclerView
    private lateinit var tvEmptyHint: TextView
    private lateinit var tvLogTotals: TextView

    // History
    private lateinit var tvHistorySummary: TextView
    private lateinit var rvHistory: RecyclerView
    private lateinit var tvHistoryEmpty: TextView

    private lateinit var scheduleView: ScheduleEmbeddedView
    private lateinit var adapter: ActivityPeriodAdapter
    private lateinit var userPrefs: UserPrefs
    private lateinit var workoutHistory: WorkoutHistory

    private val stopwatchHandler = Handler(Looper.getMainLooper())
    private val stopwatchRunnable = object : Runnable {
        override fun run() {
            updateTrackingUI()
            if (service?.isTracking == true)
                stopwatchHandler.postDelayed(this, 1000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        userPrefs      = UserPrefs(this)
        workoutHistory = WorkoutHistory(this)

        tabLayout    = findViewById(R.id.tabLayout)
        pageTracking = findViewById(R.id.pageTracking)
        pageSchedule = findViewById(R.id.pageSchedule)
        pageHistory  = findViewById(R.id.pageHistory)

        // Tracking views
        tvStepCount         = findViewById(R.id.tvStepCount)
        tvSessionElapsed      = findViewById(R.id.tvSessionElapsed)
        tvSessionElapsedLabel = findViewById(R.id.tvSessionElapsedLabel)
        tvCurrentActivity     = findViewById(R.id.tvCurrentActivity)
        tvTotalDist       = findViewById(R.id.tvTotalDist)
        tvGpsIndicator    = findViewById(R.id.tvGpsIndicator)
        tvStepSizes       = findViewById(R.id.tvStepSizes)
        tvWalkStats       = findViewById(R.id.tvWalkStats)
        tvRunStats        = findViewById(R.id.tvRunStats)
        btnStartStop      = findViewById(R.id.btnStartStop)
        btnReset          = findViewById(R.id.btnReset)
        btnCalibrate      = findViewById(R.id.btnCalibrate)
        rvActivityLog     = findViewById(R.id.recyclerView)
        tvEmptyHint       = findViewById(R.id.tvEmptyHint)
        tvLogTotals       = findViewById(R.id.tvLogTotals)

        // History views
        tvHistorySummary  = findViewById(R.id.tvHistorySummary)
        rvHistory         = findViewById(R.id.rvHistory)
        tvHistoryEmpty    = findViewById(R.id.tvHistoryEmpty)

        adapter = ActivityPeriodAdapter(mutableListOf())
        rvActivityLog.layoutManager = LinearLayoutManager(this)
        rvActivityLog.adapter = adapter

        rvHistory.layoutManager = LinearLayoutManager(this)

        btnStartStop.setOnClickListener { toggleTracking() }
        btnReset.setOnClickListener     { confirmReset() }
        btnCalibrate.setOnClickListener { startActivity(Intent(this, CalibrationActivity::class.java)) }

        scheduleView = ScheduleEmbeddedView(this, pageSchedule)
        scheduleView.setup()

        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                pageTracking.visibility = if (tab.position == 0) View.VISIBLE else View.GONE
                pageSchedule.visibility = if (tab.position == 1) View.VISIBLE else View.GONE
                pageHistory.visibility  = if (tab.position == 2) View.VISIBLE else View.GONE
                if (tab.position == 2) updateHistoryUI()
            }
            override fun onTabUnselected(t: TabLayout.Tab?) {}
            override fun onTabReselected(t: TabLayout.Tab?) {}
        })

        bindService(Intent(this, StepTrackerService::class.java), connection, Context.BIND_AUTO_CREATE)

        if (!userPrefs.isCalibrated)
            Toast.makeText(this, "Tip: tap ⚙ Calibrate to set your personal step length", Toast.LENGTH_LONG).show()
    }

    override fun onDestroy() {
        stopwatchHandler.removeCallbacks(stopwatchRunnable)
        scheduleView.onActivityDestroy()
        if (isBound) { service?.onUpdateListener = null; unbindService(connection) }
        super.onDestroy()
    }

    // ── Permissions ───────────────────────────────────────────────────────────

    private fun allPerms() = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) add(Manifest.permission.ACTIVITY_RECOGNITION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
    }.toTypedArray()

    private fun hasTrackingPerms() = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        listOf(Manifest.permission.ACTIVITY_RECOGNITION) else emptyList())
        .all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }

    override fun onRequestPermissionsResult(rc: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(rc, perms, results)
        if (rc == 100) {
            if (hasTrackingPerms()) {
                startTrackingService()
                // Warn if location was specifically denied — tracking still works, just no GPS
                val locIdx = perms.indexOf(Manifest.permission.ACCESS_FINE_LOCATION)
                if (locIdx >= 0 && results[locIdx] != PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(this, "Location denied — distance will use step counting only",
                        Toast.LENGTH_LONG).show()
                }
            } else {
                Toast.makeText(this, "Activity permission required for step tracking",
                    Toast.LENGTH_LONG).show()
            }
        }
    }

    // ── Tracking ──────────────────────────────────────────────────────────────

    private fun toggleTracking() {
        val svc = service ?: return
        if (svc.isTracking) {
            svc.stopTracking()
            stopwatchHandler.removeCallbacks(stopwatchRunnable)
            updateTrackingUI()
        } else if (hasTrackingPerms()) startTrackingService()
        else ActivityCompat.requestPermissions(this, allPerms(), 100)
    }

    private fun confirmReset() {
        val svc = service ?: return
        if (svc.isTracking) { Toast.makeText(this,"Stop tracking first",Toast.LENGTH_SHORT).show(); return }
        AlertDialog.Builder(this).setTitle("Reset session data?")
            .setMessage("This clears the current session. Workout history is preserved.")
            .setPositiveButton("Reset") { _, _ -> svc.resetData(); updateTrackingUI() }
            .setNegativeButton("Cancel", null).show()
    }

    private fun startTrackingService() {
        ContextCompat.startForegroundService(this,
            Intent(this, StepTrackerService::class.java).apply { action = StepTrackerService.ACTION_START })
        if (!isBound) bindService(Intent(this, StepTrackerService::class.java), connection, Context.BIND_AUTO_CREATE)
        stopwatchHandler.removeCallbacks(stopwatchRunnable)
        stopwatchHandler.post(stopwatchRunnable)
    }

    // ── Tracking UI ───────────────────────────────────────────────────────────

    private fun updateTrackingUI() {
        val svc = service ?: return

        tvStepCount.text = svc.totalSteps.toString()
        val showElapsed = svc.isTracking
        tvSessionElapsed.text = if (showElapsed) formatElapsed(svc.sessionElapsedMs()) else "00:00"
        tvSessionElapsed.visibility = if (showElapsed) View.VISIBLE else View.GONE
        tvSessionElapsedLabel.visibility = if (showElapsed) View.VISIBLE else View.GONE
        tvCurrentActivity.text = when {
            !svc.isTracking -> "Not tracking"
            else -> when (svc.currentPeriod?.type) {
                ActivityType.RUNNING -> "🏃 Running / Jogging"
                ActivityType.WALKING -> "🚶 Walking"
                else -> "⏸ Idle"
            }
        }
        val hasFineLocation = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        tvGpsIndicator.text = when {
            !svc.isTracking    -> ""
            !hasFineLocation   -> "⚠ No GPS permission"
            svc.gpsAvailable   -> "📍 GPS"
            else               -> "👟 Steps (GPS acquiring…)"
        }

        val totalDist = svc.walkDistM + svc.runDistM
        tvTotalDist.text = svc.formatDist(totalDist)

        val walkSizeStr = if (userPrefs.isCalibrated) "${"%.2f".format(userPrefs.walkStrideM)} m" else "-"
        val runSizeStr  = if (userPrefs.isCalibrated) "${"%.2f".format(userPrefs.runStrideM)} m" else "-"
        tvStepSizes.text = "Step size:  🚶 $walkSizeStr    🏃 $runSizeStr"

        tvWalkStats.text = "🚶 Walk\n${svc.walkSteps} steps\n${svc.formatDist(svc.walkDistM)}"
        tvRunStats.text  = "🏃 Run\n${svc.runSteps} steps\n${svc.formatDist(svc.runDistM)}"

        btnStartStop.text = if (svc.isTracking) "■  Stop" else "▶  Start"
        btnReset.visibility = if (!svc.isTracking && svc.totalSteps > 0) View.VISIBLE else View.GONE

        val filtered = svc.activityPeriods.filter { it.type != ActivityType.IDLE }
        adapter.updateData(filtered)
        tvEmptyHint.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE

        if (filtered.isNotEmpty()) {
            val walkMs = filtered.filter { it.type == ActivityType.WALKING }.sumOf { it.durationMs }
            val runMs  = filtered.filter { it.type == ActivityType.RUNNING  }.sumOf { it.durationMs }
            tvLogTotals.text =
                "🚶 ${TimeUnit.MILLISECONDS.toMinutes(walkMs)}m · ${svc.formatDist(svc.walkDistM)}   " +
                "🏃 ${TimeUnit.MILLISECONDS.toMinutes(runMs)}m · ${svc.formatDist(svc.runDistM)}"
            tvLogTotals.visibility = View.VISIBLE
        } else tvLogTotals.visibility = View.GONE
    }

    // ── History UI ────────────────────────────────────────────────────────────

    private fun updateHistoryUI() {
        val records = workoutHistory.loadAll()
        tvHistoryEmpty.visibility = if (records.isEmpty()) View.VISIBLE else View.GONE
        rvHistory.visibility      = if (records.isEmpty()) View.GONE    else View.VISIBLE

        if (records.isEmpty()) {
            tvHistorySummary.text = "No workouts yet"
            return
        }

        // Totals summary (#8)
        val totalWalkDist = records.sumOf { it.walkDistM }
        val totalRunDist  = records.sumOf { it.runDistM }
        val totalWalkMs   = records.sumOf { it.walkDurationMs }
        val totalRunMs    = records.sumOf { it.runDurationMs }
        tvHistorySummary.text =
            "ALL TIME  ·  ${records.size} workouts\n" +
            "🚶 ${formatDist(totalWalkDist)}  ${formatDur(totalWalkMs)}\n" +
            "🏃 ${formatDist(totalRunDist)}  ${formatDur(totalRunMs)}"

        rvHistory.adapter = HistoryAdapter(records) { record ->
            // Long-press → delete confirmation
            AlertDialog.Builder(this).setTitle("Delete this workout?")
                .setPositiveButton("Delete") { _, _ ->
                    // Rebuild history without this record
                    val all = workoutHistory.loadAll().toMutableList()
                    all.removeAll { it.id == record.id }
                    workoutHistory.clearAll()
                    all.forEach { workoutHistory.save(it) }
                    updateHistoryUI()
                }.setNegativeButton("Cancel", null).show()
        }
    }

    private fun formatDist(m: Double) = if (m >= 1000) "${"%.1f".format(m/1000)} km" else "${m.toInt()} m"

    private fun formatElapsed(ms: Long): String {
        val totalSec = TimeUnit.MILLISECONDS.toSeconds(ms)
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
    }

    private fun formatDur(ms: Long): String {
        val h = TimeUnit.MILLISECONDS.toHours(ms)
        val m = TimeUnit.MILLISECONDS.toMinutes(ms) % 60
        return if (h > 0) "${h}h ${m}m" else "${m}m"
    }
}

// ── History list adapter ──────────────────────────────────────────────────────

class HistoryAdapter(
    private val records: List<WorkoutRecord>,
    private val onLongPress: (WorkoutRecord) -> Unit
) : RecyclerView.Adapter<HistoryAdapter.VH>() {

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        val tvDate:  TextView = v.findViewById(R.id.tvHistDate)
        val tvWalk:  TextView = v.findViewById(R.id.tvHistWalk)
        val tvRun:   TextView = v.findViewById(R.id.tvHistRun)
        val tvTotal: TextView = v.findViewById(R.id.tvHistTotal)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_history, parent, false))

    override fun getItemCount() = records.size

    override fun onBindViewHolder(h: VH, pos: Int) {
        val r = records[pos]
        fun fDist(m: Double) = if (m >= 1000) "${"%.1f".format(m/1000)}km" else "${m.toInt()}m"
        fun fDur(ms: Long): String {
            val min = TimeUnit.MILLISECONDS.toMinutes(ms)
            val sec = TimeUnit.MILLISECONDS.toSeconds(ms) % 60
            return if (min > 0) "${min}m ${sec}s" else "${sec}s"
        }
        h.tvDate.text  = r.dateLabel
        h.tvWalk.text  = "🚶 ${fDist(r.walkDistM)}  ${fDur(r.walkDurationMs)}  ${r.walkSteps} steps"
        h.tvRun.text   = "🏃 ${fDist(r.runDistM)}  ${fDur(r.runDurationMs)}  ${r.runSteps} steps"
        h.tvTotal.text = "Total: ${fDist(r.totalDistM)}  ${r.totalSteps} steps"
        h.itemView.setOnLongClickListener { onLongPress(r); true }
    }
}
