# Y Walk — release shrink rules (Room, services, coach models)

-keepattributes *Annotation*
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Room
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**

# Keep data classes used by JSON persistence
-keep class com.steptracker.app.TrainingPlan { *; }
-keep class com.steptracker.app.PlannedWorkout { *; }
-keep class com.steptracker.app.ScheduleItem { *; }
-keep class com.steptracker.app.WorkoutRecord { *; }
-keep class com.steptracker.app.ActivityPeriod { *; }

# Foreground service + receivers (started by explicit intent)
-keep class com.steptracker.app.StepTrackerService { *; }
-keep class com.steptracker.app.WorkoutReminderReceiver { *; }

# EncryptedSharedPreferences / Tink
-keep class androidx.security.crypto.** { *; }
-dontwarn com.google.crypto.tink.**
