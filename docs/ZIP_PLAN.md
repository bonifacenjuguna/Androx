# Androx 0.0.2: delivery plan (zips)

Source of truth: `Androx_0_0_2_Master_Specification.md` (kept unchanged in this folder).
Each zip is an overlay: unzip over the repo root, commit, build. Every zip leaves a project that builds and runs.

| Zip | Spec layers | Contents |
|---|---|---|
| **I** | 0, 1, start of 2 | Tooling upgrade, atomic storage, Flow data model v2, v1→v2 migration, typed values, math/string tools, registries (all v0.0.1 features), daily stats, Trash logic, backup v2. Not yet visible in the UI. |
| II | 2, 3 | Engine v2 (step tree, control flow, budgets, cancel, simulator), switch the app to Flows (repositories, runner, alarms), vocabulary rename, trigger hub. |
| III | 3 | Constraint logic builder and the finished constraint and action catalogues. |
| IV | 4 | Internet package: gates, vault, HTTP, presets, polling, MQTT. |
| V | 5 | Shell: bottom nav, drawer, search pill, global search, Pause. |
| VI | 6 | Home. |
| VII | 7 | Flows page, Flow page, folders, Trash screens. |
| VIII–X | 8 | Steps editor, Code editor, Canvas editor. |
| XI | 9 | Studio. |
| XII | 10 | Settings hub. |
| XIII | 11 | Widgets, tiles, shortcuts, share target, external access, scheduled backup. |
| XIV | 12 | Native-feel pass, tests, README, CHANGELOG, strings. |

The plan can change as builds come back; anything cut is listed with the reason.
