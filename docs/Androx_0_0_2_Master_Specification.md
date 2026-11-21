# Androx 0.0.2 · Master Specification

**Base build:** v0.0.1 · **Written:** 21 Nov 2026 · **Status:** design agreed for every area below. Build starts when the owner says so.

**Tags used everywhere:** **\[0.0.2\]** built now · **\[LATER\]** designed for, slot reserved, not built · **\[FULL\]** Full edition only · **\[SHIZUKU\]** privileged tier, slot reserved · **\[NO\]** not possible on Android, with the closest alternative.

## 1. Standing rules (never forget)

1. Androx must look, feel and behave like it was made by Google for Android: native Material 3 Expressive look, native motion, native haptics, native system integration. This is a must, not an option.
2. Reference apps (MacroDroid, Files, Gmail) are inspiration only. Never copy their layouts, tiles or wording. Borrow ideas, improve them, add our own. Androx keeps its own identity.
3. **Fewer features, each one finished from the inside out:** settings screen, validation, plain-English summary, outputs, permission flow, failure explanation, a Test button, and unit tests. Anything that cannot be finished is cut and kept as a visible “Coming later” slot.
4. **Build for the future:** registries and generic types so new triggers, constraints, actions, settings, cards and connections can be added (the owner plans several per day) without data migrations or UI rewrites.
5. Go deeper in everything. Be creative. Add the engineering details that make the app feel solid. It is a native app, not a website.
6. Existing user data stays compatible: Flows, history, variables and backups from v0.0.0 and v0.0.1 must keep working (automatic migration, covered by tests).
7. **Delivery:** one big drop at the end of 0.0.2. The builder cannot compile in its sandbox, so every layer gets static checks and unit tests for logic. The owner pastes any build errors back and they are fixed in one pass.
8. The owner decides when the build starts and when a master spec is produced.
9. Nothing agreed is silently dropped. If something cannot be done, it is listed with the reason and the nearest alternative.
10. Internal code names and stored keys may stay as they are. Every visible word changes (see Vocabulary).

## 2. Vocabulary (visible text)

- **Flow** (was “automation”). **Trigger** (several per Flow, any-of). **Constraints** (was “rules” and “Only if”). **Steps** or **Actions**. **Variables** (typed). **Connections**. **Studio**. **Edition** (Full or Play-safe). **Pause** (master switch). **Block internet** (kill switch). **Listener** (always-on service, opt-in). **Trash**.
- Template value `{{flow}}` replaces `{{automation}}`; `{{automation}}` stays as a working alias so old text does not break.
- The action “Run automation” becomes “Run Flow”. Nothing visible may still say “automation”: empty states, search hints, filters, swipe actions, delete and undo snackbars, History wording (“Run again”, “Flow”, per-Flow filter), Home, Ideas, Settings text, backup and restore messages, notification text, and the in-app script API docs.

## 3. Baseline: what exists in v0.0.1

Local-first Android automation. Kotlin, Jetpack Compose, Material 3, about 8.6k lines, package `com.androx.app`, minSdk 26, target 35. No INTERNET permission (this changes in 0.0.2, see section 14).

- **Model:** Trigger → optional Condition → list of Actions. Builder, JavaScript and Visual workflow editors all read and write the same model.
- **Triggers:** Manual, Schedule (time + days), Interval (every N min or h, minimum 5), Once (date and time, then turns itself off), Device starts.
- **Conditions (all/any):** battery level, charging, time window (can wrap midnight), days of week, Wi-Fi, screen, ringer mode, Battery Saver, airplane mode, variable comparison.
- **Actions:** notification, toast, vibrate, speak, play sound, flashlight, set volume, copy to clipboard, set variable (set, add, subtract, multiply, append, remove), wait (up to 10 s each, 30 s per run), if/else, repeat, run another automation, write to log, run JavaScript.
- **Template values:** `{{battery}} {{charging}} {{time}} {{date}} {{datetime}} {{weekday}} {{timestamp}} {{wifi}} {{screen}} {{ringer}} {{random}} {{automation}} {{index}}` and any variable.
- **Script API (Rhino, 5 s limit, nothing else reachable):** `androx.log, notify, getVar, setVar, deleteVar, battery, isCharging, now, toast, vibrate, copy, speak, isWifiConnected, isScreenOn, ringer, version`.
- **Architecture:** `ExecutionEngine` (pure Kotlin, `DeviceBridge` interface, unit tested), `AutomationRunner`, `AlarmScheduler` (AlarmManager, exact alarms when allowed), JSON files in `filesDir` (automations, history, variables), hand-rolled `AppContainer`.
- **Screens:** Home, Automations, History, Ideas, Variables, Settings, Editor (Builder, Script, Workflow).
- **Permissions:** POST\_NOTIFICATIONS, SCHEDULE\_EXACT\_ALARM, RECEIVE\_BOOT\_COMPLETED, VIBRATE, ACCESS\_NETWORK\_STATE.
- **Known limits:** the workflow canvas is one linear chain; only time and boot triggers exist; no foreground service; no accessibility layer; no internet.

## 4. Platform and build upgrade \[0.0.2\]

- **Material:** upgrade to **Material 3 Expressive**. Pin `material3` at **1.4.0 stable** (the 1.5 line was still at release-candidate stage in Nov 2026). Use the Expressive components behind their opt-in markers: spring motion scheme, shape morphing, button groups, split buttons, FAB menu, floating toolbars, wavy loading indicators, emphasized type styles. Move to 1.5 only once it is stable.
- **Tooling:** the Compose BOM, Kotlin, Android Gradle Plugin, `compileSdk` and a few AndroidX libraries move together. The builder looks up the exact compatible versions at the start of the build and lists every change made. The owner fixes any remaining build errors together with the builder.
- **Kept:** minSdk 26. Target SDK follows the new compileSdk when the builder confirms compatibility.
- **New dependencies (confirmed at build time):** OkHttp (HTTP and downloads), a small MQTT client (Apache-licensed), `androidx.biometric` (app lock), Jetpack Glance (widgets), WorkManager (scheduled backup), ZXing core (QR generation). Secrets use the Android Keystore directly. **No Google Play Services dependency.**
- **Not on the list:** a minify or R8 change (Rhino and serialization are unsafe to shrink blindly); revisit later with proper keep rules.

## 5. Architecture foundations \[0.0.2\]

### 5.1 Registries (the extensibility core)

