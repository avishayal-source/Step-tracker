# Changelog

All notable changes to Y Walk are documented here. Versions follow the app's
`versionName` (and `versionCode` in parentheses).

## 1.0.11 (12) — 2026-09-09

Classifier v13: a walk after a run is recognised as a walk.

- Fix a 3-minute cooldown walk being logged as running (reported as 3:12 walk / 40:47 run
  for a 3 + 38 + 3 session). Peak detection registers about two steps per stride while
  walking, so a 1.14 m/s walk read as 197 SPM, and v12's cadence veto — which blocks
  run→walk while cadence looks like running — could never release
- Cadence loses its vote when GPS shows the implied step length is impossible (< 0.50 m).
  Untrusted cadence *holds* the run rather than releasing it, since the same over-count
  appears during a slow jog and releasing there re-opens the v11 false-walk hole
- Landing force outranks cadence, measured against the learned *walk* baseline rather than
  the walk/run midpoint — the run baseline is learned from fast running, so a slow jog sits
  under that midpoint and would read as walking
- Safety valve: 40 unbroken seconds at ≤ 1.40 m/s ends the run regardless of any veto, so
  the classifier can never be pinned in the running family again
- Replayed against the 2026-09-08 session: 5:07 walk / 38:53 run against a true 6:00 /
  38:00, with no false walks mid-run (was 3:12 / 40:47)
- Calibration and workout tracking now share one step counter. Calibration used stricter
  thresholds (2.4 / 300 ms vs 1.5 / 230 ms), so metres-per-step was measured against a
  counter that wasn't the one used while tracking
- Fix an inescapable recalibration loop: the app discarded any walk calibration under
  0.55 m and the wizard refused to save one, but a correct result is near half the
  anatomical stride, so no calibration could satisfy both
- Debug CSV gains peak_mag, implied_step_m, cadence_trusted and slow_streak_ms

## 1.0.10 (11) — 2026-09-09

Coaching feel: honest plan titles, gentler cues, spoken progress, earlier prep.

- Fix plan title using the *requested* horizon ("10 km in 8 weeks" for an 11-week plan);
  existing saved plans are relabelled on load
- Transition cues rebuilt as a soft warbling coach's whistle (sine) instead of square-wave
  bursts, on the media stream at moderate volume, ducking music instead of ignoring it.
  Three notes per transition — rising to run, falling to walk — and four at the finish
- Fix a latent buffer overrun in cue rendering: the PCM buffer was sized from
  (tone + gap) truncated once, but filled from tone and gap truncated separately
- Voice cues via device TTS at 25 / 50 / 75 % of a session; skipped under 10 minutes
- Next workout preloads into the Schedule tab right after you finish the previous one
  (within 3 days); replacing it returns it to the plan and it comes back the day before
- Finishing early marks the workout done and says you're ahead, leaving later dates alone
- New "Sounds & voice" settings in the ⋮ menu with an audible whistle preview

## 1.0.9 (10) — 2026-09-08

Flexible Botty schedule: a missed workout is carried, not dropped.

- Workouts have a status (pending / done / skipped); a passed date no longer hides them
- Schedule tab loads the next *due* workout — today's, or the oldest still waiting
- Completion is tracked by workout id, so a late session marks the right entry done
- Botty overdue card: Do it now / Skip it / Shift my plan (pushes remaining sessions forward)
- Backlog limits: retired after 3 days late, max 2 carried, always announced
- Reminders rebuilt as one daily 18:00 plan check: day-before, overdue nudge, Sunday summary
- Fix: reminders were lost on reboot / app update (added BootReceiver)

## 1.0.8 (9) — 2026-08-23

Fix bad calibration strides and false walking during continuous runs.

- Calibration: stricter step detection, ≥20 m GPS, adult accept ranges, optional height check
- Classifier v12: run→walk also requires walking cadence (holds jog on GPS dips at high SPM)

## 1.0.7 (8) — 2026-07-23

Move backup and legal links into a top-bar More menu.

- Add ⋮ overflow menu: Export backup, Restore backup, Privacy Policy, Terms of Service
- Remove backup section from the Tracking tab (tabs stay for main navigation)

## 1.0.6 (7) — 2026-07-23

User-owned backup export / restore on the Tracking tab.

- Export workouts, training plan, schedules, calibration, and Botty form to a JSON file
- User picks the save/open location via the system file picker
- Restore replaces local data (does not skip legal onboarding)
- Includes API 36 target from 1.0.5

## 1.0.5 (6) — 2026-07-23

Play compliance: target Android 16 (API 36).

- Bump compileSdk / targetSdk from 35 to 36

## 1.0.4 (5) — 2026-07-14

Improve Botty keyboard scrolling for lower form fields.

- Apply IME height as form bottom padding so last fields can scroll above the keypad
- Stronger scroll-to-focus (upper viewport + delayed second pass)
- Keep system-bar insets on the activity root; handle keyboard on the Botty ScrollView

## 1.0.3 (4) — 2026-07-14

UI fix for Botty form when the keyboard is open.

- Apply IME (keyboard) insets so content shrinks above the keypad
- Set `adjustResize` on MainActivity / CalibrationActivity
- Scroll focused Botty fields into view when editing

## 1.0.2 (3) — 2026-07-05

Hotfix for first-launch crash on Play Store installs.

- Guard `MainActivity.onDestroy()` so `scheduleView` is only accessed after it is initialized
- Fixes crash on the consent redirect path (MainActivity → OnboardingActivity on first open)

## 1.0.1 (2) — 2026-07-04

Hotfix for launch crash on Play Store installs.

- Remove full-screen bitmap from theme `windowBackground` (prevented OOM at startup)
- Load background artwork via center-crop `ImageView` after layout instead
- Downscale background asset for lower memory use
- Expand ProGuard keep rules (Room impl, service binder, activities, Tink)
- Add `res/raw/keep.xml` so shrinkResources retains launcher icon and background

## 1.0 (1) — 2026-06-30

First release of Y Walk.

- Step counting with automatic walk / jog / run detection
- Distance & pace with optional GPS
- Botty coach builds a safe, personalized training plan with reminders
- Workout history
- Step-length calibration for personal accuracy
- Fully private — all data stays on your device (no accounts, no tracking)
- First-run onboarding with Terms, Privacy Policy, and health/AI disclaimers
- Targets Android 15 (API 35) with edge-to-edge display
