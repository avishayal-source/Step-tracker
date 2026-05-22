package com.steptracker.app

import android.view.*
import android.widget.*
import androidx.recyclerview.widget.RecyclerView

class ScheduleEditAdapter(
    private val items: MutableList<ScheduleItem>,
    private val onChange: () -> Unit,
    private val onLiveEdit: ((index: Int, newMins: Int) -> Unit)? = null
) : RecyclerView.Adapter<ScheduleEditAdapter.VH>() {

    private var runningMode = false
    private var activeIndex = -1

    fun setRunningMode(v: Boolean) { runningMode = v }
    fun setActiveIndex(i: Int) { activeIndex = i; notifyDataSetChanged() }

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        val tvLabel: TextView     = v.findViewById(R.id.tvRowLabel)
        val tvDuration: TextView  = v.findViewById(R.id.tvRowDuration)
        val btnMinus: ImageButton = v.findViewById(R.id.btnMinus)
        val btnPlus: ImageButton  = v.findViewById(R.id.btnPlus)
        val ivDone: ImageView     = v.findViewById(R.id.ivDone)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_schedule_row, parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, pos: Int) {
        val item = items[pos]
        val ctx  = h.itemView.context

        h.tvLabel.text    = if (item.type == ActivityType.WALKING) "🚶 Walking" else "🏃 Jogging"
        h.tvDuration.text = "${item.durationMinutes} min"

        val bg = when {
            runningMode && pos == activeIndex          -> ctx.getColor(R.color.color_active_period)
            runningMode && item.state == ScheduleState.DONE -> ctx.getColor(R.color.color_done_period)
            item.type == ActivityType.WALKING          -> ctx.getColor(R.color.color_walking_dim)
            else                                       -> ctx.getColor(R.color.color_running_dim)
        }
        h.itemView.setBackgroundColor(bg)

        h.ivDone.visibility = if (runningMode && item.state == ScheduleState.DONE) View.VISIBLE else View.GONE

        // ± buttons visible in edit mode AND during run (live edit #6)
        val isPast = runningMode && pos < activeIndex
        h.btnMinus.visibility = if (isPast) View.INVISIBLE else View.VISIBLE
        h.btnPlus.visibility  = if (isPast) View.INVISIBLE else View.VISIBLE

        h.btnMinus.setOnClickListener {
            val p = h.adapterPosition; if (p < 0) return@setOnClickListener
            if (items[p].durationMinutes > 1) {
                items[p] = items[p].copy(durationMinutes = items[p].durationMinutes - 1)
                notifyItemChanged(p)
                onLiveEdit?.invoke(p, items[p].durationMinutes)
                onChange()
            }
        }
        h.btnPlus.setOnClickListener {
            val p = h.adapterPosition; if (p < 0) return@setOnClickListener
            if (items[p].durationMinutes < 120) {
                items[p] = items[p].copy(durationMinutes = items[p].durationMinutes + 1)
                notifyItemChanged(p)
                onLiveEdit?.invoke(p, items[p].durationMinutes)
                onChange()
            }
        }
    }
}
