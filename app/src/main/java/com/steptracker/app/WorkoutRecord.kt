package com.steptracker.app

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.text.SimpleDateFormat
import java.util.*

/**
 * A completed workout session. Stored as a row in the Room `workouts` table.
 *
 * The computed properties (totalDistM, dateLabel, ...) have no backing field,
 * so Room ignores them automatically.
 */
@Entity(tableName = "workouts")
data class WorkoutRecord(
    @PrimaryKey val id: Long = System.currentTimeMillis(),
    val dateMs: Long,                   // session start wall-clock ms
    val walkSteps: Int,
    val runSteps: Int,
    val walkDistM: Double,
    val runDistM: Double,
    val walkDurationMs: Long,
    val runDurationMs: Long
) {
    val totalDistM   get() = walkDistM + runDistM
    val totalSteps   get() = walkSteps + runSteps
    val totalDurMs   get() = walkDurationMs + runDurationMs
    val dateLabel    get() = SimpleDateFormat("dd MMM yyyy  HH:mm", Locale.getDefault()).format(Date(dateMs))
}
