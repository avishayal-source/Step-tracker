# Y Walk — release shrink rules (Room, services, coach models)

-keepattributes *Annotation*
-keepattributes SourceFile,LineNumberTable
-keepattributes Signature,InnerClasses,EnclosingMethod
-renamesourcefileattribute SourceFile

# Activities (manifest + explicit Intents)
-keep public class * extends androidx.appcompat.app.AppCompatActivity

# Room — entities, DAOs, generated implementations
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-keep @androidx.room.Dao interface *
-keep class com.steptracker.app.AppDatabase_Impl { *; }
-keep class com.steptracker.app.WorkoutDao_Impl { *; }
-dontwarn androidx.room.paging.**

# Enums used in JSON persistence (ActivityType.name / valueOf)
-keepclassmembers enum com.steptracker.app.ActivityType { *; }

# Keep data classes used by JSON persistence
-keep class com.steptracker.app.TrainingPlan { *; }
-keep class com.steptracker.app.PlannedWorkout { *; }
-keep class com.steptracker.app.ScheduleItem { *; }
-keep class com.steptracker.app.WorkoutRecord { *; }
-keep class com.steptracker.app.ActivityPeriod { *; }

# Foreground service + binder + receivers
-keep class com.steptracker.app.StepTrackerService { *; }
-keep class com.steptracker.app.StepTrackerService$LocalBinder { *; }
-keep class com.steptracker.app.WorkoutReminderReceiver { *; }

# Onboarding / legal
-keep class com.steptracker.app.OnboardingActivity { *; }
-keep class com.steptracker.app.LegalDocActivity { *; }
-keep class com.steptracker.app.LegalConsent { *; }

# EncryptedSharedPreferences / Tink
-keep class androidx.security.crypto.** { *; }
-keep class com.google.crypto.tink.** { *; }
-dontwarn com.google.crypto.tink.**
