# Androx 0.0.2: delivery plan (zips)

Source of truth: `Androx_0_0_2_Master_Specification.md` (kept unchanged in this folder).
Each zip is an overlay: unzip over the repo root, commit, build. Every zip leaves a project that builds and runs.

| Zip | Spec layers | Contents |
|---|---|---|
| **I** | 0, 1, start of 2 | Tooling upgrade, atomic storage, Flow data model v2, v1→v2 migration, typed values, math/string tools, registries (all v0.0.1 features), daily stats, Trash logic, backup v2. Not yet visible in the UI. |
| **II** | 2 | Flow engine v2: step tree, If/Repeat, nested constraint groups, typed templates, per-step options (retries, timeout, only-if, save result), run limits and wait budget, simulator. Pure Kotlin, fully unit tested. Not yet connected to the app. |
| III | 2, 3 | Switch the app to Flows: repositories, runner, alarms and receivers use the new engine; v1 data migrates on launch; UI vocabulary rename; trigger hub (multi-trigger, cooldown, hold time). |
| IV | 3 | Constraint logic builder UI pieces and the finished constraint and action catalogues. |
| V | 4 | Internet package: gates, vault, HTTP, presets, polling, MQTT. |
| VI | 5 | Shell: bottom nav, drawer, search pill, global search, Pause. |
| VII | 6 | Home. |
| VIII | 7 | Flows page, Flow page, folders, Trash screens. |
| IX–XI | 8 | Steps editor, Code editor, Canvas editor. |
| XII | 9 | Studio. |
| XIII | 10 | Settings hub. |
| XIV | 11 | Widgets, tiles, shortcuts, share target, external access, scheduled backup. |
| XV | 12 | Native-feel pass, tests, README, CHANGELOG, strings. |

The plan can change as builds come back; anything cut is listed with the reason.