One self-describing entry per thing, so UI, search, summaries, permissions and docs are generated, not hand-written:

- **TriggerSpec, ConstraintSpec, ActionSpec:** id, category, display name, description, icon, search synonyms, required permissions or special access, delivery mechanism, background reliability label, battery-cost label, minimum SDK and hardware needs, edition availability, settings fields (typed schema), validation, plain-English summary, **outputs** (values it exposes), a **preview** (“right now this would fire / pass”), and a **Test** hook.
- **SettingSpec:** id, type, default, group, search keywords, backup inclusion, reset behaviour, visibility (Simple or Advanced mode).
- **StateProvider:** one definition per piece of device state (battery, Wi-Fi, ringer, headphones, and so on). It feeds constraints, `{{values}}`, trigger previews and the Home “Device status” card.
- **ConnectionType** (internet accounts and endpoints), **HomeCardSpec**, **SearchProvider**.
- Adding a new entry means one spec file plus one detector or executor. No model change, no migration, no new screen.

### 5.2 Data model v2

- **Flow:** id, name, description, icon, folderId, pinned, enabled, editor type (Steps, Code or Canvas), wiring mode (structured or free-form), **triggers** (list, any-of), flow-level constraints, body (a step tree for Steps and structured Canvas, a graph for free-form Canvas, a script reference for Code), run options (timeout, concurrency policy, default on-error policy, run limit), created, updated, deletedAt (Trash), version snapshots.
- **Trigger entry:** `typeId`, `params`, optional per-trigger constraints, cooldown, and “only after holding for N minutes”. Legacy typed triggers (Manual, Schedule, Interval, Once, Device boot) keep working and map onto the same entries.
- **Generic carriers:** `Event(typeId, params)`, `Check(typeId, params, negate)`, `Op(typeId, params)` so future types need no schema change. Unknown types from a newer file load as “unavailable” instead of crashing.
- **Migration v1 → v2:** automatic on first launch, idempotent, covered by tests. One trigger becomes a list of one. Old condition groups become constraint groups. Old actions become steps. v0.0.0/0.0.1 backups import cleanly.
- **Step options (every step):** continue on error, retry N times with delay, timeout, disabled, note, save result to variable.

### 5.3 Typed values and variables

- **Types:** text, number, true/false, list, dictionary. Plus **local variables per run**, and global variables shared by all Flows.
- Existing text variables keep working: numbers stored as text are read as numbers in math, and v0.0.1 backups stay valid.
- **Tools:** math expressions, string operations (substring, replace, regex, split, join, case, trim, format), JSON parse and path lookup, date formatting, list and dictionary operations, random.
- Every trigger and action exposes **outputs** as values, with “insert” chips in every text field (for example `{{sms.sender}}`, `{{notification.text}}`, `{{wifi.ssid}}`, `{{http.status}}`).

### 5.4 Storage and safety of data

- **Atomic writes** for every file, plus a rolling last-good copy and a visible “Recover from backup” path if a file is ever damaged.
- **Versioned formats** with migrations covered by tests.
- **Separate stores:** Flows, Trash, history, **daily stats** (never trimmed, so streaks and insights stay accurate), Activity log, User log, request log, variables, secrets vault, settings, Home layout, editor drafts, script library, saved presets, Connections.
- **No lost work:** editor drafts survive process death. Alarms re-arm after reboot, time or timezone change, and app update.

### 5.5 Engine v2

- Pure Kotlin and unit tested. `DeviceBridge` grows to cover the new state and actions.
- **Executors:** a structured step-tree executor and a free-form graph executor (advanced Canvas). Both share one step runner.
- **Flow control:** If / else-if, Repeat, For each (list or dictionary), While, Break, Continue, Stop Flow, Wait until (condition with timeout), Try / On error, Confirm and Prompt, Run Flow (with inputs and a returned value).
- **Run management:** run queue with concurrency limits and per-Flow policy (skip, queue, replace), cancel for any running Flow, step budget (for example 5,000 steps), run timeout, wait budget (10 s per wait, 30 s per run unless a Flow raises it in Advanced settings), watchdogs, and a clear “stopped: loop limit reached” result.
- **Debug tools:** Run from here, Run until here, **Simulator** (dry run with fake device state and fake trigger data that logs instead of acting), and live step status.
- **Explainability data:** each run records which trigger fired, which constraints passed and the values at that moment, so the app can answer “Why did this run?” and “Why didn't it run?”.

### 5.6 Trigger hub and the Listener service

- A central **TriggerHub** owns all event delivery. A few system broadcasts work without a service (device boot, time and timezone change, package replaced). Almost everything else needs a live listener.
- **Listener service (opt-in):** one foreground service with a quiet, low-importance notification. It starts only when an enabled Flow needs it and stops when none does. Settings and the Health card show exactly which Flows and triggers need it and what it costs.
- Every trigger carries a **battery-cost label** (low, medium, high) shown in the picker.
- A **Trigger log** records events received even when no Flow ran.

### 5.7 Editions (Full and Play-safe)

- Two build flavors from the same code. **Full** includes everything. **Play-safe** omits what Google Play restricts (SMS, call log and phone control, accessibility-based triggers and actions, broad app and file visibility, screenshots and camera capture).
- Omitted items still appear in the picker as “Not in this edition”, never as missing. Switching editions is a one-line build change.
- The Play decision is still open (“a high chance”). If Play is chosen, the builder writes the permission and foreground-service justification text.

### 5.8 Security and privacy

- **Secrets vault:** API keys and passwords encrypted with the Android Keystore (AES-GCM). Masked everywhere. Redacted in logs and **excluded from backups by default**, with an optional passphrase-encrypted export.
- **App lock:** biometric or PIN to open Androx, plus an option to lock only sensitive Flows and the vault. “Hide content in Recents” (secure window flag).
- **External access** (see section 16): off by default, secret token, per-Flow allowlist, audit log.
- **Internet gate:** layered access control (section 14).
- **Privacy ledger per Flow:** sensors, permissions and domains it touched, with a local audit trail.

### 5.9 Two global switches

- **Pause** (master switch): see section 6.4.
- **Block internet** (kill switch): blocks every network call at once, shown on Home and in the drawer.

## 6. App shell and navigation \[0.0.2\]

### 6.1 Bottom navigation

