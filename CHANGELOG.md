# Changelog

## 0.0.2-alpha5 (Zip V: device, apps and notification actions)

New actions, all with settings, validation, summaries and permission explanations. The current screens don't offer them
yet; they arrive with the new editors.

- **Sound and device:** Set ringer mode, Do Not Disturb, Change volume, Set brightness, Set screen timeout, Auto-rotate,
  Keep phone awake (released when the run ends, never more than 10 minutes), Media control.
- **Temporary changes:** each settings action has "Put it back when the Flow ends". The old value is read first and
  restored newest-first when the run ends, even if a later step fails or the run is stopped. A restore that fails is logged
  and never blocks the others. The older "Set volume" action gained the same switch.
- **Apps and links:** Open link (web, email, phone, map only), Open app, Share text, Set alarm, Set timer. Android stops apps
  in the background from opening screens, so when no Androx screen is showing these post a notification to tap instead,
  and the log says so.
- **Notification builder:** name (to update or clear it later), importance, ongoing, progress bar, auto-dismiss and up to
  three buttons that run Flows; Clear notification. Button taps run the Flow with `{notification.id}` and
  `{notification.button}`.
- **Permissions:** Do Not Disturb access and Modify system settings are explained in plain English with the exact Settings
  path; the pickers show "Needs permission" until granted. Nothing is requested at install.
- **Manifest:** WAKE_LOCK, SET_ALARM, ACCESS_NOTIFICATION_POLICY, WRITE_SETTINGS, a launcher query so "Open app" works
  without the all-apps permission, and a non-exported receiver for notification buttons.
- 24 new unit tests.

Left for later, with reasons: "restore after N minutes" (needs a persisted timer, arrives with the trigger hub), dialogs
and prompts (need a screen on top, arrive with the shell), file and folder actions (need the folder picker), wallpaper,
biometric, QR and speak-the-time (low value for the effort).

## 0.0.2-alpha4 (Zip IV: device state, constraints, logic and data actions)

The engine and registries gain their finished constraint set and the logic and data actions. The current screens don't
offer them yet (the new editors do, in later zips); they run today in imported Flows and in the simulator.

**Device state providers** (one definition each, feeds a constraint, `{value}`, previews)
- 28 providers: battery level, charging, charger type, temperature, Battery Saver; Wi-Fi, network type, metered, VPN,
  Bluetooth, NFC, location; airplane, screen, locked, dark theme, auto-rotate, headphones, free storage, time since boot;
  ringer, Do Not Disturb, media playing, speakerphone, media / ring / alarm volume.
- A reader for each on Android (`AndroidStateReader`). A state that can't be read gives "can't be read right now" instead of
  guessing. Older checks stay for saved Flows and are hidden from pickers.

**Constraints**
- Typed comparisons shared by every rule: equals, not, less, greater, between, contains, matches pattern.
- New checks: Compare values, Flow state (running, turned on, ran recently, ran N times today), Started by, date range,
  day of month (including "last day"), month of year.

**Actions**
- Flow control: For each (lists, dictionaries, lines), While (always capped), Leave loop, Next round, Stop Flow (finish,
  fail or skip, with a returned value), Wait until, Try / on error with `{{error.message}}`, Run Flow with inputs and a
  returned value, Turn a Flow on or off.
- Data: Calculate, Text tools (14 operations, patterns), Read JSON, Format date and time, Random, List, Dictionary, typed
  local values.
- Break, continue and stop are never retried or swallowed by "continue on error" or Try. Each run's log starts with what
  started it.
- 66 new unit tests.

## 0.0.2-alpha3 (Zip III: the app runs on Flows)

The app now stores and runs Flows. The screens look the same apart from the new wording.

- **Storage:** `flows.json` is the source of truth. On the first launch after updating, saved automations migrate into it
  automatically; `automations.json` is left untouched. A damaged `flows.json` recovers from the last-good copy.
- **Engine:** manual runs, schedules, one-time runs and "device starts" all go through the new Flow engine.
- **Trash:** deleting a Flow moves it to Trash (Undo still works); Flows older than 30 days in Trash are removed on launch.
- **Run rules:** per-Flow run limit per hour and concurrency (skip while running, or queue). Skipped runs are logged.
- **Alarms:** re-armed from the Flows after loading and after every change, never from a half-loaded list.
- **Wording:** "automation" is now "Flow" everywhere you read it. Internal ids and file names are unchanged.
- **Safe editing:** the current editor works on a view of each Flow. Anything it can't show (extra triggers, negated rules,
  run options, folders, steps added later) is kept when you save.
