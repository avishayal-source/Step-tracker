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

    private lateinit var rvSchedule: RecyclerView
    private lateinit var tvTotalWalk: TextView
    private lateinit var tvTotalJog: TextView
    private lateinit var btnAddWalk: MaterialButton
    private lateinit var btnAddJog: MaterialButton
    private lateinit var btnStartStop: MaterialButton
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

    fun setup() {
        scheduleManager = ScheduleManager(this)
        store = ScheduleStore(activity)
        runPersistence = ScheduleRunPersistence(activity)

        rvSchedule      = root.findViewById(R.id.schedRvSchedule)
        tvTotalWalk     = root.findViewById(R.id.schedTvTotalWalk)
        tvTotalJog      = root.findViewById(R.id.schedTvTotalJog)
        btnAddWalk      = root.findViewById(R.id.schedBtnAddWalk)
        btnAddJog       = root.findViewById(R.id.schedBtnAddJog)
        btnStartStop    = root.findViewById(R.id.schedBtnStartStop)
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
                    } else {
                        scheduleAdapter.notifyItemChanged(pos)
                        Toast.makeText(activity, "Can't remove current or past periods", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    schedule.removeAt(pos)
                    scheduleAdapter.notifyItemRemoved(pos)
                    refreshTotals()
                }
            }
        }).attachToRecyclerView(rvSchedule)

        btnAddWalk.setOnClickListener   { showMinutePicker(ActivityType.WALKING) }
        btnAddJog.setOnClickListener    { showMinutePicker(ActivityType.RUNNING) }
        btnStartStop.setOnClickListener { toggleSchedule() }
        btnSave.setOnClickListener      { saveSchedule() }
        btnLoad.setOnClickListener      { loadSchedule() }

        activity.bindService(Intent(activity, StepTrackerService::class.java), serviceConn, Context.BIND_AUTO_CREATE)
        layoutRunning.visibility = View.GONE
        refreshTotals()
        pendingRestore = runPersistence.load() != null
        if (pendingRestore && isBound) tryRestoreRunningSchedule()
    }

    /** Called when activity is destroyed — persist run state; do not cancel the schedule timer goal. */
    fun onActivityDestroy() {
        if (isRunning || inPrepCountdown) persistRunState()
        if (isBound) activity.unbindService(serviceConn)
    }

    /** User explicitly stopped the schedule. */
    private fun clearRunState() {
        runPersistence.clear()
    }

    private fun persistRunState() {
        if (!isRunning || schedule.isEmpty()) return
        val items = scheduleManager.getItems()
        syncScheduleFromManager(items)
        val idx = scheduleManager.currentIndex
        val startMs = items.getOrNull(idx)?.actualStartTime ?: System.currentTimeMillis()
        runPersistence.save(ScheduleRunPersistence.Snapshot(items, idx, startMs))
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
        btnStartStop.text = "■  Stop"
        btnSave.isEnabled = false
        btnLoad.isEnabled = false
        scheduleAdapter.setRunningMode(true)
        if (stepService?.isTracking != true) {
            startStepTracking(schedule.firstOrNull()?.type ?: ActivityType.WALKING)
        }
        scheduleManager.resume(snap)
        refreshTotals()
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
                schedule.clear()
                schedule.addAll(chosen.items.map { it.copy(state = ScheduleState.PENDING) })
                scheduleAdapter.notifyDataSetChanged()
                refreshTotals()
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

    private fun toggleSchedule() {
        if (isRunning || inPrepCountdown) {
            scheduleManager.stop()
            stopStepTracking()
            isRunning = false
            inPrepCountdown = false
            clearRunState()
            layoutRunning.visibility = View.GONE
            btnStartStop.text = "▶  Start"
            btnSave.isEnabled = true; btnLoad.isEnabled = true
            scheduleAdapter.setRunningMode(false); scheduleAdapter.notifyDataSetChanged()
        } else {
            if (schedule.isEmpty()) { Toast.makeText(activity,"Add at least one period first",Toast.LENGTH_SHORT).show(); return }
            inPrepCountdown = true
            layoutRunning.visibility = View.VISIBLE
            btnStartStop.text = "■  Stop"
            btnSave.isEnabled = false; btnLoad.isEnabled = false
            tvCurrentPeriod.text = "Get ready…"
            scheduleManager.startWithPrep(schedule.map { it.copy() })
        }
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
        }
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
            btnStartStop.text = "▶  Start"
            btnSave.isEnabled = true; btnLoad.isEnabled = true
            scheduleAdapter.setRunningMode(false); scheduleAdapter.setActiveIndex(-1)
            scheduleAdapter.notifyDataSetChanged()
            stopStepTracking()
            Toast.makeText(activity, "🎉 Schedule complete!", Toast.LENGTH_LONG).show()
        }
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
}
