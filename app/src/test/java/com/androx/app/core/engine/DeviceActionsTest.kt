package com.androx.app.core.engine

import com.androx.app.core.Flow
import com.androx.app.core.Op
import com.androx.app.core.OpIds
import com.androx.app.core.Permissions
import com.androx.app.core.RunOptions
import com.androx.app.core.Step
import com.androx.app.core.StepOptions
import com.androx.app.core.params
import com.androx.app.core.spec.Edition
import com.androx.app.core.spec.SpecCatalog
import com.androx.app.core.spec.SpecStatus
import com.androx.app.engine.ActionException
import com.androx.app.engine.ScriptHost
import com.androx.app.engine.ScriptResult
import com.androx.app.engine.ScriptRunner
import com.androx.app.model.ExecutionRecord
import com.androx.app.model.ExecutionStatus
import com.androx.app.model.RingerMode
import com.androx.app.model.RunSource
import com.androx.app.model.VolumeStream
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeSystem(granted: Set<String> = setOf(Permissions.DND_ACCESS, Permissions.WRITE_SETTINGS, Permissions.NOTIFICATIONS)) : SystemActions {
    val granted = granted.toMutableSet()
    val calls = mutableListOf<String>()
    var ringer = RingerMode.NORMAL
    var dnd = "off"
    val volumes = mutableMapOf(VolumeStream.MEDIA to 40, VolumeStream.RING to 70)
    var brightness = BrightnessState(30, false)
    var timeout = 60
    var rotate = true
    var awakeOpened = 0
    var awakeClosed = 0
    var breakRinger = false
    val posted = mutableListOf<NotificationRequest>()

    override fun hasPermission(id: String) = id in granted
    override fun ringerMode() = ringer
    override fun setRinger(mode: RingerMode) {
        if (breakRinger) throw ActionException("denied")
        calls += "ringer:${mode.name}"
        ringer = mode
    }

    override fun dndFilter() = dnd
    override fun setDnd(filter: String) {
        calls += "dnd:$filter"
        dnd = filter
    }

    override fun volumePercent(stream: VolumeStream) = volumes[stream] ?: 0
    override fun setVolumePercent(stream: VolumeStream, percent: Int) {
        calls += "volume:${stream.name}:$percent"
        volumes[stream] = percent
    }

    override fun adjustVolume(stream: VolumeStream, action: String) {
        calls += "adjust:${stream.name}:$action"
        volumes[stream] = (volumes[stream] ?: 0) + if (action == "up") 10 else -10
    }

    override fun brightness() = brightness
    override fun setBrightness(percent: Int, auto: Boolean) {
        calls += "brightness:$percent:$auto"
        brightness = BrightnessState(percent, auto)
    }

    override fun screenTimeoutSeconds() = timeout
    override fun setScreenTimeoutSeconds(seconds: Int) {
        calls += "timeout:$seconds"
        timeout = seconds
    }

    override fun autoRotate() = rotate
    override fun setAutoRotate(on: Boolean) {
        calls += "rotate:$on"
        rotate = on
    }

    override fun keepAwake(minutes: Int): AutoCloseable {
        awakeOpened++
        calls += "awake:$minutes"
        return AutoCloseable { awakeClosed++ }
    }

    override fun mediaKey(key: String) {
        calls += "key:$key"
    }

    override fun openUrl(url: String): String {
        calls += "url:$url"
        return "opened"
    }

    override fun launchApp(packageName: String): String {
        calls += "app:$packageName"
        return "opened"
    }

    override fun shareText(text: String, subject: String): String {
        calls += "share:$text|$subject"
        return "shared"
    }

    override fun setAlarm(hour: Int, minute: Int, label: String, skipUi: Boolean): String {
        calls += "alarm:$hour:$minute:$label:$skipUi"
        return "alarm set"
    }

    override fun setTimer(seconds: Int, label: String): String {
        calls += "timer:$seconds:$label"
        return "timer set"
    }

    override fun postNotification(request: NotificationRequest) {
        posted += request
    }

    override fun clearNotification(id: String) {
        calls += "clear:$id"
    }
}

class DeviceActionsTest {
    private val noScripts = object : ScriptRunner {
        override fun run(source: String, host: ScriptHost): ScriptResult = ScriptResult.Success
    }

    private fun engine(sys: SystemActions?, device: EngineFakeDevice = EngineFakeDevice(), extra: Map<String, ActionExecutor> = emptyMap()) =
        FlowEngine(device = device, variables = MapValueStore(), findFlow = { null }, scripts = noScripts, extraActions = extra, system = sys)

