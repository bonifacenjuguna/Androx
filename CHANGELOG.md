# Changelog

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
