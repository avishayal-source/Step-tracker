# "Could a random user find this unusable?" — release checklist

Run per release on at least: one Samsung (One UI), one Pixel/AOSP-like, one aggressive
battery OEM (Xiaomi/Oppo/OnePlus), one Android 8–9 device (minSdk 26), one small
screen (≤ 5.5"). Mark ✅ / ❌ + note. `qa/device/robustness.ps1` automates the rows
tagged **[auto]**.

## 1. Getting in (first 2 minutes decide retention)
- [ ] Fresh Play install on Samsung S2x/S24 (tester report): succeeds; if not, capture `adb logcat | findstr PackageManager` and Play's error code.
- [ ] Upgrade over previous Play version keeps History, plan, calibration.
- [ ] Sideloaded debug installed first, then Play build → signature conflict message is understandable (doc for testers: uninstall first).
- [ ] Onboarding can be finished without calibration, without weight, without Botty.
- [ ] Legal consent: declining leaves the user with a clear exit, not a dead screen.
- [ ] Nonsense onboarding inputs (age 0, weight 999, letters pasted) are rejected with a message.

## 2. Permissions (most common "it doesn't work")  **[auto]**
- [ ] Location denied → workout still counts steps; UI says distance is step-estimated.
- [ ] Location "approximate only" (Android 12+) → explained, not silently wrong distances.
- [ ] Physical activity denied → explained.
- [ ] Notifications denied (Android 13+) → foreground service still starts; reminders explain they're off.
- [ ] Exact alarms denied (Android 14) → workout reminders degrade gracefully.
- [ ] Permission revoked from Settings *during* a workout → no crash, workout saved.

## 3. Staying alive during a workout (the core promise)  **[auto partly]**
- [ ] 60-min workout, screen off, phone in pocket: tracking never stops (Samsung "sleeping apps", Xiaomi autostart, Doze).
- [ ] Battery saver ON before starting.
- [ ] Incoming call mid-run; voice cues pause/resume; workout continues.
- [ ] Spotify/YouTube Music playing: voice cues duck music, don't stop it.
- [ ] Bluetooth headphones connect/disconnect mid-run.
- [ ] App swiped away from recents mid-workout → service survives or workout is saved.
- [ ] Phone reboots mid-workout → on reopen the user is told what was saved.
- [ ] Process killed while backgrounded (`am kill`) → timer/distance correct after reopen.
- [ ] Rotation / dark-mode toggle / font change mid-workout.
- [ ] 3-hour workout (marathon walker): memory, battery %, History entry correct.
- [ ] Battery drain over 60 min recorded (target: < 8 %/h with GPS).

## 4. Where and how people actually carry the phone
- [ ] Hand, front pocket, back pocket, armband, waist belt, backpack, stroller/pram handle, bike-mounted (should NOT become a run).
- [ ] Treadmill (no GPS) walking and running — see known defect #3.
- [ ] Urban canyon / trees / tunnel: GPS gaps don't create jumps.
- [ ] Forgot to stop: drove home → distance and calories not inflated (service rejects > 8 m/s fixes; verify UI).
- [ ] Hills: uphill slow jog not called walking; downhill fast walk not called jogging.
- [ ] Brisk cooldown walk after a run returns to Walking — see known defect #2.

## 5. Time and place
- [ ] Workout spanning midnight is saved on the right day and doesn't break streaks.
- [ ] DST transition (Israel last Sun of Oct / Fri before last Sun of Mar) — see known defect #1.
- [ ] Travel to another time zone with an active plan — see known defect #8.
- [ ] Manual clock change during a workout (negative durations) — see known defect #10.
- [ ] 12-hour clock users; week starting Sunday vs Monday.

## 6. Language, reading and accessibility  **[auto partly]**
- [ ] Hebrew/Arabic device language: layouts mirror sensibly, numbers stay LTR, no clipped text. **[auto: forced RTL]**
- [ ] Decimal comma locales (de, fr): typing "5,5" km in Coach/edit dialogs works or is clearly rejected.
- [ ] Font scale 1.3 and 2.0: no clipped buttons, Start/Stop always reachable. **[auto]**
- [ ] Display size "Largest" and a 5" screen. **[auto]**
- [ ] TalkBack: every button has a label; Start/Pause/Stop operable eyes-free.
- [ ] Contrast in dark mode and in direct sunlight (charts, gray-on-white text). **[auto screenshot]**
- [ ] Voice cues understandable at running pace with traffic noise.

## 7. Data the user cares about
- [ ] Backup export → uninstall → reinstall → import restores everything.
- [ ] Import a backup from an older version and a corrupted/hand-edited file → clear error, nothing wiped (see known defect #4).
- [ ] Low storage (< 200 MB) → saving a workout doesn't silently fail.
- [ ] Edit a past workout with extreme values (0, 99999, 0.01 min) — see known defect #11.
- [ ] Delete workout / delete plan are confirmable and undoable or clearly final.
- [ ] Import a backup with > 365 workouts: are the oldest dropped, and do ALL TIME totals change? (finding #21)

## 8. Botty / plans from a newcomer's point of view
- [ ] Typo inputs (500 weeks, 1000 km, 1 day/week) — see known defects #6, #7, #9.
- [ ] Missing a week of workouts: the user understands what Botty did (retired vs shifted).
- [ ] Back after 3 weeks off without tapping "Shift plan": first session isn't harder than before the break (finding #16).
- [ ] Doing two workouts in one day is discouraged — see design finding #13.
- [ ] Plan label, week counter and Schedule tab agree on the same week.

## 9. Watch (Galaxy Watch FE and others)
- [ ] Phone without watch app: no errors, no "watch" UI noise.
- [ ] Watch out of Bluetooth range: Sync shows stale time clearly.
- [ ] Watch workout with Pause → End saves locally; no crash on low watch battery.

## 10. Chaos
- [ ] `robustness.ps1 -Suite Monkey -Events 20000` with 3 seeds: zero crashes/ANRs. **[auto]**
- [ ] Rapid double-taps on Start/Stop/Save; back button during every dialog.
- [ ] Airplane mode for a whole session; then re-enable.
