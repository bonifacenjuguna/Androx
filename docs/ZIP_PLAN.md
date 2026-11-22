# Androx 0.0.2: delivery plan (zips)

Source of truth: `Androx_0_0_2_Master_Specification.md` (kept unchanged in this folder).
Each zip is an overlay: unzip over the repo root, commit, build. Every zip leaves a project that builds and runs.

| Zip | Spec layers | Contents |
|---|---|---|
| **I** | 0, 1, start of 2 | Tooling upgrade, atomic storage, Flow data model v2, v1→v2 migration, typed values, math/string tools, registries (all v0.0.1 features), daily stats, Trash logic, backup v2. Not yet visible in the UI. |
| **II** | 2 | Flow engine v2: step tree, If/Repeat, nested constraint groups, typed templates, per-step options (retries, timeout, only-if, save result), run limits and wait budget, simulator. Pure Kotlin, fully unit tested. Not yet connected to the app. |
| **III** | 2, 3 | The app runs on Flows: storage (`flows.json`), runner, alarms, Trash, run limits, wording. Done. |
| **IV** | 3 | Device state providers (28) and generated constraints, typed comparisons, Flow-state / Started-by / date constraints, and the logic and data actions (For each, While, Try, Stop, Wait until, Calculate, Text, JSON, Date, Random, List, Dictionary, local values, switch Flows). |
| V | 3 | Device, notification and permission actions (Do Not Disturb, brightness, notification builder with buttons, launch app, share, alarms, files) with their permission flows. |
| VI | 3 | Trigger hub and Listener service: multiple triggers per Flow, cooldown, hold time, the ready-without-permission event triggers, Trigger log. |
| VII | 4 | Internet package: gates, vault, HTTP, presets, polling, MQTT. |
| VIII | 5 | Shell: bottom nav, drawer, search pill, global search, Pause. |
| IX | 6 | Home. |
| X | 7 | Flows page, Flow page, folders, Trash screens. |
| XI–XIII | 8 | Steps editor, Code editor, Canvas editor. |
| XIV | 9 | Studio. |
| XV | 10 | Settings hub. |
| XVI | 11 | Widgets, tiles, shortcuts, share target, external access, scheduled backup. |
| XVII | 12 | Native-feel pass, tests, README, CHANGELOG, strings. |

The plan can change as builds come back; anything cut is listed with the reason.
