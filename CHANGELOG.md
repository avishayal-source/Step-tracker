# Changelog

All notable changes to Y Walk are documented here. Versions follow the app's
`versionName` (and `versionCode` in parentheses).

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
