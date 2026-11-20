# Androx v0.0.1

Local-first Android automation. Triggers, rules and actions run on the device; nothing is sent anywhere.
Kotlin + Jetpack Compose + Material 3. Build with `./gradlew assembleDebug` (or Android Studio).

## What is in it

- **Home**: what's next (with countdown), success rate, 7-day activity, quick run, ideas, recent runs, and warnings for
  missing permissions.
- **Automations**: swipe right to run, left to delete (with Undo). Search, sort, filters with counts, status pills.
- **Editors**: Builder (guided *When → Only if → Then do*, with a plain-language summary), JavaScript (auto-indent,
  snippets, API reference), Visual workflow (colour-coded nodes). "Save and run" shows the log right there.
- **History**: success ring, filters with counts, per-automation filter, step-by-step run log, *Run again*, *Copy log*.
- **Ideas**: ten ready-made automations to add and tweak.
- **Variables**: create, edit and delete the values shared by all automations.
- **Settings**: theme, Material You or accent colours, pure black, haptics, permissions, back up / restore (JSON).

## Triggers / rules / actions

| Triggers | Rules (combine with *all* / *any*) | Actions |
|---|---|---|
| Manual | Battery level, Charging | Notification, Toast, Vibrate, Speak, Play sound |
| Schedule (time + days) | Time of day (can wrap midnight), Days of week | Flashlight, Set volume, Copy to clipboard |
| Interval (every N min/h, min 5) | Wi-Fi, Screen, Ringer mode | If / else, Repeat, Wait (≤10 s, ≤30 s per run) |
| Once (date + time, then turns off) | Battery Saver, Airplane mode | Set variable (set, add, subtract, multiply, append, remove) |
| Device starts | Variable comparison | Run automation, Write to log, Run JavaScript |

Text fields accept `{{battery}}`, `{{charging}}`, `{{time}}`, `{{date}}`, `{{datetime}}`, `{{weekday}}`, `{{timestamp}}`,
`{{wifi}}`, `{{screen}}`, `{{ringer}}`, `{{random}}`, `{{automation}}`, `{{index}}` (inside Repeat) and any variable.

## JavaScript API

`androx.log`, `notify`, `getVar`, `setVar`, `deleteVar`, `battery`, `isCharging`, `now`, `toast`, `vibrate`, `copy`,
`speak`, `isWifiConnected`, `isScreenOn`, `ringer`, `version`. Runs in Rhino, stops after 5 s, can reach nothing else.
The in-app *API reference* is generated from the same list.

## Design system

Material 3 components throughout: large collapsing top bars, tonal cards (24–28 dp corners), grouped settings cards,
extended FAB that collapses on scroll, Android 14-style switches, primary tab row, fade-through navigation motion,
haptics. Icons are Material Design Icons (Apache-2.0) as vector drawables (`res/drawable/mdi_*.xml`). The wordmark is
`res/drawable/ic_androx_wordmark.xml` (stroke-based, tintable); PNG/SVG copies are in `brand/`.

## Permissions

`POST_NOTIFICATIONS`, `SCHEDULE_EXACT_ALARM`, `RECEIVE_BOOT_COMPLETED`, `VIBRATE`, `ACCESS_NETWORK_STATE`.
No internet permission.

## Tests

`./gradlew testDebugUnitTest` covers the model, the engine (including If/else, Repeat and rules), workflow compilation,
scheduling math and the backup format.
