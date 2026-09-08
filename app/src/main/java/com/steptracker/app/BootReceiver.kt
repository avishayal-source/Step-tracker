package com.steptracker.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * AlarmManager drops every pending alarm on reboot (and on app update), so without this
 * the user silently stops getting workout reminders until they next open Botty.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.intent.action.QUICKBOOT_POWERON" -> {
                if (TrainingPlanStore(context).hasActivePlan()) {
                    WorkoutReminderReceiver.ensureDailyCheck(context)
                }
            }
        }
    }
}
