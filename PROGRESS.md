# Androx 0.0.2 build progress

Source of truth: `Androx 0.0.2 - Master Specification.md` (section numbers below refer to it).

| Layer | Spec | Status |
|---|---|---|
| 0 Tooling upgrade | 4 | done (versions below, verify on first build) |
| 1 Data model v2, migration, storage, daily stats | 5.2, 5.4 | done: `core/model`, `core/migrate`, `core/store` |
| 2 Registries, typed variables, templates | 5.1, 5.3 | done for existing types; engine v2 NOT yet |
| 2b Engine v2 (executors, run queue, simulator) | 5.5 | next |
| 3 Trigger hub, Listener, constraints, actions | 5.6, 15, 17, 18 | not started |
| 4 Internet package | 14 | not started |
| 5-12 Shell, Home, Flows, editors, Studio, Settings, integrations, polish | 6-13, 16, 19-21 | not started |

## Version choices to verify on the first build
AGP 8.13.0, Gradle 8.13, Kotlin 2.2.20, Compose BOM 2025.09.00 (material3 1.4.0), core-ktx 1.17.0, activity-compose 1.11.0,
lifecycle 2.9.4, navigation 2.9.5, coroutines 1.10.2, serialization 1.9.0. If any version is rejected, paste the error back.
`kotlinOptions {}` was replaced by `kotlin { compilerOptions {} }` (Kotlin 2.2 requirement).

## Design note
The v0.0.1 UI and engine are untouched and still compile against the legacy `Automation` model.
New code lives in `com.androx.app.core`. The UI layers will switch over to Flows and the legacy classes
stay only as the migration source.
