package com.steptracker.app

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import java.util.Calendar

/**
 * Botty's reminder engine.
 *
 * Rather than one alarm per planned workout (a snapshot that goes stale as soon as the
 * plan changes, and never fires again once a day is missed), a single daily alarm at
 * [CHECK_HOUR] re-reads the plan and decides what to say:
 *
 *  - a workout is scheduled for tomorrow,
 *  - something is still overdue ("you're behind"),
 *  - the weekly progress summary, on [WEEKLY_SUMMARY_DAY].
 *
 * The alarm re-arms itself on each fire, and [BootReceiver] re-arms it after a reboot.
 */
class WorkoutReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        ensureChannel(context)

        // Legacy per-workout alarm from a plan scheduled by an older build.
        if (intent.action == ACTION_REMIND && intent.hasExtra(EXTRA_TITLE)) {
            val title = intent.getStringExtra(EXTRA_TITLE) ?: "Workout"
            val dateLabel = intent.getStringExtra(EXTRA_DATE_LABEL) ?: "tomorrow"
            val notifId = intent.getIntExtra(EXTRA_REQUEST_CODE, ID_DAY_BEFORE)
            notify(
                context, notifId,
                "Workout tomorrow 🏃",
                "$title · $dateLabel. Tap to get ready.",
                "$title is scheduled for $dateLabel.\nTap to open Y Walk — your warmup, workout and cooldown will be ready in the Schedule tab.",
                MainActivity.EXTRA_OPEN_SCHEDULE
            )
            ensureDailyCheck(context)
            return
        }

        runDailyCheck(context)
        ensureDailyCheck(context)
    }

    /** Reads the plan and posts whichever reminders apply right now. */
    private fun runDailyCheck(context: Context) {
        val store = TrainingPlanStore(context)
        val plan = store.loadReconciled()?.plan ?: return
        val now = System.currentTimeMillis()

        val today = PlannedWorkout.dayIndex(now)
        val overdue = plan.overdueWorkouts(now)
        val tomorrow = plan.pendingWorkouts()
            .firstOrNull { PlannedWorkout.dayIndex(it.dateMs) == today + 1 }

        if (overdue.isNotEmpty()) {
            val oldest = overdue.first()
            val n = overdue.size
            val headline = if (n == 1) "1 workout still waiting ⏰" else "$n workouts still waiting ⏰"
            val body = if (n == 1) {
                "${oldest.title} from ${oldest.dateLabel} is still on your schedule."
            } else {
                "Your oldest is ${oldest.title} from ${oldest.dateLabel}."
            }
            val remaining = (PlanHygiene.GRACE_DAYS - oldest.daysLate(now)).coerceAtLeast(0)
            val expiry = if (remaining <= 0) {
                "Botty will let it go after today — do it or skip it."
            } else {
                "It stays on your schedule for $remaining more day${if (remaining == 1) "" else "s"}."
            }
            notify(
                context, ID_OVERDUE, headline, body,
                "$body\n$expiry\n\nTap to open the Schedule tab and start it — don't double up to catch up.",
                MainActivity.EXTRA_OPEN_SCHEDULE
            )
        }

        if (tomorrow != null) {
            notify(
                context, ID_DAY_BEFORE,
                "Workout tomorrow 🏃",
                "${tomorrow.title} · ${tomorrow.dateLabel}. Tap to get ready.",
                "${tomorrow.title} is scheduled for ${tomorrow.dateLabel}.\nTap to open Y Walk — your warmup, workout and cooldown will be ready in the Schedule tab.",
                MainActivity.EXTRA_OPEN_SCHEDULE
            )
        }

        val dow = Calendar.getInstance().apply { timeInMillis = now }.get(Calendar.DAY_OF_WEEK)
        if (dow == WEEKLY_SUMMARY_DAY) {
            postWeeklySummary(context, plan, now, overdue.size)
        }
    }

    private fun postWeeklySummary(context: Context, plan: TrainingPlan, now: Long, overdueCount: Int) {
        val doneTotal = plan.workouts.count { it.done }
        val total = plan.workouts.size
        val week = plan.displayWeek(now)
        val next = plan.pendingWorkouts().minByOrNull { it.dateMs }

        val lines = buildString {
            append("Program week $week · $doneTotal of $total workouts done.")
            if (overdueCount > 0) {
                append("\n$overdueCount still waiting from earlier days.")
            }
            if (next != null) {
                append("\nUp next: ${next.title} — ${next.dueLabel(now)}.")
            } else {
                append("\nNothing left on the plan — nice work! 🎉")
            }
        }

        notify(
            context, ID_WEEKLY,
            "Your week with Botty 📊",
            "Week $week · $doneTotal/$total done",
            lines,
            MainActivity.EXTRA_OPEN_COACH
        )
    }

    private fun notify(
        context: Context,
        id: Int,
        title: String,
        text: String,
        bigText: String,
        openExtra: String
    ) {
        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(openExtra, true)
        }
        val contentPi = PendingIntent.getActivity(
            context, id, openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notif = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_my_calendar)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(bigText))
            .setAutoCancel(true)
            .setContentIntent(contentPi)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(id, notif)
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS not granted — nothing else we can do here.
        }
    }

    private fun ensureChannel(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Workout reminders", NotificationManager.IMPORTANCE_DEFAULT)
                    .apply { description = "Workout reminders, overdue nudges and weekly progress from Botty" }
            )
        }
    }

    companion object {
        const val CHANNEL_ID = "workout_reminders"
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_DATE_LABEL = "extra_date_label"
        const val EXTRA_REQUEST_CODE = "extra_request_code"
        const val ACTION_REMIND = "com.steptracker.app.ACTION_WORKOUT_REMINDER"
        const val ACTION_DAILY_CHECK = "com.steptracker.app.ACTION_PLAN_DAILY_CHECK"

        private const val DAY_MS = 86_400_000L
        private const val CHECK_HOUR = 18            // 6 PM daily plan check
        private const val WEEKLY_SUMMARY_DAY = Calendar.SUNDAY

        private const val RC_DAILY_CHECK = 90_001
        private const val ID_DAY_BEFORE = 90_101
        private const val ID_OVERDUE = 90_102
        private const val ID_WEEKLY = 90_103

        /** Arms (or re-arms) the daily plan check for the next [CHECK_HOUR]. */
        fun ensureDailyCheck(context: Context) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val at = nextCheckTime()
            val pi = dailyCheckIntent(context)
            try {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            } catch (_: Exception) {
                am.set(AlarmManager.RTC_WAKEUP, at, pi)
            }
        }

        /**
         * Kept for call-site compatibility: (re)arms Botty's reminders for [plan] and
         * clears any per-workout alarms left over from an older build.
         */
        fun scheduleAll(context: Context, plan: TrainingPlan) {
            cancelLegacyPerWorkout(context, plan)
            ensureDailyCheck(context)
        }

        /** Cancels every reminder for [plan], including the daily check. */
        fun cancelAll(context: Context, plan: TrainingPlan) {
            cancelLegacyPerWorkout(context, plan)
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.cancel(dailyCheckIntent(context))
        }

        private fun cancelLegacyPerWorkout(context: Context, plan: TrainingPlan) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            plan.workouts.forEach { w ->
                val intent = Intent(context, WorkoutReminderReceiver::class.java).apply {
                    action = ACTION_REMIND
                }
                am.cancel(
                    PendingIntent.getBroadcast(
                        context, w.requestCode, intent,
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                    )
                )
            }
        }

        private fun dailyCheckIntent(context: Context): PendingIntent {
            val intent = Intent(context, WorkoutReminderReceiver::class.java).apply {
                action = ACTION_DAILY_CHECK
            }
            return PendingIntent.getBroadcast(
                context, RC_DAILY_CHECK, intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        }

        private fun nextCheckTime(): Long {
            val cal = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, CHECK_HOUR)
                set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }
            if (cal.timeInMillis <= System.currentTimeMillis()) {
                cal.timeInMillis = cal.timeInMillis + DAY_MS
            }
            return cal.timeInMillis
        }
    }
}
