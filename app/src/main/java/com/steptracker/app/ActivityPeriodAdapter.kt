package com.steptracker.app

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.cardview.widget.CardView
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

class ActivityPeriodAdapter(private val periods: MutableList<ActivityPeriod>) :
    RecyclerView.Adapter<ActivityPeriodAdapter.PeriodViewHolder>() {

    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    inner class PeriodViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val card: CardView       = view.findViewById(R.id.card)
        val tvType: TextView     = view.findViewById(R.id.tvActivityType)
        val tvStart: TextView    = view.findViewById(R.id.tvStartTime)
        val tvEnd: TextView      = view.findViewById(R.id.tvEndTime)
        val tvDuration: TextView = view.findViewById(R.id.tvDuration)
        val tvSteps: TextView    = view.findViewById(R.id.tvSteps)
        val tvDist: TextView     = view.findViewById(R.id.tvDistance)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        PeriodViewHolder(LayoutInflater.from(parent.context)
            .inflate(R.layout.item_activity_period, parent, false))

    override fun onBindViewHolder(holder: PeriodViewHolder, position: Int) {
        val period = periods[periods.size - 1 - position]   // newest first
        val ctx = holder.itemView.context

        when (period.type) {
            ActivityType.RUNNING -> {
                holder.tvType.text = "🏃 Running / Jogging"
                holder.card.setCardBackgroundColor(ctx.getColor(R.color.color_running))
            }
            ActivityType.WALKING -> {
                holder.tvType.text = "🚶 Walking"
                holder.card.setCardBackgroundColor(ctx.getColor(R.color.color_walking))
            }
            ActivityType.IDLE -> {
                holder.tvType.text = "⏸ Idle"
                holder.card.setCardBackgroundColor(ctx.getColor(R.color.color_idle))
            }
        }

        holder.tvStart.text    = "Start: ${timeFmt.format(Date(period.startTime))}"
        holder.tvEnd.text      = if (period.endTime > 0) "End:   ${timeFmt.format(Date(period.endTime))}"
                                  else "End:   ongoing…"
        holder.tvDuration.text = "Duration: ${formatDuration(period.durationMs)}"
        holder.tvSteps.text    = "Steps: ${period.steps}"
        holder.tvDist.text     = "Dist:  ${formatDist(period.distanceMeters)}"
    }

    override fun getItemCount() = periods.size

    fun updateData(newList: List<ActivityPeriod>) {
        periods.clear(); periods.addAll(newList); notifyDataSetChanged()
    }

    private fun formatDuration(ms: Long): String {
        val m = TimeUnit.MILLISECONDS.toMinutes(ms)
        val s = TimeUnit.MILLISECONDS.toSeconds(ms) % 60
        return if (m > 0) "${m}m ${s}s" else "${s}s"
    }

    private fun formatDist(m: Double) = if (m >= 1000) "${"%.2f".format(m/1000)} km" else "${m.toInt()} m"
}