**Home · Flows · Studio · Settings.** History is no longer a bottom tab. It lives in three places: a **History tab on every Flow's own page**, a **global History in the drawer** (with Activity log and User log), and **Home cards** (Recent runs, Needs attention, Run insights). The History tab keeps a badge-style “unseen failures” indicator through the drawer row and Home card.

### 6.2 Navigation drawer (like the Files app side panel)

- Material 3 modal drawer: slides from the left, rounded right corners, dimmed scrim, **edge swipe to open**, predictive back. Opens from the hamburger on every top-level screen.
- **Top:** Androx wordmark and logo, then the **master Pause card** (“Androx is on / paused”, prominent switch, pause options: 1 hour, until tomorrow, until I resume) and the **Block internet** switch.
- **Main:** Home, Flows, History, Variables, Ideas.
- **Tools:** Activity log, User log, Folders, Script library, Test lab (inside Studio), Catalogue.
- **Data:** Backup and restore, Scheduled backup, **Trash** (deleted Flows with restore and a size subtitle, for example “Trash · 33 MB”).
- **Preferences:** Settings, Appearance (theme and accent), Notifications.
- **App:** Permissions and health, What's new, Help and feedback, Privacy, About (with version).
- **Footer:** Androx logo and version, Privacy and Terms links, like the Files drawer.
- Rows are icon plus label plus live subtitle, with dividers between groups. The old Home overflow items (Browse ideas, Add sample Flows, Settings) move here, and the Home three-dot menu is removed.

### 6.3 Search pill (Home and Flows) and global search

