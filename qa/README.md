# Y Walk — QA suite

This folder holds the test plan, the runners and the manual checklists. The automated
JVM tests live where Gradle expects them:
`app/src/test/java/com/steptracker/app/qa/`.

| Layer | Where | How to run |
|---|---|---|
| Extreme-case unit tests | `app/src/test/.../qa/*ExtremeCases*`, `CalendarAndDstTest`, `PlanPersistenceRobustnessTest` | `.\qa\run-qa.ps1` |
| Classifier scenarios (synthetic gait + GPS) | `ClassifierScenarioTest` | `.\qa\run-qa.ps1 -Only Classifier` |
| Virtual users on a virtual calendar | `VirtualUsersTimelineTest` → `app/build/qa-reports/virtual_users.txt` | `.\qa\run-qa.ps1 -Only VirtualUsers` |
| Device robustness (adb) | `qa/device/robustness.ps1` → `qa/device/out/<timestamp>/` | `.\qa\device\robustness.ps1 -Suite Monkey` |
| Manual "random user" checklist | `qa/RANDOM_USER_CHECKLIST.md` | by hand, per release |

## Conventions

- Tests named `defect_*` describe the **correct** behaviour and currently fail because
  of a known bug. When a bug is fixed its test turns green — leave it as a regression
  guard. `run-qa.ps1` reports them as `KNOWN DEFECT` and only exits non-zero for
  *unexpected* failures.
- All randomness is seeded; a failing seed reproduces exactly.
- Time-zone sensitive tests pin the zone (`TimeZoneRule`), default `Asia/Jerusalem`.

## What each suite challenges

**CoachExtremeCasesTest** — ~38k combinations of every value the Coach form accepts
(age 10–100, weight 25–300, height 100–230, days 1–7, any distance/weeks > 0, negative
"current longest run"). Asserts: no crash, no NaN/negative distances, weeks numbered
1..N, long run never jumps more than +50 % / +1 km, medical cautions for high-risk users.

**PlanSchedulerExtremeTest** — every generated plan: unique ids and reminder request
codes, sorted, one workout per day, no empty/negative periods, sessions ≤ 7 h,
goal label matches the real plan length.

**PlanPersistenceRobustnessTest** — JSON round-trip with Hebrew/emoji/quotes/NUL/10 KB
strings, legacy `done` flag migration, unknown status, missing fields, speed on a
year-long plan, forward-compat with unknown period types.

**CalendarAndDstTest** — due/overdue at 23:59 vs 00:00, grace window and backlog cap,
"today beats backlog", and calendar arithmetic across Israeli DST start/end and time
zone travel.

**StatsExtremeCasesTest** — calories with NaN/∞/negative inputs, Long.MAX durations,
weight bounds, physiological ceiling; History insights on empty, future-dated and
20 000-record histories, streaks across DST; distance formatting in he/de/ar/hi/fa.

**ClassifierScenarioTest** — the real `StepDetector` fed synthetic streams: steady
walk/jog/run, walk↔run intervals (latency + flapping), Couch-to-5K 2′/1′ intervals,
slow and brisk cooldowns with the double-peak counter, GPS tunnel, traffic-light stop,
noisy GPS at the jog boundary, hand-waving on the couch, treadmill (no GPS), seed sweep.

**VirtualUsersTimelineTest** — 10 seeded personas live day by day through a real
Botty plan (beginner, obese 66+, intermediate, 3-week vacation, plan realigner,
catch-up stacker, quitter, New York across DST end, spring DST start, 23:59 night owl).
Each simulated day runs hygiene → next due → train/skip → History + calories +
insights and checks: backlog ≤ 2, nothing offered past grace, retirements always
explained, `nextDue` never returns done/future work, kcal plausible, plan always
terminates, done + retired = total.

## Findings (first run, v1.0.17, 19 Oct 2026)

