# Step Tracker ("Let's GO")

Android app for live step counting, walk/run classification, GPS distance, interval workout schedules, and session history.

**Repository:** https://github.com/avishayal-source/Step-tracker.git

## Screenshots

| Tracking | Schedule | History |
|:--------:|:--------:|:-------:|
| <img src="Screenshots/tracking.jpeg" width="240" alt="Live tracking tab" /> | <img src="Screenshots/schedule.jpeg" width="240" alt="Interval schedule tab" /> | <img src="Screenshots/history.jpeg" width="240" alt="Workout history tab" /> |

## Features

| Area | What it does |
|------|----------------|
| **Live tracking** | Foreground service counts steps from the accelerometer and classifies each step as walk or run (cadence + magnitude with hysteresis). |
| **Distance** | Uses calibrated stride length per activity; GPS augments distance when location permission is granted. |
| **Calibration** | Wizard measures walk/jog stride length (sensor + optional GPS) and stores values in `UserPrefs`. |
| **Schedules** | Build timed intervals (walk/jog/rest); 3-2-1 prep countdown, period timers, audio cues, persistence across rotation. |
| **History** | Completed sessions saved to `WorkoutHistory` (steps, distance, duration per activity type). |

## Requirements

- Android **8.0+** (API 26), target SDK 34
- Device with **accelerometer** (required)
- Optional: step counter hardware, GPS for distance calibration and tracking

### Permissions

- `ACTIVITY_RECOGNITION` — step tracking (Android 10+)
- `ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION` — GPS distance (optional but recommended)
- `POST_NOTIFICATIONS` — foreground tracking notification (Android 13+)
- Foreground service types: `health`, `location`

## Build and run

```bash
cd StepTracker
./gradlew assembleDebug
```

Install the APK from `app/build/outputs/apk/debug/`, or open the project in Android Studio and run on a device/emulator with an accelerometer.

## Project structure

```
app/src/main/java/com/steptracker/app/
├── MainActivity.kt           # Tabs: Tracking | Schedule | History
├── StepTrackerService.kt     # Foreground tracking, GPS, session state
├── StepDetector.kt           # Accelerometer step + walk/run classifier
├── CalibrationActivity.kt    # Stride calibration wizard
├── ScheduleManager.kt        # Interval timer engine
├── ScheduleEmbeddedView.kt   # Schedule UI in main tab
├── UserPrefs.kt              # Stride lengths, calibration flags
└── WorkoutHistory.kt         # Persisted workout records
```

## Architecture flow

High-level path from app launch through tracking and history.

```mermaid
flowchart TB
    subgraph UI["MainActivity (3 tabs)"]
        T[Tracking]
        S[Schedule]
        H[History]
    end

    Launch([App launch]) --> Perm{Permissions granted?}
    Perm -->|No| Req[Request ACTIVITY_RECOGNITION + location + notifications]
    Req --> Perm
    Perm -->|Yes| Bind[Bind StepTrackerService]

    Bind --> T
    Bind --> S
    Bind --> H

    T --> Cal{Calibrated?}
    Cal -->|No| Tip[Toast: open Calibrate]
    Cal -->|Yes| Ready[Ready to track]
    Tip --> CalBtn[CalibrationActivity]
    CalBtn --> Prefs[(UserPrefs stride lengths)]

    T --> StartStop{Start / Stop}
    StartStop -->|Start| SvcStart[Service.startTracking]
    StartStop -->|Stop| SvcStop[Service.stopTracking]

    SvcStart --> FG[Foreground notification + wake lock]
    FG --> Acc[Accelerometer → StepDetector]
    FG --> GPS[GPS location updates]
    Acc --> Classify[Walk / Run classification]
    Classify --> Period[ActivityPeriod log]
    GPS --> Dist[GPS distance per period]
    Period --> UIUpdate[onUpdateListener → MainActivity UI]

    SvcStop --> Save{totalSteps > 0?}
    Save -->|Yes| Hist[(WorkoutHistory)]
    Save --> H

    S --> Edit[Edit schedule items]
    Edit --> RunSched[ScheduleManager.startWithPrep]
    RunSched --> Prep[3-2-1 countdown]
    Prep --> Periods[Timed periods + sounds]
    Periods --> SvcStart

    H --> List[Load saved WorkoutRecords]
```

## Step detection flow

How a single accelerometer sample becomes a classified step and distance update.

```mermaid
flowchart LR
    A[TYPE_ACCELEROMETER] --> Filter[Gravity filter + peak detect]
    Filter --> Step{Above threshold + min interval?}
    Step -->|No| A
    Step -->|Yes| Cadence[Cadence + magnitude windows]
    Cadence --> Vote[Vote window: walk vs run]
    Vote --> Hyst[Hysteresis: enter/exit run thresholds]
    Hyst --> Type[ActivityType WALKING or RUNNING]
    Type --> Listener[StepTrackerService.onStep]
    Listener --> Stride[Stride from UserPrefs]
    Stride --> Steps[Increment walk/run steps + distance]
    GPS[GPS delta] --> Steps
```

## Schedule flow

```mermaid
sequenceDiagram
    participant U as User
    participant V as ScheduleEmbeddedView
    participant M as ScheduleManager
    participant S as StepTrackerService

    U->>V: Edit intervals (walk/jog/rest + duration)
    U->>V: Start schedule
    V->>M: startWithPrep(schedule)
    M-->>U: Countdown 3, 2, 1 + sound
    M->>M: start() — period 0
    loop Each period
        M-->>V: onTick(remaining)
        M->>S: Switch activity type if needed
        M-->>V: onPeriodComplete → next period
    end
    M-->>V: onScheduleComplete
```

## Data persistence

| Store | Contents |
|-------|----------|
| `UserPrefs` | Walk/jog stride (m), calibration flag |
| `ScheduleStore` / `SavedSchedule` | Named schedule templates |
| `ScheduleRunPersistence` | In-progress schedule snapshot (resume after recreate) |
| `WorkoutHistory` | Past sessions: steps, distance, duration by activity |

## License

Add a license file if you plan to open-source the project publicly.
