# Changelog

## 0.0.2 (in progress: layer 0-2 foundation)

**Tooling**
- Material 3 1.4.0 stable, Compose BOM 2025.09.00, AGP 8.13.0, Kotlin 2.2.20, Gradle 8.13, compileSdk 36 (target stays 35).

**Core (new, pure Kotlin, unit tested; the v0.0.1 UI still runs on the old model until the UI layers land)**
- `core.value`: typed values (text, number, true/false, list, dictionary), JSON paths, safe maths evaluator, `{{template}}` engine with `{{flow}}` and the `{{automation}}` alias.
- `core.model`: Flow v2 (several triggers, constraint groups All/Any/None, step tree with options), generic Event/Check/Op carriers.
- `core.migrate`: automatic, repeatable v0.0.1 automation to Flow conversion.
- `core.registry`: self-describing trigger, constraint and action specs with search, summaries and validation.
- `core.store`: crash-safe files with last-good copy, daily stats that are never trimmed, Trash rules.

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