    private fun step(typeId: String, p: JsonObject = params(), options: StepOptions = StepOptions()) = Step("s-$typeId", Op(typeId, p), options)
    private fun flow(vararg steps: Step) = Flow(id = "f", name = "Device flow", steps = steps.toList(), runOptions = RunOptions(timeoutSeconds = 0))
    private suspend fun run(e: FlowEngine, vararg steps: Step): ExecutionRecord = e.execute(flow(*steps), RunSource.MANUAL)

    // ── permissions ─────────────────────────────────────────────────────────────────────────

    @Test
    fun silentNeedsDoNotDisturbAccessAndExplainsHowToFixIt() = runTest {
        val sys = FakeSystem(granted = emptySet())
        val rec = run(engine(sys), step(OpIds.RINGER_MODE, params("mode" to RingerMode.SILENT)))
        assertEquals(ExecutionStatus.FAILED, rec.status)
        assertEquals(Permissions.fixText(Permissions.DND_ACCESS), rec.message)
        assertTrue(rec.message!!.contains("Settings"))
        assertTrue(sys.calls.isEmpty())
        // Vibrate doesn't need that access.
        assertEquals(ExecutionStatus.SUCCESS, run(engine(sys), step(OpIds.RINGER_MODE, params("mode" to RingerMode.VIBRATE))).status)
    }

    @Test
    fun settingsActionsNeedTheModifySettingsAccess() = runTest {
        val sys = FakeSystem(granted = emptySet())
        for (s in listOf(
            step(OpIds.BRIGHTNESS, params("percent" to 10)),
            step(OpIds.SCREEN_TIMEOUT, params("seconds" to 30)),
            step(OpIds.AUTO_ROTATE, params("action" to "off")),
        )) {
            val rec = run(engine(sys), s)
            assertEquals(Permissions.fixText(Permissions.WRITE_SETTINGS), rec.message)
        }
        assertTrue(sys.calls.isEmpty())
    }

    @Test
    fun withoutDeviceActionsEveryOneExplainsItself() = runTest {
        val rec = run(engine(null), step(OpIds.DND, params("filter" to "off")))
        assertEquals("Device actions aren't available here.", rec.message)
    }

    // ── restore at the end ──────────────────────────────────────────────────────────────────

    @Test
    fun temporaryChangesAreUndoneNewestFirstWhenTheFlowEnds() = runTest {
        val sys = FakeSystem()
        val rec = run(
            engine(sys),
            step(OpIds.RINGER_MODE, params("mode" to RingerMode.SILENT, "restore" to true)),
            step(OpIds.DND, params("filter" to "priority", "restore" to true)),
            step(OpIds.VOLUME_ADJUST, params("stream" to VolumeStream.MEDIA, "action" to "down", "restore" to true)),
        )
        assertEquals(ExecutionStatus.SUCCESS, rec.status)
        assertEquals(
            listOf("ringer:SILENT", "dnd:priority", "adjust:MEDIA:down", "volume:MEDIA:40", "dnd:off", "ringer:NORMAL"),
            sys.calls,
        )
        assertEquals(3, rec.logs.count { it.contains("restored:") })
    }

    @Test
    fun withoutTheRestoreSwitchNothingIsUndone() = runTest {
        val sys = FakeSystem()
        run(engine(sys), step(OpIds.RINGER_MODE, params("mode" to RingerMode.SILENT)))
        assertEquals(listOf("ringer:SILENT"), sys.calls)
        assertEquals(RingerMode.SILENT, sys.ringer)
    }

    @Test
    fun changesAreUndoneEvenWhenALaterStepFails() = runTest {
        val sys = FakeSystem()
        val boom = ActionExecutor { _, _ -> throw ActionException("kaput") }
        val rec = run(engine(sys, extra = mapOf("boom" to boom)), step(OpIds.RINGER_MODE, params("mode" to RingerMode.SILENT, "restore" to true)), step("boom"))
        assertEquals(ExecutionStatus.FAILED, rec.status)
        assertEquals(RingerMode.NORMAL, sys.ringer)
        assertTrue(rec.logs.any { it.contains("restored: ringer back to normal") })
    }

