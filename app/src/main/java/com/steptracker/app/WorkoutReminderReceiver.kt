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
 * Posts the "workout tomorrow" reminder notification. Reminders are scheduled with
 * [AlarmManager] (inexact, no special permission needed) for 18:00 the day before
 * each planned workout. Tapping the notification opens the Schedule tab.
 */
class WorkoutReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val title = intent.getStringExtra(EXTRA_TITLE) ?: "Workout"
        val dateLabel = intent.getStringExtra(EXTRA_DATE_LABEL) ?: "tomorrow"
        val notifId = intent.getIntExtra(EXTRA_REQUEST_CODE, 1)

        ensureChannel(context)

        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_OPEN_SCHEDULE, true)
        }
        val contentPi = PendingIntent.getActivity(
            context, notifId, openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notif = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_my_calendar)
            .setContentTitle("Workout tomorrow 🏃")
            .setContentText("$title · $dateLabel. Tap to get ready.")
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText("$title is scheduled for $dateLabel.\nTap to open Y Walk — your warmup, workout and cooldown will be ready in the Schedule tab."))
            .setAutoCancel(true)
            .setContentIntent(contentPi)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(notifId, notif)
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS not granted — nothing else we can do here.
        }
    }

    private fun ensureChannel(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Workout reminders", NotificationManager.IMPORTANCE_DEFAULT)
                    .apply { description = "Reminds you the day before each scheduled workout" }
            )
        }
    }

    companion object {
        const val CHANNEL_ID = "workout_reminders"
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_DATE_LABEL = "extra_date_label"
        const val EXTRA_REQUEST_CODE = "extra_request_code"
        const val ACTION_REMIND = "com.steptracker.app.ACTION_WORKOUT_REMINDER"

        private const val DAY_MS = 86_400_000L
        private const val REMINDER_HOUR = 18   // 6 PM the evening before

        /** Cancels any previously scheduled reminders for [plan]'s workouts. */
        fun cancelAll(context: Context, plan: TrainingPlan) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            plan.workouts.forEach { am.cancel(buildPendingIntent(context, it)) }
        }

        /** (Re)schedules a day-before reminder for every not-done future workout. */
        fun scheduleAll(context: Context, plan: TrainingPlan) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val now = System.currentTimeMillis()
            for (w in plan.workouts) {
                if (w.done) continue
                val remindAt = reminderTimeFor(w.dateMs)
                if (remindAt <= now) continue
                try {
                    am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, remindAt, buildPendingIntent(context, w))
                } catch (_: Exception) {
                    am.set(AlarmManager.RTC_WAKEUP, remindAt, buildPendingIntent(context, w))
                }
            }
        }

        private fun reminderTimeFor(workoutMidnightMs: Long): Long {
            val cal = Calendar.getInstance().apply {
                timeInMillis = workoutMidnightMs - DAY_MS
                set(Calendar.HOUR_OF_DAY, REMINDER_HOUR)
                set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }
            return cal.timeInMillis
        }

        private fun buildPendingIntent(context: Context, w: PlannedWorkout): PendingIntent {
            val intent = Intent(context, WorkoutReminderReceiver::class.java).apply {
                action = ACTION_REMIND
                putExtra(EXTRA_TITLE, w.title)
                putExtra(EXTRA_DATE_LABEL, w.dateLabel)
                putExtra(EXTRA_REQUEST_CODE, w.requestCode)
            }
            return PendingIntent.getBroadcast(
                context, w.requestCode, intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        }
    }
}
