package com.steptracker.app

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.*
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.tabs.TabLayout
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_OPEN_SCHEDULE = "extra_open_schedule"
    }

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
    private lateinit var pageCoach: View

    // Tracking
    private lateinit var tvStepCount: TextView
    private lateinit var tvSessionElapsed: TextView
    private lateinit var tvSessionElapsedLabel: TextView
    private lateinit var tvCurrentActivity: TextView
    private lateinit var tvTotalDist: TextView
    private lateinit var tvGpsIndicator: TextView
    private lateinit var tvStepSizes: TextView
    private lateinit var tvWalkStats: TextView
    private lateinit var tvJogStats: TextView
    private lateinit var tvRunStats: TextView
    private lateinit var btnStartStop: MaterialButton
    private lateinit var btnReset: MaterialButton
    private lateinit var btnCalibrate: MaterialButton
    private lateinit var layoutTimeline: LinearLayout
    private lateinit var timelineBar: LinearLayout
    private lateinit var tvLogTotals: TextView
    private lateinit var tvEmptyHint: TextView

    // History
    private lateinit var tvHistorySummary: TextView
    private lateinit var tvHistoryInsights: TextView
    private lateinit var rvHistory: RecyclerView
    private lateinit var tvHistoryEmpty: TextView

    private lateinit var scheduleView: ScheduleEmbeddedView
    private lateinit var coachView: CoachView
    private lateinit var userPrefs: UserPrefs
    private lateinit var workoutHistory: WorkoutHistory

    private val exportBackupLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument(BackupManager.mimeType)
    ) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        try {
            BackupManager.exportToUri(this, uri)
            Toast.makeText(this, R.string.backup_export_ok, Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(
                this,
                getString(R.string.backup_export_fail, e.message ?: "error"),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private val importBackupLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        confirmAndRestoreBackup(uri)
    }

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

        // Legal consent gate — must accept Terms / Privacy / disclaimers first.
        if (!LegalConsent.isAccepted(this)) {
            startActivity(Intent(this, OnboardingActivity::class.java))
            finish()
            return
        }

        setContentView(R.layout.activity_main)
        applyRootSystemBarInsets()
        userPrefs      = UserPrefs(this)
        workoutHistory = WorkoutHistory(this)

        tabLayout    = findViewById(R.id.tabLayout)
        pageTracking = findViewById(R.id.pageTracking)
        pageSchedule = findViewById(R.id.pageSchedule)
        pageHistory  = findViewById(R.id.pageHistory)
        pageCoach    = findViewById(R.id.pageCoach)

        // Tracking views
        tvStepCount         = findViewById(R.id.tvStepCount)
        tvSessionElapsed      = findViewById(R.id.tvSessionElapsed)
        tvSessionElapsedLabel = findViewById(R.id.tvSessionElapsedLabel)
        tvCurrentActivity     = findViewById(R.id.tvCurrentActivity)
        tvTotalDist       = findViewById(R.id.tvTotalDist)
        tvGpsIndicator    = findViewById(R.id.tvGpsIndicator)
        tvStepSizes       = findViewById(R.id.tvStepSizes)
        tvWalkStats       = findViewById(R.id.tvWalkStats)
        tvJogStats        = findViewById(R.id.tvJogStats)
        tvRunStats        = findViewById(R.id.tvRunStats)
        btnStartStop      = findViewById(R.id.btnStartStop)
        btnReset          = findViewById(R.id.btnReset)
        btnCalibrate      = findViewById(R.id.btnCalibrate)
        layoutTimeline    = findViewById(R.id.layoutTimeline)
        timelineBar       = findViewById(R.id.timelineBar)
        tvLogTotals       = findViewById(R.id.tvLogTotals)
        tvEmptyHint       = findViewById(R.id.tvEmptyHint)

        // History views
        tvHistorySummary  = findViewById(R.id.tvHistorySummary)
        tvHistoryInsights = findViewById(R.id.tvHistoryInsights)
        rvHistory         = findViewById(R.id.rvHistory)
        tvHistoryEmpty    = findViewById(R.id.tvHistoryEmpty)

        rvHistory.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this)

        btnStartStop.setOnClickListener { toggleTracking() }
        btnReset.setOnClickListener     { confirmReset() }
        btnCalibrate.setOnClickListener { startActivity(Intent(this, CalibrationActivity::class.java)) }

        findViewById<View>(R.id.btnMore).setOnClickListener { anchor -> showMoreMenu(anchor) }

        scheduleView = ScheduleEmbeddedView(this, pageSchedule)
        scheduleView.setup()

        coachView = CoachView(this, pageCoach)
        coachView.setup()
        coachView.onPlanActivated = { tabLayout.getTabAt(1)?.select() }
        pageCoach.findViewById<View>(R.id.btnPrivacyPolicy).setOnClickListener {
            startActivity(LegalDocActivity.privacy(this))
        }
        pageCoach.findViewById<View>(R.id.btnTermsOfService).setOnClickListener {
            startActivity(LegalDocActivity.terms(this))
        }

        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                pageTracking.visibility = if (tab.position == 0) View.VISIBLE else View.GONE
                pageSchedule.visibility = if (tab.position == 1) View.VISIBLE else View.GONE
                pageHistory.visibility  = if (tab.position == 2) View.VISIBLE else View.GONE
                pageCoach.visibility    = if (tab.position == 3) View.VISIBLE else View.GONE
                if (tab.position == 1) scheduleView.loadTodaysPlannedWorkout()
                if (tab.position == 2) updateHistoryUI()
                if (tab.position == 3) coachView.onTabVisible()
            }
            override fun onTabUnselected(t: TabLayout.Tab?) {}
            override fun onTabReselected(t: TabLayout.Tab?) {}
        })

        bindService(Intent(this, StepTrackerService::class.java), connection, Context.BIND_AUTO_CREATE)

        if (!userPrefs.isCalibrated)
            Toast.makeText(this, "Tip: tap ⚙ Calibrate to set your personal step length", Toast.LENGTH_LONG).show()

        handleIntentExtras(intent)
    }

    private fun showMoreMenu(anchor: View) {
        val popup = androidx.appcompat.widget.PopupMenu(this, anchor)
        popup.menuInflater.inflate(R.menu.more_menu, popup.menu)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_export_backup -> {
                    exportBackupLauncher.launch(BackupManager.suggestedFileName()); true
                }
                R.id.action_restore_backup -> {
                    importBackupLauncher.launch(arrayOf("application/json", "text/plain", "*/*")); true
                }
                R.id.action_privacy -> {
                    startActivity(LegalDocActivity.privacy(this)); true
                }
                R.id.action_terms -> {
                    startActivity(LegalDocActivity.terms(this)); true
                }
                else -> false
            }
        }
        popup.show()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntentExtras(intent)
    }

    /** Open the Schedule tab (and load today's workout) when launched from a reminder. */
    private fun handleIntentExtras(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_OPEN_SCHEDULE, false) == true) {
            tabLayout.post { tabLayout.getTabAt(1)?.select() }
        }
    }

    override fun onDestroy() {
        stopwatchHandler.removeCallbacks(stopwatchRunnable)
        if (::scheduleView.isInitialized) scheduleView.onActivityDestroy()
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
        else requestTrackingPermissions()
    }

    /** Explain why each permission is needed before the system dialog (Play policy). */
    private fun requestTrackingPermissions() {
        AlertDialog.Builder(this)
            .setTitle(R.string.permission_rationale_title)
            .setMessage(R.string.permission_rationale_message)
            .setPositiveButton(R.string.permission_rationale_continue) { _, _ ->
                ActivityCompat.requestPermissions(this, allPerms(), 100)
            }
            .setNegativeButton(R.string.permission_rationale_cancel, null)
            .show()
    }

    private fun confirmReset() {
        val svc = service ?: return
        if (svc.isTracking) { Toast.makeText(this,"Stop tracking first",Toast.LENGTH_SHORT).show(); return }
        AlertDialog.Builder(this).setTitle("Reset session data?")
            .setMessage("This clears the current session. Workout history is preserved.")
            .setPositiveButton("Reset") { _, _ -> svc.resetData(); updateTrackingUI() }
            .setNegativeButton("Cancel", null).show()
    }

    private fun confirmAndRestoreBackup(uri: Uri) {
        try {
            val summary = BackupManager.peekSummary(this, uri)
            val planNote = if (summary.hasPlan) " · includes training plan" else ""
            AlertDialog.Builder(this)
                .setTitle(R.string.backup_restore_title)
                .setMessage(
                    getString(
                        R.string.backup_restore_message,
                        summary.appVersionName,
                        summary.workoutCount,
                        summary.scheduleCount,
                        planNote
                    )
                )
                .setPositiveButton(R.string.backup_restore_confirm) { _, _ ->
                    try {
                        BackupManager.importFromUri(this, uri)
                        userPrefs = UserPrefs(this)
                        coachView.reloadAfterBackupRestore()
                        updateTrackingUI()
                        updateHistoryUI()
                        Toast.makeText(this, R.string.backup_restore_ok, Toast.LENGTH_LONG).show()
                    } catch (e: Exception) {
                        Toast.makeText(
                            this,
                            getString(R.string.backup_restore_fail, e.message ?: "error"),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        } catch (e: Exception) {
            Toast.makeText(
                this,
                getString(R.string.backup_restore_fail, e.message ?: "error"),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun startTrackingService() {
        ContextCompat.startForegroundService(this,
            Intent(this, StepTrackerService::class.java).apply { action = StepTrackerService.ACTION_START })
        // Service is already bound in onCreate(); no second bindService here to avoid
        // a duplicate ServiceConnection (which Android reports as a leak).
        stopwatchHandler.removeCallbacks(stopwatchRunnable)
        stopwatchHandler.post(stopwatchRunnable)
    }

    // ── Tracking UI ───────────────────────────────────────────────────────────

    private fun updateTrackingUI() {
        val svc = service ?: return

        // ── Step count + timer ────────────────────────────────────────────────
        tvStepCount.text = svc.totalSteps.toString()
        val showElapsed = svc.isTracking
        tvSessionElapsed.text = if (showElapsed) formatElapsed(svc.sessionElapsedMs()) else "00:00"
        tvSessionElapsed.visibility      = if (showElapsed) View.VISIBLE else View.GONE
        tvSessionElapsedLabel.visibility = if (showElapsed) View.VISIBLE else View.GONE

        // ── Activity pill with dynamic colour (Walking / Jogging / Running) ──────
        val activityType = svc.currentPeriod?.type
        tvCurrentActivity.text = when {
            !svc.isTracking -> "Not tracking"
            activityType == ActivityType.RUNNING -> "🏃 Running"
            activityType == ActivityType.JOGGING -> "🏃 Jogging"
            activityType == ActivityType.WALKING -> "🚶 Walking"
            else            -> "⏸ Idle"
        }
        val pillColor = when {
            !svc.isTracking -> ContextCompat.getColor(this, R.color.surface_elevated)
            activityType == ActivityType.RUNNING -> 0xBFFF5722.toInt()   // coral-orange
            activityType == ActivityType.JOGGING -> 0xBFFFA000.toInt()   // amber (mid tier)
            activityType == ActivityType.WALKING -> 0xBF14B86A.toInt()   // emerald
            else            -> ContextCompat.getColor(this, R.color.surface_elevated)
        }
        tvCurrentActivity.backgroundTintList = ColorStateList.valueOf(pillColor)
        // White text on the coloured "tracking" pill, dark text on the light idle pill
        val pillTextColor = when {
            !svc.isTracking -> ContextCompat.getColor(this, R.color.text_primary)
            activityType == ActivityType.RUNNING ||
            activityType == ActivityType.JOGGING ||
            activityType == ActivityType.WALKING -> 0xFFFFFFFF.toInt()
            else -> ContextCompat.getColor(this, R.color.text_primary)
        }
        tvCurrentActivity.setTextColor(pillTextColor)

        // ── GPS indicator ─────────────────────────────────────────────────────
        val hasFineLocation = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        tvGpsIndicator.text = when {
            !svc.isTracking  -> ""
            !hasFineLocation -> "⚠ No GPS"
            svc.gpsAvailable -> "📍 GPS active"
            else             -> "⌛ GPS acquiring…"
        }

        // ── Distance + step sizes ─────────────────────────────────────────────
        tvTotalDist.text = svc.formatDist(svc.walkDistM + svc.jogDistM + svc.runDistM)

        val walkSizeStr = if (userPrefs.isCalibrated) "${"%.2f".format(userPrefs.walkStrideM)} m" else "–"
        val runSizeStr  = if (userPrefs.isCalibrated) "${"%.2f".format(userPrefs.runStrideM)} m" else "–"
        tvStepSizes.text = "Step:  🚶 $walkSizeStr   🏃 $runSizeStr"

        tvWalkStats.text = "🚶 Walk\n${svc.walkSteps} steps\n${svc.formatDist(svc.walkDistM)}"
        tvJogStats.text  = "🏃 Jog\n${svc.jogSteps} steps\n${svc.formatDist(svc.jogDistM)}"
        tvRunStats.text  = "🏃 Run\n${svc.runSteps} steps\n${svc.formatDist(svc.runDistM)}"

        // ── Buttons ───────────────────────────────────────────────────────────
        btnStartStop.text = if (svc.isTracking) "■  Stop" else "▶  Start"
        btnReset.visibility = if (!svc.isTracking && svc.totalSteps > 0) View.VISIBLE else View.GONE

        // ── Session timeline ──────────────────────────────────────────────────
        val periods = svc.activityPeriods.filter { it.type != ActivityType.IDLE }
        val totalMs = periods.sumOf { it.durationMs }.coerceAtLeast(1L)

        if (periods.isEmpty()) {
            layoutTimeline.visibility = View.GONE
            tvEmptyHint.visibility    = View.VISIBLE
        } else {
            tvEmptyHint.visibility    = View.GONE
            layoutTimeline.visibility = View.VISIBLE

            // Rebuild bar when period count changes; always refresh segment weights
            // (live period duration grows every tick — stale weights made the bar look wrong).
            val dp = resources.displayMetrics.density
            if (timelineBar.childCount != periods.size) {
                timelineBar.removeAllViews()
                periods.forEachIndexed { i, p ->
                    val seg = View(this)
                    val color = when (p.type) {
                        ActivityType.RUNNING -> 0xFFFF5722.toInt()   // coral-orange
                        ActivityType.JOGGING -> 0xFFFFA000.toInt()   // amber
                        else                 -> 0xFF14B86A.toInt()   // emerald (walk)
                    }
                    seg.setBackgroundColor(color)
                    val weight = p.durationMs.toFloat() / totalMs
                    val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, weight)
                    if (i > 0) lp.marginStart = (2 * dp).toInt()
                    timelineBar.addView(seg, lp)
                }
            } else {
                periods.forEachIndexed { i, p ->
                    val seg = timelineBar.getChildAt(i) ?: return@forEachIndexed
                    val lp = seg.layoutParams as LinearLayout.LayoutParams
                    lp.weight = p.durationMs.toFloat() / totalMs
                    seg.layoutParams = lp
                }
                timelineBar.requestLayout()
            }

            // Summary line
            val walkMs = periods.filter { it.type == ActivityType.WALKING }.sumOf { it.durationMs }
            val jogMs  = periods.filter { it.type == ActivityType.JOGGING }.sumOf { it.durationMs }
            val runMs  = periods.filter { it.type == ActivityType.RUNNING }.sumOf { it.durationMs }
            tvLogTotals.text =
                "🚶 Walk  ${formatDur(walkMs)}  ·  ${svc.formatDist(svc.walkDistM)}\n" +
                "🏃 Jog   ${formatDur(jogMs)}  ·  ${svc.formatDist(svc.jogDistM)}\n" +
                "🏃 Run   ${formatDur(runMs)}  ·  ${svc.formatDist(svc.runDistM)}"
            tvLogTotals.visibility = View.VISIBLE
        }
    }

    // ── History UI ────────────────────────────────────────────────────────────

    private fun updateHistoryUI() {
        val records = workoutHistory.loadAll()
        tvHistoryEmpty.visibility = if (records.isEmpty()) View.VISIBLE else View.GONE
        rvHistory.visibility      = if (records.isEmpty()) View.GONE    else View.VISIBLE

        if (records.isEmpty()) {
            tvHistorySummary.text = "No workouts yet"
            tvHistoryInsights.visibility = View.GONE
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

        // Auto-generated insights (#A): pace trend, streak, frequency, PBs.
        val insights = WorkoutInsights.compute(records)
        if (insights.isEmpty()) {
            tvHistoryInsights.visibility = View.GONE
        } else {
            tvHistoryInsights.visibility = View.VISIBLE
            tvHistoryInsights.text = "✨ INSIGHTS\n\n" + insights.joinToString("\n")
        }

        rvHistory.adapter = HistoryAdapter(records) { record ->
            // Long-press → delete confirmation
            AlertDialog.Builder(this).setTitle("Delete this workout?")
                .setPositiveButton("Delete") { _, _ ->
                    workoutHistory.delete(record.id)
                    updateHistoryUI()
                }.setNegativeButton("Cancel", null).show()
        }
    }

    private fun formatDist(m: Double) = Format.dist(m)

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
        fun fDist(m: Double) = Format.dist(m)
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
