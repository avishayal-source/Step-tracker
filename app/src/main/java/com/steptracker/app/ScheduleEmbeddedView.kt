package com.steptracker.app

import android.content.*
import android.os.IBinder
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import java.util.concurrent.TimeUnit

class ScheduleEmbeddedView(
    private val activity: AppCompatActivity,
    private val root: View
) : ScheduleManager.Listener {

    private val schedule = mutableListOf<ScheduleItem>()
    private lateinit var scheduleManager: ScheduleManager
    private lateinit var scheduleAdapter: ScheduleEditAdapter
    private lateinit var store: ScheduleStore
    private lateinit var runPersistence: ScheduleRunPersistence
    private lateinit var planStore: TrainingPlanStore

    private lateinit var rvSchedule: RecyclerView
    private lateinit var tvTotalWalk: TextView
    private lateinit var tvTotalJog: TextView
    private lateinit var btnAddWalk: MaterialButton
    private lateinit var btnAddJog: MaterialButton
    private lateinit var btnStartStop: MaterialButton
    private lateinit var btnAbort: MaterialButton
    private lateinit var btnSave: MaterialButton
    private lateinit var btnLoad: MaterialButton
    private lateinit var tvCurrentPeriod: TextView
    private lateinit var tvCountdown: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var layoutRunning: View

    private var stepService: StepTrackerService? = null
    private var isBound = false
    private var pendingRestore = false

    private val serviceConn = object : ServiceConnection {
        override fun onServiceConnected(n: ComponentName?, b: IBinder?) {
            stepService = (b as StepTrackerService.LocalBinder).getService()
            isBound = true
            if (pendingRestore) tryRestoreRunningSchedule()
        }
        override fun onServiceDisconnected(n: ComponentName?) { isBound = false }
    }

    var isRunning = false
        private set
    private var inPrepCountdown = false

    // Id of the planned workout currently loaded in the editor. Tracking the id (rather
    // than just "is it today's") means an overdue session loaded days late still gets
    // marked done against the right entry in the plan.
    private var loadedPlanWorkoutId: Long? = null

    // Set when the loaded workout was pulled in ahead of its scheduled day.
    private var loadedAheadOfSchedule = false

    private lateinit var voice: VoiceCoach
    private val spokenMarkers = mutableSetOf<Int>()

    fun setup() {
        scheduleManager = ScheduleManager(activity, this)
        voice = VoiceCoach(activity)
        store = ScheduleStore(activity)
        runPersistence = ScheduleRunPersistence(activity)
        planStore = TrainingPlanStore(activity)

        rvSchedule      = root.findViewById(R.id.schedRvSchedule)
        tvTotalWalk     = root.findViewById(R.id.schedTvTotalWalk)
        tvTotalJog      = root.findViewById(R.id.schedTvTotalJog)
        btnAddWalk      = root.findViewById(R.id.schedBtnAddWalk)
        btnAddJog       = root.findViewById(R.id.schedBtnAddJog)
        btnStartStop    = root.findViewById(R.id.schedBtnStartStop)
        btnAbort        = root.findViewById(R.id.schedBtnAbort)
        btnSave         = root.findViewById(R.id.schedBtnSave)
        btnLoad         = root.findViewById(R.id.schedBtnLoad)
        tvCurrentPeriod = root.findViewById(R.id.schedTvCurrentPeriod)
        tvCountdown     = root.findViewById(R.id.schedTvCountdown)
        progressBar     = root.findViewById(R.id.schedProgressBar)
        layoutRunning   = root.findViewById(R.id.schedLayoutRunning)

        scheduleAdapter = ScheduleEditAdapter(schedule,
            onChange = { refreshTotals() },
            onLiveEdit = { index, newMins ->
                if (isRunning) scheduleManager.editPeriodDuration(index, newMins)
            }
        )
        rvSchedule.layoutManager = LinearLayoutManager(activity)
        rvSchedule.adapter = scheduleAdapter

        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN,
            ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT
        ) {
            override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, t: RecyclerView.ViewHolder): Boolean {
                if (isRunning) return false
                val from = vh.adapterPosition; val to = t.adapterPosition
                schedule.add(to, schedule.removeAt(from))
                scheduleAdapter.notifyItemMoved(from, to); return true
            }
            override fun onSwiped(vh: RecyclerView.ViewHolder, dir: Int) {
                val pos = vh.adapterPosition
                if (isRunning) {
                    if (pos > scheduleManager.currentIndex) {
                        scheduleManager.removePeriod(pos)
                        schedule.removeAt(pos)
                        scheduleAdapter.notifyItemRemoved(pos)
                        // Keep view list, manager list and totals in sync after removal.
                        syncScheduleFromManager(scheduleManager.getItems())
                        scheduleAdapter.setActiveIndex(scheduleManager.currentIndex)
                        refreshTotals()
                        persistRunState()
                    } else {
                        scheduleAdapter.notifyItemChanged(pos)
                        Toast.makeText(activity, "Can't remove current or past periods", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    schedule.removeAt(pos)
                    scheduleAdapter.notifyItemRemoved(pos)
                    refreshTotals()
                    // Cleared out a preloaded session: let it come back on its own day.
                    if (schedule.isEmpty()) dismissLoadedPreview()
                }
            }
        }).attachToRecyclerView(rvSchedule)

        btnAddWalk.setOnClickListener   { showMinutePicker(ActivityType.WALKING) }
        btnAddJog.setOnClickListener    { showMinutePicker(ActivityType.RUNNING) }
        btnStartStop.setOnClickListener { onPrimaryScheduleClick() }
        btnAbort.setOnClickListener     { abortSchedule() }
        btnSave.setOnClickListener      { saveSchedule() }
        btnLoad.setOnClickListener      { loadSchedule() }
        btnAbort.visibility = View.GONE

        activity.bindService(Intent(activity, StepTrackerService::class.java), serviceConn, Context.BIND_AUTO_CREATE)
        layoutRunning.visibility = View.GONE
        refreshTotals()
        pendingRestore = runPersistence.load() != null
        if (pendingRestore && isBound) tryRestoreRunningSchedule()
    }

    /**
     * Loads the next due workout from the active plan — today's, or the oldest one still
     * waiting from an earlier day — into the editor as warmup + main + cooldown periods.
     * Safe to call repeatedly: it does nothing while a schedule is running or when the
     * editor already has periods, so it never clobbers the user's manual edits.
     */
    fun loadTodaysPlannedWorkout(showToastIfNone: Boolean = false) {
        if (isRunning || inPrepCountdown) return
        if (schedule.isNotEmpty()) return
        val plan = planStore.loadReconciled()?.plan
        // Nothing due today or overdue? Offer tomorrow's — including one that was
        // previously pushed away, which is why it comes back the day before.
        val due = plan?.let { planStore.nextDueWorkout(it) }
            ?: plan?.let { planStore.previewWorkout(it, maxDaysAhead = 1) }
        if (due == null) {
            if (showToastIfNone) Toast.makeText(activity, "No workout waiting right now", Toast.LENGTH_SHORT).show()
            return
        }
        applyPlannedWorkout(due)
    }

    /**
     * After finishing a session, queue up the next one so it's ready to go rather than
     * appearing only the day before. Capped at [PRELOAD_WINDOW_DAYS] so a session a week
     * out doesn't sit in the editor going stale.
     */
    private fun preloadNextWorkout() {
        if (isRunning || inPrepCountdown) return
        val plan = planStore.load() ?: return
        val next = planStore.previewWorkout(plan, maxDaysAhead = PRELOAD_WINDOW_DAYS) ?: return
        applyPlannedWorkout(next)
    }

    /** Loads a specific planned workout, e.g. when Botty's overdue card says "Do it now". */
    fun loadPlannedWorkout(workoutId: Long): Boolean {
        if (isRunning || inPrepCountdown) {
            Toast.makeText(activity, "Finish the running schedule first", Toast.LENGTH_SHORT).show()
            return false
        }
        val target = planStore.load()?.findWorkout(workoutId) ?: return false
        applyPlannedWorkout(target)
        return true
    }

    private fun applyPlannedWorkout(w: PlannedWorkout) {
        schedule.clear()
        schedule.addAll(w.items.map { it.copy(state = ScheduleState.PENDING) })
        scheduleAdapter.notifyDataSetChanged()
        refreshTotals()
        loadedPlanWorkoutId = w.id
        val daysUntil = -w.daysLate()
        loadedAheadOfSchedule = daysUntil > 0
        val msg = when {
            w.isOverdue() -> "⏰ Loaded workout from ${w.dateLabel}: ${w.title}"
            daysUntil == 1 -> "📅 Up next, tomorrow: ${w.title}"
            daysUntil > 1 -> "📅 Up next, ${w.dateLabel}: ${w.title}"
            else -> "📅 Loaded today's workout: ${w.title}"
        }
        Toast.makeText(activity, msg, Toast.LENGTH_LONG).show()
    }

    /**
     * The user replaced a workout that was loaded ahead of its day. It stays in the plan
     * and comes back on its own the day before, so nothing is lost by clearing it.
     */
    private fun dismissLoadedPreview() {
        val id = loadedPlanWorkoutId ?: return
        if (!loadedAheadOfSchedule) return
        planStore.dismissPreview(id)
        loadedPlanWorkoutId = null
        loadedAheadOfSchedule = false
    }

    /** Called when activity is destroyed — persist run state; do not cancel the schedule timer goal. */
    fun onActivityDestroy() {
        if (isRunning || inPrepCountdown) persistRunState()
        if (isBound) activity.unbindService(serviceConn)
        voice.shutdown()
    }

    /** User explicitly stopped the schedule. */
    private fun clearRunState() {
        runPersistence.clear()
    }

    private fun persistRunState() {
        if ((!isRunning && !inPrepCountdown) || schedule.isEmpty()) return
        val items = if (isRunning) scheduleManager.getItems() else schedule
        if (isRunning) syncScheduleFromManager(items)
        val idx = if (isRunning) scheduleManager.currentIndex else 0
        val startMs = items.getOrNull(idx)?.actualStartTime ?: System.currentTimeMillis()
        runPersistence.save(
            ScheduleRunPersistence.Snapshot(
                items = items,
                currentIndex = idx,
                periodStartMs = startMs,
                paused = scheduleManager.isPaused,
                remainingMs = scheduleManager.pausedRemainingMs
            )
        )
    }

    private fun syncScheduleFromManager(managerItems: List<ScheduleItem>) {
        managerItems.forEachIndexed { i, m ->
            if (i < schedule.size) schedule[i] = m.copy()
        }
    }

    private fun tryRestoreRunningSchedule() {
        pendingRestore = false
        val snap = runPersistence.load() ?: return
        schedule.clear()
        schedule.addAll(snap.items)
        scheduleAdapter.notifyDataSetChanged()
        isRunning = true
        inPrepCountdown = false
        layoutRunning.visibility = View.VISIBLE
        btnSave.isEnabled = false
        btnLoad.isEnabled = false
        scheduleAdapter.setRunningMode(true)
        if (stepService?.isTracking != true && !snap.paused) {
            startStepTracking(schedule.firstOrNull()?.type ?: ActivityType.WALKING)
        }
        scheduleManager.resume(snap)
        refreshScheduleControls()
        refreshTotals()
        if (snap.paused) {
            tvCurrentPeriod.text = "Paused"
            val rem = snap.remainingMs
            tvCountdown.text = String.format(
                "%d:%02d",
                TimeUnit.MILLISECONDS.toMinutes(rem),
                TimeUnit.MILLISECONDS.toSeconds(rem) % 60
            )
        }
    }

    private fun refreshScheduleControls() {
        when {
            inPrepCountdown -> {
                btnStartStop.text = "⏸  Pause"
                btnAbort.visibility = View.VISIBLE
            }
            isRunning && scheduleManager.isPaused -> {
                btnStartStop.text = "▶  Resume"
                btnAbort.visibility = View.VISIBLE
            }
            isRunning -> {
                btnStartStop.text = "⏸  Pause"
                btnAbort.visibility = View.VISIBLE
            }
            else -> {
                btnStartStop.text = "▶  Start"
                btnAbort.visibility = View.GONE
            }
        }
    }

    private fun saveSchedule() {
        if (schedule.isEmpty()) { Toast.makeText(activity, "Add periods first", Toast.LENGTH_SHORT).show(); return }
        val input = EditText(activity).apply { hint = "e.g. Couch to 5K Week 1"; setPadding(60,20,60,20) }
        AlertDialog.Builder(activity).setTitle("Save schedule as…").setView(input)
            .setPositiveButton("Save") { _, _ ->
                val name = input.text.toString().trim().ifEmpty { "Schedule" }
                store.save(SavedSchedule(name = name, items = schedule.map { it.copy() }))
                Toast.makeText(activity, "Saved: $name", Toast.LENGTH_SHORT).show()
            }.setNegativeButton("Cancel", null).show()
    }

    private fun loadSchedule() {
        val saved = store.loadAll()
        if (saved.isEmpty()) { Toast.makeText(activity, "No saved schedules yet", Toast.LENGTH_SHORT).show(); return }
        val names = saved.map { s ->
            "${s.name}  (${s.items.size} periods · ${s.items.sumOf{it.durationMinutes}}m)"
        }.toTypedArray()
        AlertDialog.Builder(activity).setTitle("Load schedule")
            .setItems(names) { _, i ->
                val chosen = saved[i]
                dismissLoadedPreview()
                schedule.clear()
                schedule.addAll(chosen.items.map { it.copy(state = ScheduleState.PENDING) })
                scheduleAdapter.notifyDataSetChanged()
                refreshTotals()
                loadedPlanWorkoutId = null
                loadedAheadOfSchedule = false
                Toast.makeText(activity, "Loaded: ${chosen.name}", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("Delete…") { _, _ -> showDeleteDialog(saved) }
            .setNegativeButton("Cancel", null).show()
    }

    private fun showDeleteDialog(saved: List<SavedSchedule>) {
        AlertDialog.Builder(activity).setTitle("Delete a schedule")
            .setItems(saved.map { it.name }.toTypedArray()) { _, i ->
                store.delete(saved[i].id)
                Toast.makeText(activity, "Deleted: ${saved[i].name}", Toast.LENGTH_SHORT).show()
            }.setNegativeButton("Cancel", null).show()
    }

    private fun onPrimaryScheduleClick() {
        when {
            inPrepCountdown -> abortSchedule()
            isRunning && scheduleManager.isPaused -> resumeSchedule()
            isRunning -> pauseSchedule()
            else -> startSchedule()
        }
    }

    private fun startSchedule() {
        if (schedule.isEmpty()) {
            Toast.makeText(activity, "Add at least one period first", Toast.LENGTH_SHORT).show()
            return
        }
        spokenMarkers.clear()
        inPrepCountdown = true
        layoutRunning.visibility = View.VISIBLE
        btnSave.isEnabled = false
        btnLoad.isEnabled = false
        tvCurrentPeriod.text = "Get ready…"
        refreshScheduleControls()
        scheduleManager.startWithPrep(schedule.map { it.copy() })
    }

    private fun pauseSchedule() {
        if (!scheduleManager.pause()) return
        pauseStepTracking()
        persistRunState()
        tvCurrentPeriod.text = "Paused"
        refreshScheduleControls()
        Toast.makeText(activity, "Paused — schedule kept", Toast.LENGTH_SHORT).show()
    }

    private fun resumeSchedule() {
        if (!scheduleManager.resumeFromPause()) return
        val type = scheduleManager.getItems().getOrNull(scheduleManager.currentIndex)?.type
            ?: ActivityType.WALKING
        resumeStepTracking(type)
        persistRunState()
        refreshScheduleControls()
    }

    /** Abort ends the run but keeps the period list in the editor. */
    private fun abortSchedule() {
        scheduleManager.stop()
        stopStepTracking()
        isRunning = false
        inPrepCountdown = false
        clearRunState()
        layoutRunning.visibility = View.GONE
        btnSave.isEnabled = true
        btnLoad.isEnabled = true
        scheduleAdapter.setRunningMode(false)
        for (i in schedule.indices) {
            schedule[i] = schedule[i].copy(
                state = ScheduleState.PENDING,
                actualStartTime = 0L,
                actualEndTime = 0L
            )
        }
        scheduleAdapter.notifyDataSetChanged()
        refreshScheduleControls()
        refreshTotals()
        Toast.makeText(activity, "Stopped — your schedule is still here", Toast.LENGTH_SHORT).show()
    }

    private fun pauseStepTracking() {
        stepService?.pauseTracking()
    }

    private fun resumeStepTracking(type: ActivityType) {
        val svc = stepService ?: return
        if (svc.isPaused) svc.resumeTracking()
        else if (!svc.isTracking) startStepTracking(type)
    }

    override fun onPrepCountdown(secondsLeft: Int) {
        activity.runOnUiThread {
            tvCountdown.text = "$secondsLeft"
            tvCurrentPeriod.text = "Starting in…"
        }
    }

    override fun onPrepFinished() {
        activity.runOnUiThread {
            inPrepCountdown = false
            isRunning = true
            scheduleAdapter.setRunningMode(true)
            startStepTracking(schedule.first().type)
            syncScheduleFromManager(scheduleManager.getItems())
            refreshScheduleControls()
        }
    }

    override fun onTick(periodIndex: Int, remainingMs: Long) {
        activity.runOnUiThread {
            syncScheduleFromManager(scheduleManager.getItems())
            if (periodIndex >= schedule.size) return@runOnUiThread
            val item = schedule[periodIndex]
            progressBar.max = item.durationMs.toInt()
            progressBar.progress = (item.durationMs - remainingMs).toInt()
            tvCountdown.text = formatMs(remainingMs)
            tvCurrentPeriod.text = buildString {
                append(if (item.type == ActivityType.WALKING) "🚶 Walking" else "🏃 Jogging")
                append("  ·  period ${periodIndex + 1} of ${schedule.size}")
            }
            scheduleAdapter.setActiveIndex(periodIndex)
            persistRunState()
            maybeSpeakProgress(periodIndex, remainingMs)
        }
    }

    /**
     * Spoken progress at the quarter points of the whole session (not per period), so the
     * cues scale with workout length. Short sessions stay silent — there's nothing useful
     * to say every couple of minutes.
     */
    private fun maybeSpeakProgress(periodIndex: Int, remainingMs: Long) {
        val items = scheduleManager.getItems()
        if (periodIndex >= items.size) return
        val totalMs = items.sumOf { it.durationMs }
        if (totalMs < MIN_VOICE_SESSION_MS) return

        val elapsedMs = items.take(periodIndex).sumOf { it.durationMs } +
            (items[periodIndex].durationMs - remainingMs).coerceAtLeast(0L)
        val pct = (elapsedMs * 100 / totalMs).toInt()

        val due = VOICE_MARKERS.filter { pct >= it && it !in spokenMarkers }
        if (due.isEmpty()) return
        // Resuming mid-workout can cross several markers at once; only the latest is worth saying.
        spokenMarkers.addAll(due)
        voice.say(progressLine(due.max(), elapsedMs, totalMs))
    }

    private fun progressLine(marker: Int, elapsedMs: Long, totalMs: Long): String {
        if (marker == 50) return "You're halfway there. Keep it up."
        val doneMin = Math.round(elapsedMs / 60_000.0).toInt()
        val totalMin = Math.round(totalMs / 60_000.0).toInt()
        return "$doneMin of $totalMin minutes done."
    }

    override fun onPeriodComplete(completedIndex: Int, nextIndex: Int?) {
        activity.runOnUiThread {
            syncScheduleFromManager(scheduleManager.getItems())
            scheduleAdapter.notifyDataSetChanged()
            persistRunState()
        }
    }

    override fun onScheduleComplete() {
        activity.runOnUiThread {
            isRunning = false
            inPrepCountdown = false
            clearRunState()
            layoutRunning.visibility = View.GONE
            btnSave.isEnabled = true; btnLoad.isEnabled = true
            scheduleAdapter.setRunningMode(false); scheduleAdapter.setActiveIndex(-1)
            scheduleAdapter.notifyDataSetChanged()
            stopStepTracking()
            spokenMarkers.clear()
            refreshScheduleControls()

            val finished = loadedPlanWorkoutId?.let { id ->
                val w = planStore.load()?.findWorkout(id)
                planStore.markDone(id)
                loadedPlanWorkoutId = null
                loadedAheadOfSchedule = false
                w
            }

            Toast.makeText(activity, "🎉 Schedule complete!", Toast.LENGTH_LONG).show()

            if (finished != null && finished.daysLate() < 0) {
                showAheadOfPlanNote(finished)
            }
            if (finished != null) preloadNextWorkout()
        }
    }

    /**
     * Completing a session before its scheduled day is fine, but the plan's spacing exists
     * for recovery — so say so, and leave the remaining dates where they are.
     */
    private fun showAheadOfPlanNote(w: PlannedWorkout) {
        val daysEarly = -w.daysLate()
        AlertDialog.Builder(activity)
            .setTitle("You're ahead of plan")
            .setMessage(
                "${w.title} was scheduled for ${w.dateLabel} — you did it $daysEarly day" +
                    "${if (daysEarly == 1) "" else "s"} early.\n\n" +
                    "It's marked done and the rest of your plan keeps its dates. Take a recovery " +
                    "day before the next session rather than pulling everything forward."
            )
            .setPositiveButton("Got it", null)
            .show()
    }

    override fun onScheduleEdited() {
        activity.runOnUiThread {
            syncScheduleFromManager(scheduleManager.getItems())
            scheduleAdapter.notifyDataSetChanged()
            refreshTotals()
            persistRunState()
        }
    }

    private fun startStepTracking(initialType: ActivityType) {
        if (stepService?.isTracking == true) return
        ContextCompat.startForegroundService(activity,
            Intent(activity, StepTrackerService::class.java).apply {
                action = StepTrackerService.ACTION_START
                putExtra(StepTrackerService.EXTRA_INITIAL_TYPE, initialType.name)
            })
    }

    private fun stopStepTracking() {
        stepService?.stopTracking()
    }

    private fun showMinutePicker(type: ActivityType) {
        val label = if (type == ActivityType.WALKING) "Walking" else "Jogging"
        val picker = NumberPicker(activity).apply { minValue = 1; maxValue = 60; value = 2 }
        android.app.AlertDialog.Builder(activity)
            .setTitle("Add $label period").setMessage("Duration (minutes):").setView(picker)
            .setPositiveButton("Add") { _, _ ->
                val item = ScheduleItem(type, picker.value)
                schedule.add(item)
                if (isRunning) scheduleManager.appendPeriod(item)
                scheduleAdapter.notifyItemInserted(schedule.size - 1)
                refreshTotals()
            }.setNegativeButton("Cancel", null).show()
    }

    private fun refreshTotals() {
        val src = if (isRunning && scheduleManager.isRunning) scheduleManager.getItems() else schedule
        val wm = src.filter { it.type == ActivityType.WALKING }.sumOf { it.durationMinutes }
        val jm = src.filter { it.type == ActivityType.RUNNING  }.sumOf { it.durationMinutes }
        tvTotalWalk.text = "🚶 Walk: ${formatMins(wm)}"
        tvTotalJog.text  = "🏃 Jog: ${formatMins(jm)}"
    }

    private fun formatMins(m: Int) = if (m >= 60) "${m/60}h ${m%60}m" else "${m}m"
    private fun formatMs(ms: Long): String {
        val m = TimeUnit.MILLISECONDS.toMinutes(ms)
        val s = TimeUnit.MILLISECONDS.toSeconds(ms) % 60
        return "%d:%02d".format(m, s)
    }

    companion object {
        private val VOICE_MARKERS = listOf(25, 50, 75)

        /** Below this, quarter-point cues would fire every few minutes for no benefit. */
        private const val MIN_VOICE_SESSION_MS = 10 * 60_000L

        /** How far ahead a finished-early preload may reach. */
        const val PRELOAD_WINDOW_DAYS = 3
    }
}
