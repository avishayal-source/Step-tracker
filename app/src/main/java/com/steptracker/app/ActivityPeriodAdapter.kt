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
        val viewBar: View        = view.findViewById(R.id.viewTypeBar)
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
                holder.tvType.text = "🏃 Running"
                holder.card.setCardBackgroundColor(ctx.getColor(R.color.color_running))
                holder.viewBar.setBackgroundColor(0xFFFF5722.toInt())
            }
            ActivityType.JOGGING -> {
                holder.tvType.text = "🏃 Jogging"
                holder.card.setCardBackgroundColor(ctx.getColor(R.color.color_running))
                holder.viewBar.setBackgroundColor(0xFFFFA000.toInt())
            }
            ActivityType.WALKING -> {
                holder.tvType.text = "🚶 Walking"
                holder.card.setCardBackgroundColor(ctx.getColor(R.color.color_walking))
                holder.viewBar.setBackgroundColor(0xFF14B86A.toInt())
            }
            ActivityType.IDLE -> {
                holder.tvType.text = "⏸ Idle"
                holder.card.setCardBackgroundColor(ctx.getColor(R.color.color_idle))
                holder.viewBar.setBackgroundColor(0xFF44475E.toInt())
            }
        }

        holder.tvStart.text    = timeFmt.format(Date(period.startTime))
        holder.tvEnd.text      = if (period.endTime > 0) "→ ${timeFmt.format(Date(period.endTime))}"
                                  else "→ ongoing…"
        holder.tvDuration.text = formatDuration(period.durationMs)
        holder.tvSteps.text    = "${period.steps} steps"
        val distLabel = if (period.usingGps) "📍 ${formatDist(period.distanceMeters)}"
                        else "👟 ${formatDist(period.distanceMeters)}"
        holder.tvDist.text = distLabel
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

    private fun formatDist(m: Double) = Format.dist(m)
}
