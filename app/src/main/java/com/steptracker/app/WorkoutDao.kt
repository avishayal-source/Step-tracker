package com.steptracker.app

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * Data access for workouts. Beyond plain CRUD, the analytics queries here are
 * what unlock the "ask your data" AI features (avg speed, frequency, etc.).
 */
@Dao
interface WorkoutDao {

    // ── CRUD ───────────────────────────────────────────────────────────────
    @Query("SELECT * FROM workouts ORDER BY dateMs DESC")
    fun getAll(): List<WorkoutRecord>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(record: WorkoutRecord)

    @Query("DELETE FROM workouts WHERE id = :id")
    fun deleteById(id: Long)

    @Query("DELETE FROM workouts")
    fun clearAll()

    @Query("SELECT COUNT(*) FROM workouts")
    fun count(): Int

    // Trim to the most recent N sessions (keeps the table bounded).
    @Query("""
        DELETE FROM workouts WHERE id NOT IN (
            SELECT id FROM workouts ORDER BY dateMs DESC LIMIT :keep
        )
    """)
    fun trimToMostRecent(keep: Int)

    // ── Analytics ────────────────────────────────────────────────────────────

    /** Average running speed (m/s) over the last [lastN] workouts; null if no run data. */
    @Query("""
        SELECT CASE WHEN SUM(runDurationMs) > 0
                    THEN SUM(runDistM) / (SUM(runDurationMs) / 1000.0)
                    ELSE NULL END
        FROM (SELECT runDistM, runDurationMs FROM workouts ORDER BY dateMs DESC LIMIT :lastN)
    """)
    fun avgRunningSpeedMps(lastN: Int): Double?

    /** Average walking speed (m/s) over the last [lastN] workouts; null if no walk data. */
    @Query("""
        SELECT CASE WHEN SUM(walkDurationMs) > 0
                    THEN SUM(walkDistM) / (SUM(walkDurationMs) / 1000.0)
                    ELSE NULL END
        FROM (SELECT walkDistM, walkDurationMs FROM workouts ORDER BY dateMs DESC LIMIT :lastN)
    """)
    fun avgWalkingSpeedMps(lastN: Int): Double?

    /** Number of workouts since a given epoch-ms timestamp (e.g. last 6 months). */
    @Query("SELECT COUNT(*) FROM workouts WHERE dateMs >= :sinceMs")
    fun countSince(sinceMs: Long): Int

    /** Total distance (m) across all workouts. */
    @Query("SELECT COALESCE(SUM(walkDistM + runDistM), 0) FROM workouts")
    fun totalDistanceM(): Double

    /** Total run distance (m) since a timestamp. */
    @Query("SELECT COALESCE(SUM(runDistM), 0) FROM workouts WHERE dateMs >= :sinceMs")
    fun runDistanceSince(sinceMs: Long): Double

    /** Longest single-session total distance (m). */
    @Query("SELECT COALESCE(MAX(walkDistM + runDistM), 0) FROM workouts")
    fun longestDistanceM(): Double
}
