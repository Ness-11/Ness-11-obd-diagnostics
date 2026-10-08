package pl.obd.readonly

import java.io.ByteArrayOutputStream
import java.nio.file.Files
import kotlin.test.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RecordingStoreTest {
    @Test fun onlySelectedSignalsAreRecordedAndExportSurvivesStop() {
        val store = RecordingStore(RuntimeEnvironment.getApplication())
        val start = store.start(setOf("0C"), false, 1000)
        store.append("2026-10-05T00:00:00Z", 1100, "7E8", "05", "Płyn", 50.0, "°C", "OK", 100)
        assertEquals(0L, store.list().first { it.id == start.id }.rows)
        store.append("2026-10-05T00:00:01Z", 2000, "7E8", "0C", "RPM", 800.0, "rpm", "OK", 150)
        store.append("2026-10-05T00:00:02Z", 3000, "7E8", "0C", "RPM", null, "rpm", "INVALID", 160)
        store.stop()
        val info = store.list().first { it.id == start.id }
        assertEquals(2L, info.rows); assertNotNull(info.ended)
        val output = ByteArrayOutputStream(); store.export(start.id, output)
        val lines = output.toString("UTF-8").trim().lines()
        assertEquals(3, lines.size)
        assertTrue(lines[1].contains(",1000,7E8,0C,RPM,800.000000,rpm,OK,150"))
        assertTrue(lines[2].contains(",RPM,,rpm,INVALID,"))
        assertFalse(output.toString("UTF-8").contains("Płyn"))
    }
    @Test fun multipleSessionsRemainIndependentAndInvalidIdsRejected() {
        val store = RecordingStore(RuntimeEnvironment.getApplication())
        assertFailsWith<IllegalArgumentException> { store.start(emptySet(), true, 0) }
        val first = store.start(setOf("0C"), true, 0); store.stop()
        val second = store.start(setOf("05"), false, 0); store.stop("disconnected")
        assertNotEquals(first.id, second.id)
        assertTrue(store.list().any { it.id == first.id && it.demo })
        assertTrue(store.list().any { it.id == second.id && it.status == "disconnected" })
        assertFailsWith<IllegalArgumentException> { store.export("../../file", ByteArrayOutputStream()) }
    }
    @Test fun reportPersistsThresholdsMarkersAndComparisonAcrossRestarts() {
        val app = RuntimeEnvironment.getApplication()
        val store = RecordingStore(app)
        val first = store.start(setOf("05"), false, 0, pl.obd.core.AlertSettings(holdMs = 1000))
        store.append("utc-0", 0, "7E8", "05", "Płyn", 120.0, "°C", "OK", 150)
        store.append("utc-1000", 1000, "7E8", "05", "Płyn", 120.0, "°C", "OK", 150)
        store.markIgnitionOff(true, "off")
        store.append("utc-2000", 2000, "7E8", "05", "Płyn", null, "°C", "NO_DATA", 600)
        store.stop()
        val restored = RecordingStore(app)
        val report = restored.report(first.id)
        assertTrue("Temperatura płynu" in report); assertTrue("zapłon wyłączony" in report)
        assertTrue("podtrzymanie 1 s" in report); assertTrue("NO_DATA" in report)
        val second = restored.start(setOf("05"), false, 0)
        restored.append("utc", 1000, "7E8", "05", "Płyn", 90.0, "°C", "OK", 150); restored.stop()
        val comparison = restored.comparison()
        assertTrue("120.00" in comparison); assertTrue("90.00" in comparison)
        assertNotEquals(first.id, second.id)
        assertFailsWith<IllegalArgumentException> { restored.report("../../anything") }
    }
    @Test fun legacyCsvReconstructsStatisticsWithoutInventingAlerts() {
        val app = RuntimeEnvironment.getApplication()
        val store = RecordingStore(app)
        val info = store.start(setOf("05"), false, 0)
        store.append("utc", 1000, "7E8", "05", "Płyn", 120.0, "°C", "OK", 150); store.stop()
        java.io.File(app.filesDir, "recordings/${info.id}.report.md").delete()
        val report = RecordingStore(app).report(info.id)
        assertTrue("120.000" in report)
        assertTrue("historia alertów i adnotacje niedostępne" in report)
    }
    @Test fun liveRpmCanGateRecordedVoltageAlertWithoutLeakingIntoCsv() {
        val store = RecordingStore(RuntimeEnvironment.getApplication())
        val info = store.start(setOf("42"), false, 0, pl.obd.core.AlertSettings(holdMs = 1000))
        store.append("utc-0", 0, "7E8", "0C", "RPM", 800.0, "rpm", "OK", 150)
        store.append("utc-0", 0, "7E8", "42", "Napięcie", 11.0, "V", "OK", 150)
        store.append("utc-1000", 1000, "7E8", "42", "Napięcie", 11.0, "V", "OK", 150)
        store.stop()
        val output = ByteArrayOutputStream(); store.export(info.id, output)
        assertFalse(",0C," in output.toString("UTF-8"))
        assertTrue("Niskie napięcie ECU" in store.report(info.id))
    }
    @Test fun dtcHistorySurvivesRestartAndBundleIncludesAllSessionFiles() {
        val app = RuntimeEnvironment.getApplication(); val store = RecordingStore(app)
        val info = store.start(setOf("0C"), false, 0)
        store.recordDtcs(pl.obd.core.DtcScan("2026-10-08T10:00:00Z", listOf(
            pl.obd.core.DtcGroup("7E8", 3, "Zapisane", "OK", listOf("P0133")),
            pl.obd.core.DtcGroup("7E8", 7, "Oczekujące", "OK", emptyList()),
            pl.obd.core.DtcGroup("7E8", 10, "Trwałe", "NO_DATA", emptyList(), listOf("0A: NO_DATA")))))
        store.append("utc", 1000, "7E8", "0C", "RPM", 800.0, "rpm", "OK", 150)
        store.stop()
        val restored = RecordingStore(app); val report = restored.report(info.id)
        assertTrue("P0133" in report); assertTrue("2026-10-08T10:00:00Z" in report)
        assertTrue("NO_DATA" in report); assertTrue("Brak DTC w poprawnej odpowiedzi" in report)
        val bytes = ByteArrayOutputStream(); restored.exportBundle(info.id, bytes)
        val entries = mutableMapOf<String, String>()
        java.util.zip.ZipInputStream(bytes.toByteArray().inputStream()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) { entries[entry.name] = zip.readBytes().toString(Charsets.UTF_8); entry = zip.nextEntry }
        }
        assertTrue("P0133" in entries.getValue("${info.id}.dtc.jsonl"))
        assertTrue("RPM" in entries.getValue("${info.id}.csv"))
        assertTrue("NO_DATA" in entries.getValue("${info.id}.report.md"))
        assertTrue("selected" in entries.getValue("${info.id}.json"))
        assertFailsWith<IllegalArgumentException> { restored.exportBundle("../escape", ByteArrayOutputStream()) }
    }
}