**Fixed** (tests now green): #1, #2, #3, #8, #12. Plan dates use calendar-day arithmetic
and stored dates are snapped to the nearest local midnight on load (repairs plans saved by
v1.0.17 and earlier, and travel within ±12 h). The classifier releases a run on a sustained
soft-landing streak when the walk baseline was never learned, and soft landings veto
"jogging" when there is no GPS. `ClassifierReplayTest` replays the recorded workouts in
`device_logs/` and `pulled_logs/` (not committed) — the 2026-09-22 workout is unchanged
at 99.9 % agreement.

| # | Severity | Finding | Test |
|---|---|---|---|
| 1 | **High** | Workouts are dated `start + n × 24 h`. After the clocks go back (Israel: 25 Oct 2026) every later workout lands at 23:00 the previous evening — 24 of 27 workouts in a plan started today show and remind **one day early**. Same arithmetic in "shift plan to today". | `defect_workoutsStayOnTheirCalendarDay_acrossDstEnd`, `defect_shiftPlanForward_…`, virtual "Realigner" |
| 2 | **High** | Brisk cooldown walk (1.5 m/s) after a run with the double-peak counter **never** returns to WALKING (3 min tested). Slow override only covers ≤ 1.40 m/s, cadence is distrusted and gait baselines are unlearned. | `defect_briskCooldown_withDoubledPeaks_returnsToWalking` |
| 3 | **High** | Treadmill / indoor walking (no GPS) with the double-peak counter (~207 SPM) is classified as **jogging 100 %** of the time — nothing can expose the doubled cadence without GPS. | `defect_treadmillWalk_withDoubledPeaks_staysWalking` |
| 4 | Medium | One unknown period type in stored/restored plan JSON throws → `TrainingPlanStore.load()` returns null → the **whole plan silently disappears**. | `defect_unknownActivityType_doesNotDiscardWholePlan` |
| 5 | Medium | With 2 running days/week (or 1, coerced to 2) the "easy run" equals the long run — two long runs a week. 3 days gives easy ≈ 82 % of long. | `defect_easyRuns_areShorterThanTheLongRun` |
| 6 | Medium | Coach accepts any weeks > 0 — "500" builds a 500-week plan (~1 500 workouts and reminders at 3 days/week). | `defect_hugeHorizon_isBounded` |
| 7 | Medium | Coach accepts any distance > 0 — "1000" plans a 910 km long run. | `defect_absurdTargetDistance_isRejectedOrCapped` |
| 8 | Medium | Plan dates are absolute Israeli midnights; a user who travels west sees **every** workout on the previous day. | `defect_travellerSeesWorkoutsOnTheSameCalendarDay` |
| 9 | Low | "1 day per week" is silently turned into 2. | `defect_oneDayPerWeek_isRespectedOrExplained` |
| 10 | Low | A negative duration (clock change mid-workout) subtracts calories. | `defect_negativeDurationNeverReducesCalories` |
| 11 | Low | A manually edited 0.01-min run makes History say "You ran 299900 % faster". | `defect_paceInsight_ignoresImplausibleSessions` |
| 12 | Low | `displayWeek` shows the previous week for the first hour after spring-forward. | `defect_displayWeek_acrossDstStart` |
| 13 | Design | After finishing today's workout the next overdue one is offered the same day — the "Stacker" persona did double sessions on 9 days, contradicting PlanHygiene's anti-stacking intent. | virtual users report (`stacked`) |
| 14 | Design | Habitual "shift plan to today" with 55 % adherence stretched a 12-week plan to 31 weeks with no upper bound or warning. | virtual users report (`realign`, `days`) |
| 15 | Info | Standing at a traffic light: the GPS Kalman filter takes ~10 s to fall below walking speed, so phone fidgets in that window are counted as steps. | `trafficLightStop_countsNoStepsAndKeepsTheRun` (tolerated ≤ 8) |

Not unit-testable from here (handled by the checklist / device script): foreground
service survival under OEM battery killers, permission UX, accessibility, install/upgrade.
