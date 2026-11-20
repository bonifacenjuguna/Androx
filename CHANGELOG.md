# Changelog

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