- **Hamburger outside the pill, on the left** (owner's decision, Gmail style). The pill itself is styled like the Files app: full width, rounded, dark tonal fill, same height, radius and padding, placeholder “Search Flows, history…”, search icon on the right, pinned under the status bar while scrolling.
- It replaces the wordmark in the top bar. The wordmark moves into the drawer.
- **Optional profile or app-icon slot** on the right (Gmail style) opening a sheet with version, backup and restore. \[0.0.2, small\]
- **Tapping expands to full-screen search** with recent searches and live results grouped by **Flows, History, Variables, Ideas, Settings** (and Studio items). Tapping a result opens it. **Smart search** also matches what is inside a Flow (for example “flashlight” or “every 30 min”).
- Search is generated from the registries and the settings registry, so new entries are searchable automatically.

### 6.4 Master Pause switch

- When **off (paused):** all Flows stop running. No scheduled, interval, one-time or boot trigger fires (alarms are cancelled). No notifications, toasts, sounds or speech come from Flows. The app can still be opened, Flows can still be created and edited, and history is viewable.
- **Manual runs are blocked** by default with a “Run anyway” confirmation, so a paused app is really quiet.
- When turned back **on:** schedules are re-armed. Missed one-time Flows are not run silently; the app offers to run them.
- **Visible state:** a paused banner on Home and on the Flows page, and a status chip near the search area.
- **Pause options:** 1 hour, until tomorrow, until I resume. They tie into the “Pause all / Focus” Home card and Settings quiet hours.

## 7. Home \[0.0.2\] (issues #1, #6, #7)

A complete redesign: a real “home”, organised, with new features introduced as cards. The Flow list does not live on Home (it has its own tab). Home offers creation through the speed-dial button and a New Flow card. It must look clearly better than v0.0.1.

### 7.1 Layout, top to bottom

1. **Search pill** with the hamburger outside it (section 6.3).
2. **Greeting**, contextual to time and state (“Good morning · Next Flow at 10:25”), plus **tappable status chips** (“3 active”, “1 failed”, “Exact alarms off”, “Listener on”).
3. **Paused banner** when the master switch is off.
4. **Up next** card: smaller and tidy, expandable to show the Flow's steps and a Run now button.
5. **Pinned Flows / Quick run** strip (horizontal).
6. **Feature card grid:** two columns, a mix of full-width and half-width tonal cards, consistent 24–28 dp corners, MDI icon in an icon container, and live data on each card (for example “Variables · 6 set”).
7. **Health check** and **Run insights** cards.
8. **Next 24 hours**, **Needs attention**, **Variables peek**, **Device status**.
9. **Tip of the day**, **What's new**.
10. **Recent runs**.

### 7.2 Card catalogue (first set #6 and second set #7, all approved)

- **Adopt, in Androx's own style:** New Flow; Pinned Flows; Quick run; Recent Flows; Variables (live count); Backup and restore; Scheduled backup (to a folder the user picks); User log; Activity log; Folders; Home-screen widgets; Quick Settings tile.
- **Later:** Geofences (location permission and a map) **\[LATER\]**.
- **Moved:** Script library lives in Studio **\[0.0.2\]**. Remote control was skipped for being network-based; the internet package reopens it (HTTP and MQTT triggers and the secured external-access intent, sections 14 and 16).
- **Skipped:** Forum, Videos, Cell Towers, the MacroDroid-style AI Macro Builder (AI becomes an action preset instead, section 14), Notification Bar Options, a MacroDroid-style drawer, Last Opened Actions.
- **Our own:** Device status (live battery, Wi-Fi, ringer, Battery Saver); Health check (missing permissions, exact-alarm problems, listener state, Flows that keep failing, one-tap fixes); Run insights (success ring and 7-day chart); Flow templates by category (Morning, Sleep, Focus, Battery…); Catalogue with “Try it”; Test lab.
- **Second set (all):** Next 24 hours (mini timeline); Needs attention (failed or skipped runs with one-tap Run again or Fix); Streaks and stats (runs this week, busiest Flow, **estimated** time saved, success ring); Variables peek (top variables as live chips, tap to edit inline); Battery and charging card (Flows that react to battery plus the current level); Pause all / Focus (1 hour, until tomorrow, until I resume); Tip of the day (rotating from Ideas and the Script API); What's new (shown once after an update, with the changelog).

### 7.3 Behaviours

- **Customisable layout:** an Edit Home mode with drag handles, hide and resize; long-press on a card also offers reorder, hide and resize. Layout is saved.
- Skeleton loading, pull to refresh (re-checks health and permissions), expandable cards, illustrated empty states (vector illustrations drawn for Androx) and a first-run onboarding card.
- **Speed-dial button:** New Flow, Import, Quick script.
- **App-icon long-press shortcuts:** New Flow and a pinned Flow.
- Predictive back. **Adaptive two-pane layout** on tablets and foldables.
- Insights read from the daily stats store, not the trimmed run history. Time saved is always labelled as an estimate.

## 8. Flows page \[0.0.2\] (issues #2, #9, #10)

- **Header:** title “Flows” with a count badge in a tonal pill (“Flows · 12”). Search expands inline in the Files-style pill. Filter and overflow icons.
- **Filter chips** (scroll horizontally, keep counts): All, Enabled, Disabled, Errors, Running, Pinned.
- **Empty state:** icon in a tonal circle, “No Flows yet”, primary New Flow button, plus Browse ideas and Import.
- **Flow cards:** last run status and time (“Ran 20 min ago · Success”), mini success sparkline, trigger chip, a live “Running now” indicator with a progress bar, a red failure badge that opens the log, pin star, folder chip, a type chip (Steps, Code or Canvas), the toggle on the right, and an overflow that opens a proper options sheet.
- **Organisation:** folders (a second chip row or section headers) with drag to reorder; group by folder, trigger type, status or none; sort by next run, last run, name, recently edited or most failures; view modes comfortable list, compact list and 2-column grid.
- **Multi-select** on long-press with a contextual top bar: enable, disable, pin, move to folder, duplicate, export, delete.
- **Smart search:** names AND the triggers and actions inside a Flow (“flashlight”, “every 30 min”), with filter chips for trigger and action types.
- **Swipe:** right to run, left to delete (goes to Trash, with Undo).
- **Quick tools:** speed-dial button (New Flow, From an idea, Import, Quick script); per-Flow options sheet (Run, Duplicate, Pin, Move to folder, Share or export, View history, Delete); paused banner; **Run all enabled once**.

### 8.1 Overflow menu (short, grouped, icons, dividers)

1. Select Flows (starts multi-select). 2. Sort by (compact sheet). 3. View options (sheet).
2. Import Flows (file, clipboard or nearby device). 5. Export all Flows. 6. Run all enabled once.
3. Trash (deleted Flows, restore). 8. Delete all Flows (error colour, confirmation, moves everything to Trash so it is recoverable).

### 8.2 View options sheet (live-updating, saved)

Layout (comfortable, compact, grid) · Group by (none, folder, trigger, status) · Card details switches (last run, next run, trigger chip, sparkline, action count) · Max lines for descriptions (slider) · Card style (tonal, outlined, elevated) · Folder style (chips or section headers, plus Expand all and Collapse all) · Show filter bar · Reset to default.

### 8.3 Creating a Flow

New Flow asks for the type first: **Steps**, **Code** or **Canvas**. The choice shows as a chip on the card. “From an idea” opens Ideas. Import accepts a file, the clipboard or a nearby device.

## 9. Flow page, the hub for one Flow \[0.0.2\]

Tapping a Flow opens its own page with tabs **Overview, History, Settings** and one large **Edit** button that opens the right editor.

- **Overview:** plain-English summary; triggers with live status (“Right now: …”); the constraints gate with a **Would it run now?** explanation; step outline; Run and Test (Simulator); stats (success ring, sparkline, last run, next run); **privacy ledger** (permissions, sensors, domains it touched); Connections it uses; pin toggle; folder chip.
- **History:** that Flow's runs with filters, the run details sheet, and **Why did this run?**
- **Settings:** name, icon, description, folder, enabled, run options (timeout, concurrency policy, default on-error, run limit), **version history with restore**, convert, export or share, duplicate, delete.
- **Converting is a deliberate overflow action, never a tab tap.** Steps → Canvas always works. Canvas (structured) → Steps always works. Canvas (free-form) → Steps is blocked with a clear message. Steps → Code exports starter JavaScript for actions that have an `androx` equivalent (one-way copy, labelled). Code → Steps is not possible.

## 10. Supporting features \[0.0.2\]

- Pinned Flows and Recent Flows.
- **Folders:** create, rename, colour, move, expand and collapse.
- **Trash:** soft delete, restore, empty, 30-day auto-empty, size shown. Used by delete and Delete all. The Undo snackbar stays for instant mistakes.
- **Global search** across Flows, History, Variables, Ideas, Settings and Studio.
- **Health check engine:** permissions, exact alarms, listener, repeated failures, OEM kill risk, with one-tap fixes.
- **Run insights and stats** from the daily stats store.
- First-run onboarding, What's new, Tip of the day content, Home layout storage (order, hidden, size).
- **Home-screen widgets** for pinned Flows (Glance, interactive).
- **Quick Settings tiles:** three tiles, each bound to a pinned Flow (tiles are fixed at build time).
- **App shortcuts:** New Flow plus pinned Flows (about four are shown by Android).
- **Share target** (“Share to Androx”, with text and file values) and **custom Intent received** trigger.
- **Scheduled backup:** WorkManager, a folder the user picks, frequency, keep last N, optional passphrase encryption.
- Backup and restore card on Home.

## 11. Studio tab \[0.0.2\]

A large “Studio” top bar and tonal cards with live data, in the same style as Home. Every section is finished before it ships.

- **Test lab:** run any single action, script or request with sample values; see the log, outputs and timing. It never touches Flows or history unless asked. Test sessions can be saved.
- **Connections:** saved, reusable endpoints and accounts with a **Test connection** button, a status dot, secrets in the vault, and “used by N Flows”.
- **Scripts:** a library of saved JavaScript with a run button, snapshots, and insert-into-Flow by copy or by shared reference.
- **Variables:** the typed manager with search and filters by type; create, edit, delete; JSON view; import and export.
- **Catalogue:** every trigger, constraint and action with a **Try it** button and what each needs (permission, listener, edition, battery cost).
- **Presets:** saved, configured steps for reuse.
- **Request tester:** build and send a request, view the response, **Copy as cURL**, save to a Connection.

## 12. History, logs and stats \[0.0.2\]

- **History (per run):** success-ring header; filters with counts (All, Succeeded, Failed, Skipped); per-Flow filter; day groups; run details sheet (status, trigger, constraints passed with values, step log with timings and outputs, Run again, Copy log); **Why did this run?** and **Why didn't it run?** diagnosis with fix buttons.
- **Activity log (engine events):** alarms set and fired, permission changes, skipped runs, listener start and stop, pause and resume, trigger events received, external-access calls, safe-mode events.
- **User log:** entries from the Write to log action, with Flow, time and level; filter, export, clear.
- **Request log (internet):** URL, method, status, time, size and Flow. Never secrets. Replay, and Copy as cURL.
- **Daily stats store (never trimmed):** per Flow per day counts for success, failure, skipped and duration. Powers streaks, busiest Flow, sparklines, insights and the time-saved estimate.
- **Retention** is set per log. **Failure alerts** (after N failures in a row) and an optional daily summary notification live here and in Settings.

## 13. Flow editors \[0.0.2\]

In v0.0.1 the first real control started **44% of the way down the screen**: top bar, name row, tabs and summary card used the rest. All three editors had this problem. **Rule: editor chrome gets a budget of 12% of the screen; content gets the rest.** Each editor is its own full-screen product with its own chrome.

### 13.1 Shared shell

- **No switcher inside the editors.** The Flow type (Steps, Code or Canvas) is chosen when the Flow is created and shown as a chip on its card. Converting is a deliberate overflow action (section 9).
- **One slim top bar:** back, Flow icon and name (tap to rename), Run, Save, overflow. It collapses on scroll and stays minimal while the keyboard is open. The summary sentence becomes a one-line subtitle that scrolls away.
- Editing happens in **full-screen pages and sheets**, not inline expanding cards.
- **Flow settings sheet:** name, icon, folder, description, run limits, on-error policy, enabled.
- **One undo and redo** across the whole Flow, a **live run panel** (each step's progress and outputs as it runs), an **issues list** (tap an issue to jump to it), and **autosaved drafts**.
- **Pro tools:** Run from here, Run until here, the Simulator (dry run with fake device state and trigger data, for example “what if battery were 10%?”), step comments, a variables inspector, and version history with restore.
- **Tablets and foldables:** two panes, editor on the left and an inspector on the right.
- **Shared picker** for adding triggers, constraints and actions: full-screen, with search (synonyms such as “make phone silent”), category sections or chips, recents and favourites, status badges (Ready, Needs permission, Uses listener, Needs Shizuku, Full edition only, Beta), battery cost, an inline permission fix, and a **Test** button. Category colours are tonal Material You roles, never full red or blue headers.

### 13.2 Steps editor (the rebuilt Builder)

- **Top strip (about 56 dp):** trigger chips (“Any of”) and the “Only if” gate for constraints, so the timeline starts immediately.
- **Timeline** of steps: numbered nodes joined by a line, collapsed to one line with badges (needs permission, reliability). Tapping a step opens a full-screen step editor.
- **Editing:** a **+** between steps to insert, drag handles, swipe to duplicate or delete, If / Repeat / Try blocks inside a coloured rail, **Test this step**, multi-select with bulk edit, copy and paste across Flows, and search inside a Flow.

### 13.3 Code editor (JavaScript, immersive)

- **Layout:** full-screen. The top bar hides while typing. A **docked toolbar above the keyboard** holds symbols, undo and redo, and Run. A **draggable console** peeks from the bottom (split view in landscape). Console shows logs, the return value, and errors that jump to the line.
- **Editor:** line numbers, horizontal scroll with no wrapping, highlighting, bracket matching, auto-indent, a formatter, find and replace with regex, go to line, and a line toolbar (duplicate, move, comment).
- **Intelligence:** autocomplete for `androx.`, variables and keywords with signature tips, and live error markers with line numbers.
- **Productivity:** snippets with fill-in fields, a trigger-data mock, snapshots with a diff, a watch panel for variables, themes and font settings, hardware-keyboard shortcuts (Ctrl+S, Ctrl+Enter), and a link to the script library.
- Script timeout is configurable from 5 to 30 seconds in Settings.
- **\[LATER\], needs a fully custom text engine:** code folding, minimap, multi-cursor, a step debugger.

### 13.4 Canvas editor (the rebuilt Workflow)

- **Full-bleed**, no hint paragraph, a floating translucent toolbar at the bottom.
- **Navigation and layout:** pinch zoom, pan, fit to screen, node search, auto-layout, align and distribute, snap grid, lock.
- **Node palette** (searchable sheet) and nodes for triggers, constraint gates, actions, If / Else (true and false outputs), Repeat, For each, Wait until, Try / On error, Stop, and Run Flow. Typed ports, labelled edges (true, false, error), tap a line to insert a node, multi-select, copy and paste sub-graphs.
- **Live status:** nodes light up while a Flow runs.
- **Two wiring modes:** **Structured** (default; blocks map to the same step tree as the Steps editor and convert both ways) and **Free-form** (advanced; any wiring, including loops back and Goto-style jumps). Free-form is protected by the step budget, the run timeout and a visible “stopped: loop limit reached” result.
- **\[LATER\]:** minimap, grouping, sticky comments, replay of the last run.

## 14. Internet package \[0.0.2\]

Androx gains the INTERNET permission. Its privacy statement, onboarding, About and README are rewritten: Androx sends nothing anywhere unless a Flow you made contacts a service you allowed.

### 14.1 Access in layers

1. **Global mode** (Settings, Internet): **Off** (default on first install), **Ask** (the first time a Flow contacts a new domain: Allow once, Always or Block; a notification when it runs in the background), or **On**.
2. **Per Flow (advanced):** Inherit, Allow or Block, plus an optional domain allowlist (for example only `api.telegram.org`).
3. **Guards:** Wi-Fi only; a hard limit of requests per hour so a loop cannot spam; and the one-tap **Block internet** kill switch on Home and in the drawer.

### 14.2 HTTP request action

Method, URL, headers, body (JSON, form or raw), auth (Bearer, Basic, API key), timeout, retries, follow redirects, fail on non-2xx. The response (status, body, headers) saves to variables, with JSON-path extraction into `{{values}}`. A **Test request** button and a response viewer sit in the editor. **Import from cURL** and **Copy as cURL**. Response size caps. Optional HTTPS-only. **LAN exceptions:** plain `http://` and self-signed certificates allowed for chosen local hosts only (Home Assistant, a NAS, a Raspberry Pi), with a clear warning.

### 14.3 Presets and AI

One-tap presets: Discord, Slack, Telegram, ntfy, Gotify, Pushover, Home Assistant, IFTTT, generic webhook. **Ask AI** preset with providers (OpenAI, Anthropic, Gemini, local Ollama and others) using the user's own key from the vault. A Flow-from-description generator is **\[LATER\]**.

### 14.4 Triggers

Poll a URL every N minutes and run when it changes (text diff or JSON value); RSS/Atom new item; network connected or lost; Wi-Fi name; MQTT subscribe (uses the Listener); Weather (Open-Meteo, no key). **\[LATER\]:** local webhook server (foreground service on the local network), email received via IMAP.

### 14.5 Constraints

Internet reachable; network type (Wi-Fi, mobile, VPN, metered); Wi-Fi name; IP address (a handy “at home” check); host reachable (ping).

### 14.6 Actions

HTTP request; download file; upload; check if a site is up; get public IP; DNS lookup; TCP port check; weather (Open-Meteo); MQTT publish; Wake-on-LAN; send email via SMTP (credentials in the vault); AI query; Telegram, Discord and ntfy messages; JSON helpers.

### 14.7 JavaScript `http` API

`androx.http.get`, `post` and `request`, plus JSON helpers, all behind the same gates, limits and logging. Responses come back as objects.

### 14.8 Secrets vault and Connections

Keystore AES-GCM. Masked in every screen. Redacted in logs. **Excluded from backups by default**, with an optional passphrase-encrypted export. **Connections** (Studio) store endpoints and accounts once, with a Test button, and are reused by any Flow.

### 14.9 Request log, audit and limits

Every request is logged (URL, method, status, time, size, Flow) without secrets, with replay and Copy as cURL, and a retention setting. Timeouts, response caps, rate caps, domain allowlists and HTTPS-only apply to every request, including scripts.

### 14.10 Items this reopens

**Remote control** (running a Flow from outside through HTTP, MQTT or the secured intent) and the **AI builder** (as the Ask AI action and, later, Flow-from-description) were skipped when Androx was local-only. They are back in scope.

## 15. Triggers \[0.0.2\]

The full-screen picker, registry and guards come from sections 5.1 and 13.1.

### 15.1 Rules for every trigger

- Several triggers per Flow (any-of). Per-trigger constraints, a **cooldown**, and “only after holding for N minutes” (for example battery under 20% for 5 minutes).
- Exposes its event data as values (for example `{{sms.sender}}`, `{{notification.text}}`, `{{wifi.ssid}}`, `{{battery.temp}}`).
- Shows a battery-cost label, a live “Right now: …” status, a **Test fire** button, and an inline permission fix.
- Event triggers that need live receivers use the opt-in Listener service.

### 15.2 Catalogue

- **Ready, no permission \[0.0.2\]:** Manual; Schedule; Interval; Once; Device boot; Day of week and month; Stopwatch; System time or timezone change; Screen on or off; Power connected or disconnected; Battery level crossing; Battery Saver on or off; Airplane mode; Headphones insert or remove; Torch on or off; Network and Wi-Fi change (connected, lost, transport); VPN state; Ringer or silent change; Do Not Disturb change; Device unlocked; **Intent received** (custom action); **Share to Androx** (text or file); Quick Settings tile; Widget button; Notification button; App shortcut; **Flow finished or failed**; Flow enabled or disabled; **Variable changed**; **Called by another Flow**; Androx opened.
- **Needs a permission you grant \[0.0.2\]:** Notification received (notification access); Media track changed (notification access); Bluetooth device (Bluetooth connect); Wi-Fi name (location); App launched or closed (usage access); Calendar event; Photo taken (media access); Camera in use; Microphone recording; Music or sound playing; Floating button (display over other apps); Media button.
- **Needs a permission and is \[FULL\]:** SMS received; Call incoming, active, ended and missed; App install, remove and update (package visibility).
- **\[LATER\]:** Geofence and Location (background location); Activity recognition; shake, flip, light and proximity sensors (continuous sensors need the Listener); Sunrise and sunset (manual coordinates); email (IMAP); local webhook server; accessibility-based triggers \[FULL\] (UI click, screen content, keyboard, fingerprint gesture).
- **\[NO\]:** Logcat message (root); Tasker plugin; clipboard change in the background (blocked since Android 10); Home and Power button long-press (accessibility only); Hotspot and USB tethering state (no reliable API); Failed login attempt (device admin, heavy); Shizuku stopped.
- **\[SHIZUKU\] slot** reserved.

## 16. Integration with the phone and other apps \[0.0.2\]

- **Widgets** (Jetpack Glance, interactive) for pinned Flows: run button and status. **Quick Settings tiles:** three, each bound to a pinned Flow. **App shortcuts:** New Flow and pinned Flows. **Launcher pin** for a Flow. **Share target** and the **Intent received** trigger. **Deep links** such as `androx://flow/…`, and tapping a `.androx` file imports it. A **notification channel per Flow**, a **themed monochrome app icon**, and **per-app language**.
- **External access (off by default):** when enabled, Androx generates a **secret token**. Other apps (Tasker, adb, shortcuts) must include it to run a named Flow through a secured intent. The token can be regenerated at any time, the reachable Flows can be restricted, every external call is written to the Activity log, calls are rate-limited, and a paused Androx answers with a “paused” result. It never needs the internet.

## 17. Constraints \[0.0.2\]

Constraints only read a state at the moment a Flow runs, so they need no listener service.

### 17.1 Foundation

- **State providers** (section 5.1) feed constraints, `{{values}}` and previews from one definition.
- **Registry** entries with a generic `Check(typeId, params, negate)`. v1 conditions migrate automatically.
- **Logic builder:** nested groups of **All, Any and None**, with a per-rule “is not” toggle. XOR is skipped (it can be built from groups).
- **Where they attach:** to the whole Flow, to one specific trigger (for example only fire the Wi-Fi trigger at night), or to a single step (a quick “only if” on any action).
- **Typed comparisons:** one shared operator UI that adapts to the value type (equals, not equals, less, greater, between, contains, matches).
- **Live status:** every rule shows “Right now: ✓ (battery 66%)”. A **Would it run now?** button explains in plain English why a Flow would run or skip.

### 17.2 Catalogue

- **Battery and power \[0.0.2\]:** level, charging (including charger type), temperature, Battery Saver.
- **Connectivity \[0.0.2\]:** network type (Wi-Fi, mobile, VPN, metered), Wi-Fi name (needs location), Bluetooth state or device, GPS and location mode, IP address.
- **Date and time \[0.0.2\]:** time of day, day of week, day of month, month of year, date range, calendar entry (permission), time since boot, stopwatch, sunrise and sunset (manual coordinates).
- **Device state \[0.0.2\]:** airplane mode, Do Not Disturb, ringer mode, torch, headphones, NFC, microphone recording, locked or unlocked, screen on, auto-rotate, dark theme, media playing, free storage, and **System setting** (read any value).
- **Logic and Flow \[0.0.2\]:** Compare values, Variable, **Flow state** (running, enabled, ran recently, ran N times today), **Triggered by** (which trigger, tile or widget started it).
- **Sensors \[0.0.2\]:** light and proximity work in the background. Face up or down and orientation need the Listener, because Android blocks continuous sensors for background apps.
- **Volume \[0.0.2\]:** stream level and speakerphone.
- **\[LATER\] or \[FULL\]:** app installed, enabled or running \[FULL\]; call state and phone ringing \[FULL\]; data used.
- **\[SHIZUKU\] slot:** Shizuku, root and ADB states. **\[NO\]:** keyboard showing, hotspot and USB tethering state, ambient display, work profile.

## 18. Actions \[0.0.2\]

### 18.1 Foundation

Registry with a generic `Op(typeId, params)`; the shared picker; per-step options (continue on error, retry, timeout, disabled, note, save result); **outputs** saved to variables with insert chips; structured flow control (section 5.5); **Test this step**; saved **presets**; copy and paste across Flows. **Temporary change:** settings actions (volume, brightness, Do Not Disturb) get a “restore when the Flow ends or after N minutes” toggle. Goto exists only in the free-form Canvas.

### 18.2 Catalogue

- **Flow control and logic:** If / else-if, Repeat, For each, While, Break, Continue, Stop Flow, Wait (up to 10 s), Wait until, Try / On error, Confirm and Prompt, Run Flow (inputs and returned value), Enable or disable a Flow, Pause a Flow, cancel a running Flow.
- **Variables and data:** set and modify typed values, list and dictionary operations, math, string and regex, JSON parse, date formatting, random, local variables.
- **Notify and tell:** notification builder (channel, priority, **buttons that trigger Flows**, ongoing, progress, auto-dismiss, clear), toast, vibrate, speak, play sound, a **dialog prompt that asks for input** (when the user is present), say current time.
- **Device and settings:** flashlight; set volume, adjust and mute; ringer mode and Do Not Disturb (needs DND access); brightness, screen timeout and auto-rotate (needs Modify system settings); wallpaper; keep screen awake during a run; media keys (play, pause, next); fill clipboard; biometric confirm; light level; QR generate; get location (permission); Wi-Fi scan (location).
- **Clock:** set alarm or timer, stopwatch.
- **Apps and links:** launch app, open website, launch shortcut, share. These work when the user starts them; Android blocks background launches, but a notification tap is allowed.
- **Files** (in a folder the user picks, with persisted access): read, write, append, copy, move, delete, zip.
- **Logging:** write to log (User log), clear log, export log.
- **Internet:** section 14.6.
- **\[FULL\]:** send SMS; call a number, answer, hang up; WhatsApp send; accessibility-based actions (screen text, click X,Y, OCR on screen, pixel check, screenshot, camera capture); get installed apps; kill background process.
- **\[LATER\]:** take picture, read QR (camera), OCR from an image (on device).
- **\[SHIZUKU\] slot:** airplane mode, mobile data, hotspot, USB tethering, kill or clear an app, enable or disable an app, key events, status bar, dark mode, shell.
- **\[NO\]:** toggling Wi-Fi, Bluetooth, mobile data or airplane mode directly without privileges; reading other apps' screens without accessibility; killing other apps without root; freely launching apps from the background; Goto outside the free-form Canvas (by design); a Tasker or Locale plugin.

## 19. Settings \[0.0.2\]

### 19.1 Structure

A hub instead of one long scroll: a **Settings search pill** and a **Health card** at the top (“2 things need attention”, with one-tap fixes), then category rows with an icon tile, a title and a live summary (“Pause: off · Listener: on”). Each opens its own page, like Android's own Settings.

- **Settings registry:** every setting is defined once, so it is searchable, included in backups, has its own **Reset**, and a new one needs no screen work.
- **Deep links:** Home banners open the exact setting, highlighted.
- **Change log** of setting changes (“why did this stop working?”).
- **Simple and Advanced mode** (Advanced reveals power features and reserved slots).

### 19.2 Categories

1. **Health and permissions:** every permission listed by feature with the Flows that need it, one-tap fixes, a setup wizard, and best-effort OEM “don't kill Androx” guides (Xiaomi, Samsung and others).
2. **Appearance:** theme, Material You or accent colours, pure black, density, reduce motion, haptics, per-app language, Home layout edit.
3. **Running and reliability:** Pause options, the always-on Listener (and which triggers use it), run limits (timeout, loop cap, max at once), default on-error, missed-run policy, **quiet hours**.
4. **Notifications:** channels, sound and vibration defaults, **failure alerts** (after N failures in a row), optional daily summary.
5. **Internet:** mode (Off, Ask or On), Wi-Fi only, hourly request cap, domain allow and block lists, HTTPS-only with LAN exceptions, log retention, the kill switch.
6. **Security and privacy:** **app lock** (biometric or PIN), hide content in Recents, secrets vault, log redaction.
7. **Data and storage:** retention for history, logs and Trash; storage breakdown with clear buttons; backup and restore; scheduled backup to a folder (optional encrypted export); reset options.
8. **Editor and scripting:** default editor, unsaved-changes prompt, script timeout (5 to 30 s), Advanced mode.
9. **Integrations:** assign Flows to the three tiles, manage shortcuts, **External access** (section 16).
10. **About:** version, What's new, open-source licences, the updated privacy statement, Help and feedback, **Export diagnostics** (redacted).

**\[LATER\]:** Labs and experimental flags, extra accessibility options, check for updates.

## 20. Native-feel standard (quality gate) \[0.0.2\]

**The test:** would this look out of place inside Android's own apps? If yes, it does not ship.

1. **Standard components first.** Material 3 (Expressive) components are used as designed. A custom component needs a reason and still follows Material rules.
2. **System patterns.** List rows, dialogs, sheets, search bars, menus and settings pages follow what Android's own apps do.
3. **One shape and spacing system.** The same corner radii (larger and rounder, Expressive) and 4, 8, 16, 24 spacing everywhere.
4. **Typography.** The system font (Google Sans on Pixel, Roboto elsewhere) and Material's type scale with emphasized styles for headers. No invented sizes.
5. **Colour from the system.** Material You roles for every colour, never hardcoded.
6. **Motion.** Material patterns (container transform, shared axis, fade-through) with spring physics. **Predictive back** on every screen and sheet.
7. **Feel.** Haptic vocabulary (tick, confirm, reject, drag-start, snap), correct scroll and overscroll, correct IME and insets, gestures everywhere (long-press menus, drag to reorder, pull to refresh, double-tap a tab to scroll to top).
8. **System integration.** Widgets, tiles, shortcuts, share, notification channels, themed monochrome icon, per-app language, deep links.
9. **Edge-to-edge** with correct insets for gesture navigation, cutouts and foldables; **adaptive two-pane layouts**.
10. **Delight.** Illustrated empty states, tiny success animations, a haptic when a Flow finishes, Undo on every destructive action.

**Review gate before delivery:** every screen is checked in light, dark and pure black, at large text, in landscape, and in a tablet layout. Anything that could not be verified is reported.

**Honest limits:** Google Sans, Google's product icons and illustrations are proprietary, so Androx uses the system font, Material Design Icons and its own drawn illustrations. The wordmark is an original lookalike; its stroke is thickened at small sizes (it looked thin in the Home top bar) and it lives in the drawer, About and splash.

## 21. Reliability and safety \[0.0.2\]

- Crash-safe saving and recovery; versioned data with tested migrations; drafts survive process death; alarms re-arm after reboot, time change and update.
- Run safety: run queue, concurrency limits, cancel, step budgets, wait budgets, watchdogs.
- **Safe mode** after repeated crashes: pauses Flows and offers recovery.
- OEM background-kill guides; a startup time budget with baseline profiles; skeleton loading and stable list keys.
- **Accessibility:** TalkBack labels and roles, 48 dp targets, text up to 200%, high contrast, reduce-motion path, keyboard navigation.
- **Localization-ready:** all text in resources, plurals, right-to-left support.
- **It explains itself:** Why did this run, Why didn't it run, privacy ledger, trigger log.

## 22. Edition matrix (Full and Play-safe)

- **Both editions:** everything tagged \[0.0.2\] without \[FULL\].
- **Full only:** SMS received and send; call triggers and phone actions; accessibility-based triggers and actions; app install, remove and update; installed-apps list; screenshots and camera capture; broad file visibility.
- **Play-safe:** the same screens, with those items shown as “Not in this edition”. If Play is chosen, the builder writes the justification text for the always-on service, exact alarms and any sensitive permission.

## 23. Testing and verification

- **Unit tests (pure Kotlin):** v1 → v2 migration, registries, typed values, engine control flow, budgets and cancellation, free-form executor and loop limit, scheduling math, trigger guards (cooldown, hold), constraint logic and negation, backup format, vault redaction.
- **Static checks every layer:** imports, unresolved names, exhaustive `when`, API availability. The sandbox cannot compile, so the owner's first build is the compile check and errors are fixed in one pass.
- **On-device checklist** for the owner: each trigger and action with its permission flow, pause and resume, Listener start and stop, backup and restore, migration from a v0.0.1 backup, light, dark and pure black themes.

## 24. Build order (layers, delivered as one drop)

0. Tooling upgrade (Material 3 Expressive, BOM, Kotlin, AGP, SDK).
1. Data model v2, migration, storage, daily stats.
2. Registries, engine v2, typed variables.
3. Trigger hub and Listener; constraints and actions (each finished set).
4. Internet package (gates, vault, HTTP, presets, polling, MQTT, Wake-on-LAN).
5. App shell: navigation, drawer, search, Pause.
6. Home.
7. Flows page, Flow page, folders, Trash.
8. Editors: Steps, Code, Canvas.
9. Studio.
10. Settings.
11. Integrations: widgets, tiles, shortcuts, share, external access, scheduled backup.
12. Native-feel pass, tests, README, CHANGELOG, in-app strings (Flows wording).

Each layer ends with static checks and tests. If a layer cannot be finished properly, it is cut and shown as a “Coming later” slot, per the standing rule.

## 25. Decision log (every answer given)

- Delivery: **one big drop at the end**. Hamburger **outside the pill**; the pill styled like Files.
- Internet access: **layered** (global mode, per-Flow, guards), default **Off** until enabled; all extras included plus the builder's additions.
- **Multiple triggers per Flow (any-of).** Listener service **opt-in with a quiet notification**.
- Google Play **undecided** (high chance), so two editions from one codebase.
- **Shizuku tier:** the owner was unsure; slot reserved, not built in 0.0.2.
- **Typed variables** now (text, number, true/false, list, dictionary, plus local).
- **Studio** takes History's bottom-tab slot, with Connections inside. History moves to per-Flow, the drawer and Home cards.
- **App lock** in 0.0.2. **External access** with a secret token, off by default.
- **Editors:** the switcher lives outside (type chosen at creation); Canvas offers both structured and free-form wiring; each editor is advanced and full-screen.
- **Material 3 Expressive** (1.4.0 stable); the owner will fix build errors together with the builder.
- Naming: **Flows**, **Constraints**, **Steps**; `{{flow}}` with a working `{{automation}}` alias.
- Rule: **fewer features, each complete**; registries make later additions cheap.
- The owner decides when to start and when to produce a spec. Remote control and AI are reopened by the internet package.

## 26. Open items

- **Google Play decision** (selects which edition ships).
- Exact library versions are looked up at build start and listed.
- Wordmark weight at small sizes: confirm on the device after the first build.
- **Not yet designed in detail** (the builder will design them to the standards above and the owner can review): widget visual design, onboarding copy and illustrations, the Ideas library rewrite, Help and feedback content, Tip of the day content, and the Home card visual details.
- Anything the owner adds after this spec is appended as #12, #13 and so on.

## 27. Traceability: batch spec to this spec

- **#1 Home** → section 7. **#2 Rename to Flows** → section 2. **#3 Search pill** → 6.3. **#4 Hamburger and drawer** → 6.2. **#5 Master switch** → 6.4.
- **#6 First card set** → 7.2. **#7 Second card set and UI** → 7.2 and 7.3. **#8 Drawer contents** → 6.2.
- **#9 Flows page** → section 8. **#10 Overflow and View options** → 8.1 and 8.2.
- **Supporting screens and features** → sections 9, 10, 11, 12 and 16. **Decisions and notes** → section 25. **Standing rules** → section 1. **Baseline** → section 3.
- **Issues added later:** Flow editor sections → sections 13, 15, 17 and 18. Internet → section 14. Settings → section 19. Studio → section 11. Native-feel and engineering standards → sections 20 and 21.
