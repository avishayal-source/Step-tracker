package com.steptracker.wear.sync

import android.content.Context
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Last PATH_TODAY snapshot from the phone (M2).
 */
data class TodaySnapshot(
    val syncedAtMs: Long,
    val dateMs: Long,
    val workoutId: Long,
    val title: String,
    val periods: List<Period>,
    val walkStrideM: Double,
    val runStrideM: Double,
    val calibrated: Boolean,
    val rawJson: String
) {
    data class Period(val type: String, val mins: Int) {
        val label: String
            get() = when (type) {
                "WALKING" -> "Walk ${mins} min"
                "JOGGING" -> "Jog ${mins} min"
                "RUNNING" -> "Run ${mins} min"
                else -> "$type ${mins} min"
            }
    }

    val totalMins: Int get() = periods.sumOf { it.mins }

    /** "Today" / "Overdue from …" / date — matches phone Botty wording. */
    fun dueLabel(now: Long = System.currentTimeMillis()): String {
        if (dateMs <= 0L) return ""
        val day = dayIndex(dateMs)
        val today = dayIndex(now)
        val dateLabel = SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(Date(dateMs))
        return when {
            day == today -> "Today’s workout"
            day < today -> {
                val late = (today - day).toInt()
                "Overdue · $dateLabel (${late}d late)"
            }
            else -> "Upcoming · $dateLabel"
        }
    }

    companion object {
        fun dayIndex(ms: Long): Long {
            val cal = Calendar.getInstance().apply {
                timeInMillis = ms
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            return cal.timeInMillis
        }
    }
}

object TodayCache {
    private const val PREFS = "ywalk_today"
    private const val KEY_JSON = "json"

    fun save(context: Context, json: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_JSON, json)
            .apply()
    }

    fun load(context: Context): TodaySnapshot? {
        val json = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_JSON, null) ?: return null
        return parse(json)
    }

    fun parse(json: String): TodaySnapshot? = try {
        val o = JSONObject(json)
        val periodsJson = o.optJSONArray("periods")
        val periods = buildList {
            if (periodsJson != null) {
                for (i in 0 until periodsJson.length()) {
                    val p = periodsJson.getJSONObject(i)
                    add(TodaySnapshot.Period(p.getString("type"), p.getInt("mins")))
                }
            }
        }
        TodaySnapshot(
            syncedAtMs = o.optLong("syncedAtMs", 0L),
            dateMs = o.optLong("dateMs", 0L),
            workoutId = o.optLong("workoutId", 0L),
            title = o.optString("title", ""),
            periods = periods,
            walkStrideM = o.optDouble("walk_stride", 0.39),
            runStrideM = o.optDouble("run_stride", 0.60),
            calibrated = o.optBoolean("calibrated", false),
            rawJson = json
        )
    } catch (_: Exception) {
        null
    }
}