- **Also:** `simulate(id)` on the runner for the coming dry-run button.
- 23 new unit tests.

Known gaps (by design, next zips): REPLACE concurrency queues instead of cancelling; multi-trigger, cooldown and hold
time arrive with the trigger hub; backups still export the v0.0.1 format.

## 0.0.2-alpha2 (Zip II: Flow engine)

Nothing changes on screen yet; the engine is not connected to the app until Zip III.

- `core/engine/FlowEngine`: runs a Flow. Constraints first (skips with a reason), then the step tree.
- Control flow: If / else and Repeat (with `{index}`), Run Flow with cycle and depth guards, a 1000-step cap per run.
- Per-step options: turn off, "only if", retries with delay, timeout, continue on error, save result to a variable.
- Run options: whole-run timeout, flow-wide on-error policy, wait budget (30 s by default, raisable).
- Nested All / Any / None constraint groups with per-rule "is not" and v0.0.1-style reasons (`ConstraintEvaluator`).
- Typed values end to end. `{user.items.1.name}` reaches into lists and dictionaries; old text variables still work.
- Simulator: `simulate(flow)` describes every step and changes nothing (copy-on-write variables, no device calls, no waiting).
- Extensible: leaf actions live in a table; adding one is one `ActionExecutor`.
- Cancellation is never swallowed by retries or "continue on error".
- 44 new unit tests.

## 0.0.2-alpha1 (Zip I: foundation)

Nothing changes on screen yet. This zip lays the ground the rest of 0.0.2 stands on.

**Tooling**
- Compose BOM 2025.12.00 (Material 3 1.4.0), Kotlin 2.2.20, AGP 8.13.0, Gradle 8.13, compileSdk and targetSdk 36,
  plus matching AndroidX, coroutines and serialization lines. `kotlinOptions` replaced by `compilerOptions`.

**Data safety**
- Every JSON file now keeps a rolling last-good copy (`.bak`), recovers from it when the main file is damaged,
  and never promotes a damaged file to last-good.

**Data model v2** (`core/`)
- `Flow` with several triggers, nested All/Any/None constraint groups, a step tree with per-step options, run options,
  folders, version snapshots and Trash fields. Generic `Check` and `Op` carriers; unknown types from a newer file load
  as "Unavailable" instead of crashing.
- Automatic, idempotent v1 → v2 migration (`FlowMigration`, `FlowStorage`). Flows keep the id of the automation they came
  from; `automations.json` is never touched. v0.0.0/0.0.1 backups import through `BackupCodec`.

**Registries** (`core/registry/`)
- One self-describing `Spec` per trigger, constraint and action: typed settings schema, validation, plain-English
  summary, permissions, battery cost, edition, outputs, synonyms. All v0.0.1 features are registered and their
  summaries match the old wording exactly (tested).

**Typed values and tools**
- Text, number, boolean, list, dictionary; JSON parse and path lookup; a safe math expression evaluator; string and
  regex helpers with size and pattern caps.

**Stats and Trash**
- Daily stats store (never trimmed) recording every run from now on: success ring, 7-day series, streaks, busiest Flow,
  time-saved estimate. Trash rules: soft delete, restore switched off, 30-day expiry.

**Tests**: 66 new unit tests (values, math, migration, registries, stats, Trash, atomic storage).

## 0.0.1

**New**
- Triggers: Interval, Once, Device starts.
- Rules: time window, days of the week, Wi-Fi, screen, ringer mode, Battery Saver, Airplane mode; combine with all / any.
- Actions: Vibrate, Toast, Speak, Play sound, Flashlight, Set volume, Copy to clipboard, Wait, If / else, Repeat.
- Variable operations: subtract, multiply, append, remove.
- More template values: `{{datetime}}`, `{{weekday}}`, `{{timestamp}}`, `{{wifi}}`, `{{screen}}`, `{{ringer}}`, `{{random}}`, `{{index}}`.
- Script API: `toast`, `vibrate`, `copy`, `speak`, `isWifiConnected`, `isScreenOn`, `ringer`.
- Ideas gallery, Variables manager, back up / restore, per-automation icons.
- Home dashboard, history statistics, run details with *Run again* and *Copy log*.
- Swipe actions, undo for delete, sort, failure badge on the History tab.

**Design**
- Large collapsing app bars, tonal cards, grouped settings, accent colours, haptics, fade-through motion.
- "androx" wordmark, Material Design Icons.

**Fixes**
- Editor title no longer wraps; actions are collapsible; notification checks cover nested actions.