    @Test
    fun changesAreUndoneWhenTheRunIsCancelled() = runTest {
        val sys = FakeSystem()
        val e = engine(sys)
        val job = launch {
            run(e, step(OpIds.RINGER_MODE, params("mode" to RingerMode.SILENT, "restore" to true)), step(OpIds.WAIT, params("seconds" to 10)))
        }
        yield()
        job.cancel()
        job.join()
        assertEquals(RingerMode.NORMAL, sys.ringer)
    }

    @Test
    fun aRestoreThatFailsIsLoggedAndTheOthersStillRun() = runTest {
        val sys = FakeSystem()
        val flip = ActionExecutor { _, _ -> sys.breakRinger = true; null }
        val rec = run(
            engine(sys, extra = mapOf("flip" to flip)),
            step(OpIds.DND, params("filter" to "alarms", "restore" to true)),
            step(OpIds.RINGER_MODE, params("mode" to RingerMode.SILENT, "restore" to true)),
            step("flip"),
        )
        assertEquals(ExecutionStatus.SUCCESS, rec.status)
        assertTrue(rec.logs.any { it.contains("couldn't restore (ringer back to normal): denied") })
        assertEquals("off", sys.dnd)
    }

    @Test
    fun theOlderSetVolumeActionCanRestoreToo() = runTest {
        val sys = FakeSystem()
        val device = EngineFakeDevice()
        run(engine(sys, device), step(OpIds.SET_VOLUME, params("stream" to VolumeStream.MEDIA, "percent" to 10, "restore" to true)))
        assertEquals(listOf("volume:MEDIA:10"), device.calls)
        assertEquals(listOf("volume:MEDIA:40"), sys.calls)
        val rec = run(engine(null), step(OpIds.SET_VOLUME, params("stream" to VolumeStream.MEDIA, "percent" to 10, "restore" to true)))
        assertTrue(rec.message!!.contains("needs device actions"))
    }

    @Test
    fun simulationDescribesAndTouchesNothing() = runTest {
        val sys = FakeSystem()
        val rec = engine(sys).simulate(
            flow(
                step(OpIds.RINGER_MODE, params("mode" to RingerMode.SILENT, "restore" to true)),
                step(OpIds.KEEP_AWAKE),
                step(OpIds.OPEN_URL, params("url" to "https://example.com")),
            ),
        )
        assertEquals(ExecutionStatus.SUCCESS, rec.status)
        assertTrue(sys.calls.isEmpty())
        assertEquals(0, sys.awakeOpened)
        assertTrue(rec.logs.any { it.contains("would: Set ringer mode") })
        assertFalse(rec.logs.any { it.contains("restored:") })
    }

    // ── individual actions ──────────────────────────────────────────────────────────────────

    @Test
    fun screenSettingsReadTheirOldValueAndRestoreIt() = runTest {
        val sys = FakeSystem()
        run(
            engine(sys),
            step(OpIds.BRIGHTNESS, params("mode" to "manual", "percent" to 80, "restore" to true)),
            step(OpIds.SCREEN_TIMEOUT, params("seconds" to 600, "restore" to true)),
            step(OpIds.AUTO_ROTATE, params("action" to "toggle", "restore" to true)),
        )
        assertEquals(
            listOf("brightness:80:false", "timeout:600", "rotate:false", "rotate:true", "timeout:60", "brightness:30:false"),
            sys.calls,
        )
    }

    @Test
    fun automaticBrightnessIsRestoredAsAutomatic() = runTest {
        val sys = FakeSystem().also { it.brightness = BrightnessState(55, true) }
        run(engine(sys), step(OpIds.BRIGHTNESS, params("mode" to "manual", "percent" to 20, "restore" to true)))
        assertEquals(BrightnessState(55, true), sys.brightness)
    }

    @Test
    fun keepAwakeIsAlwaysReleasedWhenTheRunEnds() = runTest {
        val sys = FakeSystem()
        val boom = ActionExecutor { _, _ -> throw ActionException("kaput") }
        run(engine(sys, extra = mapOf("boom" to boom)), step(OpIds.KEEP_AWAKE, params("minutes" to 99)), step("boom"))
        assertEquals(1, sys.awakeOpened)
        assertEquals(1, sys.awakeClosed)
        assertEquals(listOf("awake:10"), sys.calls) // capped at 10 minutes
    }

