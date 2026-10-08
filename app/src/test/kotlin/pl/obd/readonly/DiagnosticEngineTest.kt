package pl.obd.readonly

import android.Manifest
import android.content.Intent
import android.os.Looper
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import kotlin.test.*
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = ObdApplication::class)
@LooperMode(LooperMode.Mode.PAUSED)
class DiagnosticEngineTest {
    private fun until(test: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(12)
        while (!test() && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idleFor(50, TimeUnit.MILLISECONDS)
            Thread.sleep(10)
        }
        assertTrue(test(), "Timed out waiting for the diagnostic engine")
    }
    @Test fun foregroundServiceKeepsSelectedRecordingRunningInBackgroundAndStopsCleanly() {
        val app = (RuntimeEnvironment.getApplication() as ObdApplication)
        shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.POST_NOTIFICATIONS)
        val engine = app.engine
        engine.setUiVisible(true); engine.preset(setOf("0C")); engine.toggleSignal("05", true)
        engine.connect(demo = true)
        until { engine.state.value.connected }
        val service = Robolectric.buildService(RecordingService::class.java).create().get()
        try {
            service.onStartCommand(Intent(app, RecordingService::class.java), 0, 1)
            until { engine.state.value.recording && (engine.state.value.activeRecording?.rows ?: 0) >= 2 }
            val startRows = engine.state.value.activeRecording!!.rows
            engine.setUiVisible(false)
            until { (engine.state.value.activeRecording?.rows ?: 0) >= startRows + 4 }
            assertTrue(engine.state.value.recording); assertTrue(engine.state.value.live)
            engine.readVin(); until { engine.state.value.vin.isNotEmpty() && !engine.state.value.busy }
            assertTrue(engine.state.value.recording)
            val id = engine.state.value.activeRecording!!.id
            service.onStartCommand(Intent(app, RecordingService::class.java).setAction(RecordingService.STOP), 0, 2)
            until { !engine.state.value.recording && !engine.state.value.live && engine.state.value.recordings.any { it.id == id && it.ended != null } }
            val store = RecordingStore(app)
            val output = ByteArrayOutputStream(); store.export(id, output)
            val csv = output.toString("UTF-8")
            assertTrue(",0C,RPM," in csv); assertTrue(",05,Temperatura płynu," in csv)
            assertFalse(",0B," in csv)
            assertTrue(engine.state.value.history.values.any { it.isNotEmpty() })
            assertTrue(engine.state.value.analysis.measurements.any { it.signalId == "0C" && it.ok > 0 })
            engine.viewReport(id); until { engine.state.value.savedReport != null }
            assertTrue("DEMO" in engine.state.value.savedReport!!)
            assertTrue("RPM" in engine.state.value.savedReport!!)
            assertTrue("P0133" in engine.state.value.savedReport!!)
            assertNotNull(engine.state.value.dtcScan)
            assertTrue(engine.state.value.scaling.containsKey("7E8"))
        } finally { service.onDestroy(); engine.disconnect(); shadowOf(Looper.getMainLooper()).idle() }
    }
    @Test fun selectionsPersistAndDisconnectClosesRecordingWithoutRestart() {
        val app = (RuntimeEnvironment.getApplication() as ObdApplication)
        val engine = app.engine
        engine.setUiVisible(true); engine.preset(setOf("0C")); engine.setCyclePause(1000)
        engine.setAlertSettings(pl.obd.core.AlertSettings(coolantHigh = 115.0))
        val restored = DiagnosticEngine(app)
        assertEquals(115.0, restored.state.value.alertSettings.coolantHigh)
        assertEquals(setOf("0C"), restored.state.value.liveIds)
        assertEquals(1000L, restored.state.value.cyclePauseMs)
        engine.connect(demo = true); until { engine.state.value.connected }
        val service = Robolectric.buildService(RecordingService::class.java).create().get()
        try {
            service.onStartCommand(Intent(app, RecordingService::class.java), 0, 1)
            until { engine.state.value.recording && (engine.state.value.activeRecording?.rows ?: 0) > 0 }
            val id = engine.state.value.activeRecording!!.id
            engine.disconnect()
            until { !engine.state.value.recording && engine.state.value.recordings.any { it.id == id && it.ended != null } }
            assertFalse(engine.state.value.connected); assertFalse(engine.state.value.live)
            assertEquals("disconnected", engine.state.value.recordings.first { it.id == id }.status)
        } finally { service.onDestroy(); engine.disconnect(); shadowOf(Looper.getMainLooper()).idle() }
    }
    @Test fun extendedMaskedSensorsAndManualDtcScanWorkWithAutomaticScanDisabled() {
        val app = RuntimeEnvironment.getApplication() as ObdApplication; val engine = app.engine
        engine.setUiVisible(true); engine.setAutoDtcs(false)
        engine.preset(setOf("67.1", "67.2", "69.commandA", "69.commandB", "78.1", "78.4", "83.1", "85.level", "8B.active", "0B", "34.lambda"))
        engine.connect(demo = true); until { engine.state.value.connected }
        val service = Robolectric.buildService(RecordingService::class.java).create().get()
        try {
            service.onStartCommand(Intent(app, RecordingService::class.java), 0, 1)
            until { engine.state.value.samples.containsKey("7E8:8B.active") && engine.state.value.unavailable.contains("7E8:78.4") }
            assertNull(engine.state.value.dtcScan)
            assertEquals(460.0, engine.state.value.samples.getValue("7E8:78.1").value)
            assertFalse(engine.state.value.samples.containsKey("7E8:69.commandB"))
            assertEquals(1L, engine.state.value.analysis.measurements.single { it.signalId == "78.4" }.unsupported)
            engine.readDtcs(); until { engine.state.value.dtcScan != null && !engine.state.value.busy }
            val id = engine.state.value.activeRecording!!.id
            engine.stopRecording(); until { !engine.state.value.recording }
            val report = RecordingStore(app).report(id)
            assertTrue("P0133" in report); assertTrue("regeneracja DPF w toku" in report)
        } finally { service.onDestroy(); engine.disconnect(); shadowOf(Looper.getMainLooper()).idle() }
    }
}
