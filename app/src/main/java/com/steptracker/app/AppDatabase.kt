package com.steptracker.app

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [WorkoutRecord::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {

    abstract fun workoutDao(): WorkoutDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "steptracker.db"
                )
                    // Local analytics table is tiny; synchronous calls keep the existing
                    // callers drop-in compatible. TODO: move to coroutines/Flow later.
                    .allowMainThreadQueries()
                    .build()
                    .also { INSTANCE = it }
            }
    }
}