    @Test
    fun mediaKeysAndVolumeSteps() = runTest {
        val sys = FakeSystem()
        val rec = run(engine(sys), step(OpIds.MEDIA_KEY, params("key" to "next")), step(OpIds.VOLUME_ADJUST, params("stream" to VolumeStream.RING, "action" to "up"), StepOptions(saveResultTo = "v")))
        assertEquals(ExecutionStatus.SUCCESS, rec.status)
        assertEquals(listOf("key:next", "adjust:RING:up"), sys.calls)
    }

    @Test
    fun linksAreCheckedAndTemplatesFilledIn() = runTest {
        val sys = FakeSystem()
        val bad = run(engine(sys), step(OpIds.OPEN_URL, params("url" to "javascript:alert(1)")))
        assertTrue(bad.message!!.startsWith("Only web, email, phone and map links"))
        val file = run(engine(sys), step(OpIds.OPEN_URL, params("url" to "file:///sdcard/x")))
        assertEquals(ExecutionStatus.FAILED, file.status)
        val empty = run(engine(sys), step(OpIds.OPEN_URL, params("url" to " ")))
        assertEquals("Give a link to open.", empty.message)
        assertTrue(sys.calls.isEmpty())

        val device = EngineFakeDevice(battery = 42)
        val ok = run(engine(sys, device), step(OpIds.OPEN_URL, params("url" to "https://example.com/?b={{battery}}")))
        assertEquals(ExecutionStatus.SUCCESS, ok.status)
        assertEquals(listOf("url:https://example.com/?b=42"), sys.calls)
        assertTrue(listOf("http://a", "HTTPS://a", "mailto:a@b", "tel:123", "geo:0,0").all { u ->
            run(engine(FakeSystem()), step(OpIds.OPEN_URL, params("url" to u))).status == ExecutionStatus.SUCCESS
        })
    }

    @Test
    fun appShareAlarmAndTimer() = runTest {
        val sys = FakeSystem()
        val rec = run(
            engine(sys),
            step(OpIds.LAUNCH_APP, params("packageName" to "com.example.app")),
            step(OpIds.SHARE_TEXT, params("text" to "hi {{flow}}", "subject" to "Sub")),
            step(OpIds.SET_ALARM, params("hour" to 6, "minute" to 30, "label" to "Run", "skipUi" to true)),
            step(OpIds.SET_TIMER, params("seconds" to 90, "label" to "Tea")),
        )
        assertEquals(ExecutionStatus.SUCCESS, rec.status)
        assertEquals(listOf("app:com.example.app", "share:hi Device flow|Sub", "alarm:6:30:Run:true", "timer:90:Tea"), sys.calls)
    }

    @Test
    fun badAppAlarmAndTimerInputsAreExplained() = runTest {
        val sys = FakeSystem()
        assertEquals("Give the package name of the app to open.", run(engine(sys), step(OpIds.LAUNCH_APP, params("packageName" to ""))).message)
        assertEquals("That isn't a valid time.", run(engine(sys), step(OpIds.SET_ALARM, params("hour" to 25, "minute" to 0))).message)
        assertEquals("A timer can be 1 second to 24 hours.", run(engine(sys), step(OpIds.SET_TIMER, params("seconds" to 0))).message)
    }

    // ── notifications ───────────────────────────────────────────────────────────────────────

    @Test
    fun theNotificationBuilderPassesEverythingThrough() = runTest {
        val sys = FakeSystem()
        val rec = run(
            engine(sys, EngineFakeDevice(battery = 33)),
            step(
                OpIds.NOTIFICATION,
                params(
                    "id" to "backup", "title" to "Backup", "text" to "{{battery}}% left", "importance" to "high", "ongoing" to true,
                    "progress" to 250, "autoDismiss" to true, "dismissSeconds" to 45,
                    "b1Label" to "Pause", "b1FlowId" to "flow-a", "b1FlowName" to "Pause backup",
                    "b2Label" to "No flow", "b3FlowId" to "flow-c",
                ),
            ),
        )
        assertEquals(ExecutionStatus.SUCCESS, rec.status)
        val n = sys.posted.single()
        assertEquals("backup", n.id)
        assertEquals("Backup", n.title)
        assertEquals("33% left", n.text)
        assertEquals("high", n.importance)
        assertTrue(n.ongoing)
        assertEquals(100, n.progress)
        assertEquals(45, n.autoDismissSeconds)
        assertEquals(listOf(NotificationButton("Pause", "flow-a", "Pause backup")), n.buttons)
    }

    @Test
    fun notificationDefaultsAndClearing() = runTest {
        val sys = FakeSystem()
        run(engine(sys), step(OpIds.NOTIFICATION, params("text" to "hello")), step(OpIds.CLEAR_NOTIFICATION))
        val n = sys.posted.single()
        assertEquals("default", n.id)
        assertEquals("Androx", n.title)
        assertEquals(-1, n.progress)
        assertEquals(0, n.autoDismissSeconds)
        assertTrue(n.buttons.isEmpty())
        assertEquals(listOf("clear:default"), sys.calls)
    }

    // ── catalogue entries ───────────────────────────────────────────────────────────────────

    private val catalog = SpecCatalog.default

    @Test
    fun everyDeviceActionIsRegisteredAndDescribesItself() {
        for (id in DEVICE_OP_IDS) {
            val spec = catalog.actions[id]!!
            spec.summary(spec.defaultParams()) // must never throw, even for a brand-new empty step
            assertTrue(spec.description.isNotBlank())
        }
        assertEquals(15, DEVICE_OP_IDS.size)
    }

    @Test
    fun statusShowsWhichActionsNeedAPermissionRightNow() {
        val ringer = catalog.actions[OpIds.RINGER_MODE]!!
        assertEquals(SpecStatus.NEEDS_PERMISSION, ringer.status(Edition.FULL, 35, emptySet()))
        assertEquals(SpecStatus.READY, ringer.status(Edition.FULL, 35, setOf(Permissions.DND_ACCESS)))
        assertEquals(SpecStatus.NEEDS_PERMISSION, catalog.actions[OpIds.BRIGHTNESS]!!.status(Edition.FULL, 35, setOf(Permissions.DND_ACCESS)))
        assertEquals(SpecStatus.READY, catalog.actions[OpIds.KEEP_AWAKE]!!.status(Edition.FULL, 35, emptySet()))
        assertEquals(SpecStatus.NEEDS_PERMISSION, catalog.actions[OpIds.NOTIFICATION]!!.status(Edition.FULL, 35, emptySet()))
    }

    @Test
    fun specsValidateTheirInputs() {
        val url = catalog.actions[OpIds.OPEN_URL]!!
        assertTrue(url.problems(params("url" to "ftp://x")).single().contains("must start with"))
        assertTrue(url.problems(params("url" to "https://x")).isEmpty())
        assertTrue(url.problems(params("url" to "{{link}}")).isEmpty())

        val timer = catalog.actions[OpIds.SET_TIMER]!!
        assertTrue(timer.problems(params("seconds" to 0)).single().contains("at least 1"))

        val notification = catalog.actions[OpIds.NOTIFICATION]!!
        assertEquals(listOf("Button 1 needs both a name and a Flow to run."), notification.problems(params("b1Label" to "Go")))
        assertTrue(notification.problems(params("b1Label" to "Go", "b1FlowId" to "x")).isEmpty())

        val alarm = catalog.actions[OpIds.SET_ALARM]!!
        assertTrue(alarm.problems(alarm.defaultParams()).isEmpty())
    }

    @Test
    fun summariesAreReadable() {
        assertEquals("Ringer: silent (temporarily)", catalog.actions.summarize(OpIds.RINGER_MODE, params("mode" to RingerMode.SILENT, "restore" to true)))
        assertEquals("Do Not Disturb: alarms only", catalog.actions.summarize(OpIds.DND, params("filter" to "alarms")))
        assertEquals("Brightness 80%", catalog.actions.summarize(OpIds.BRIGHTNESS, params("percent" to 80)))
        assertEquals("Automatic brightness", catalog.actions.summarize(OpIds.BRIGHTNESS, params("mode" to "auto")))
        assertEquals("Alarm at 06:30 – Run", catalog.actions.summarize(OpIds.SET_ALARM, params("hour" to 6, "minute" to 30, "label" to "Run")))
        assertEquals("Backup – 33% left · 1 button", catalog.actions.summarize(OpIds.NOTIFICATION, params("title" to "Backup", "text" to "33% left", "b1Label" to "Pause", "b1FlowId" to "x")))
    }

    @Test
    fun permissionTextsNameTheExactSettingsScreen() {
        for (id in listOf(Permissions.NOTIFICATIONS, Permissions.EXACT_ALARMS, Permissions.DND_ACCESS, Permissions.WRITE_SETTINGS)) {
            val text = Permissions.fixText(id)
            assertTrue(id, text.contains("Settings"))
            assertTrue(id, text.contains("Androx"))
        }
    }
}
