package pl.obd.core

import kotlin.test.*
import kotlinx.coroutines.test.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ExtendedDiagnosticsTest {
    private fun values(pid: Int, vararg data: Int) = SignalCatalog.decode(EcuPayload("7E8", listOf(0x41, pid) + data.toList()), pid).associate { it.definition.id to it.value }
    @Test fun masksExcludeAbsentSensorsAndKeepRealZero() {
        assertEquals(mapOf("67.2" to 0.0), values(0x67, 2, 255, 40))
        assertEquals(mapOf("77.3" to 30.0), values(0x77, 4, 0, 0, 70, 0))
        assertTrue(values(0x69, 0, 0, 0, 0, 0, 0, 0).isEmpty())
        val egr = values(0x69, 7, 255, 0, 128, 255, 255, 255)
        assertEquals(mapOf("69.commandA" to 100.0, "69.actualA" to 0.0, "69.errorA" to 0.0), egr)
    }
    @Test fun exhaustNoxAdblueAndRegenerationHaveCorrectUnitsAndOffsets() {
        assertEquals(mapOf("78.1" to 460.0, "78.4" to 60.0), values(0x78, 9, 0x13, 0x88, 0, 0, 0, 0, 3, 0xE8))
        assertEquals(mapOf("83.2" to 258.0), values(0x83, 2, 0, 0, 1, 2, 0, 0, 0, 0))
        val adblue = values(0x85, 15, 0, 200, 1, 144, 255, 0xFF, 0xFF, 0xFF, 0xFF)
        assertEquals(1.0, adblue["85.use"]); assertEquals(2.0, adblue["85.demand"])
        assertEquals(100.0, adblue["85.level"]); assertEquals(4294967295.0, adblue["85.warning"])
        val dpf = values(0x8B, 0x73, 3, 255, 0, 120, 1, 144)
        assertEquals(1.0, dpf["8B.active"]); assertEquals(1.0, dpf["8B.type"])
        assertEquals(100.0, dpf["8B.trigger"]); assertEquals(120.0, dpf["8B.interval"]); assertEquals(400.0, dpf["8B.distance"])
        assertNull(dpf["8B.nox"])
    }
    @Test fun everyExtendedPidRequiresFullPayloadEvenWhenOnlyOneSensorExists() {
        for (pid in setOf(0x67, 0x69, 0x77, 0x78, 0x79, 0x83, 0x85, 0x8B)) {
            val size = SignalCatalog.definitions.first { it.pid == pid }.minimumBytes
            assertFailsWith<IllegalArgumentException> { values(pid, *IntArray(size - 1) { if (it == 0) 1 else 0 }) }
        }
        val raw = "7E8 10 0B 41 78 01 13 88 00"
        val r = ObdParser.parse(ElmResponse("0178", raw, listOf(raw), ElmStatus.DATA, false), 1, 0x78)
        assertTrue(r.payloads.isEmpty()); assertTrue(r.issues.isNotEmpty())
    }
    @Test fun scalingIsPerEcuWithZeroDefaultsAndNoFallbackForMissingConfiguration() {
        val config = PidScaling.decode(EcuPayload("7E8", listOf(0x41, 0x4F, 8, 5, 32, 40)))
        assertEquals(400.0, config.map(255)); assertEquals(8.0, config.equivalence(65535)); assertEquals(-32.0, config.current(0))
        assertEquals(1.0, PidScaling.DEFAULT.equivalence(32768)); assertEquals(0.0, PidScaling.DEFAULT.current(32768)); assertEquals(100.0, PidScaling.DEFAULT.map(100))
        val p = EcuPayload("7E8", listOf(0x41, 0x34, 0x80, 0, 0x90, 0))
        val custom = SignalCatalog.decode(p, 0x34, config)
        assertEquals(32768 * 8.0 / 65535, custom.first().value); assertEquals(4.0, custom.last().value)
        assertFailsWith<IllegalArgumentException> { SignalCatalog.decode(p, 0x34, null) }
        assertFailsWith<IllegalArgumentException> { PidScaling.decode(EcuPayload("7E8", listOf(0x41, 0x4F, 8, 5, 32))) }
        assertEquals("014F", ReadOnlyCommand.parse("014F").wire)
        for (command in listOf("04", "1003", "2701", "2E123400", "31010000", "ATSH7E0")) assertFailsWith<IllegalArgumentException> { ReadOnlyCommand.parse(command) }
    }
    @Test fun demoExercisesMultiFrameSensorReadsAndSeparateDtcGroups() = runTest {
        val session = ElmSession(DemoTransport(), RawLog { _, _ -> }, StandardTestDispatcher(testScheduler))
        try {
            session.start(); val obd = ObdClient(session); obd.initialize()
            for (pid in setOf(0x69, 0x78, 0x83, 0x85, 0x8B)) {
                val result = obd.query(1, pid); assertTrue(result.issues.isEmpty(), result.issues.toString())
                assertTrue(SignalCatalog.decode(result.payloads.single(), pid).isNotEmpty())
            }
            val scan = obd.dtcScan(true, setOf("7E8", "7E9"))
            assertEquals(listOf("P0133"), scan.groups.single { it.ecu == "7E8" && it.service == 3 }.codes)
            assertEquals("OK", scan.groups.single { it.ecu == "7E8" && it.service == 7 }.status)
            assertEquals(3, scan.groups.count { it.ecu == "7E9" && it.status == "INVALID" })
        } finally { session.close() }
    }
    @Test fun dtcScanNeverConvertsNoDataOrTruncatedFramesIntoNoFaults() = runTest {
        val incoming = kotlinx.coroutines.channels.Channel<ByteArray>(kotlinx.coroutines.channels.Channel.UNLIMITED)
        val transport = object : ByteTransport {
            override suspend fun open() {}
            override suspend fun read(buffer: ByteArray): Int {
                val bytes = incoming.receiveCatching().getOrNull() ?: return -1
                bytes.copyInto(buffer); return bytes.size
            }
            override suspend fun write(bytes: ByteArray) {
                val reply = when (bytes.toString(Charsets.US_ASCII).trim()) {
                    "ATZ" -> "ELM327 v1.5"
                    "03" -> "7E8 10 0A 43 04 01 33 02 01"
                    "07" -> "7E8 02 47 00"
                    else -> "NO DATA"
                }
                incoming.send((reply + "\r>").toByteArray(Charsets.US_ASCII))
            }
            override fun close() { incoming.close() }
        }
        val session = ElmSession(transport, RawLog { _, _ -> }, StandardTestDispatcher(testScheduler))
        try {
            session.start(); val scan = ObdClient(session).dtcScan(true, setOf("7E8"))
            assertEquals("INVALID", scan.groups.single { it.service == 3 }.status)
            assertEquals("OK", scan.groups.single { it.service == 7 }.status)
            assertEquals("NO_DATA", scan.groups.single { it.service == 10 }.status)
            assertTrue(scan.groups.single { it.service == 7 }.codes.isEmpty())
            assertTrue(scan.groups.single { it.service == 3 }.issues.isNotEmpty())
        } finally { session.close() }
    }
    @Test fun schedulerImprovesFastSamplesAndDoesNotStarveSlowCounters() {
        val selected = setOf(4, 5, 0x0B, 0x0C, 0x0D, 0x0F, 0x10, 0x11, 0x1F, 0x21, 0x23, 0x30, 0x31, 0x33, 0x34, 0x42, 0x45, 0x46, 0x49, 0x4A, 0x4C, -1)
        val scheduler = PollingScheduler(); val history = mutableMapOf<Int, MutableList<Long>>()
        var now = 0L
        while (now < 180_000) {
            val id = scheduler.next(selected, now, 500)
            if (id == null) { now += 25; continue }
            now += if (id == -1) 49 else 162
            history.getOrPut(id) { mutableListOf() } += now; scheduler.complete(id, now, true); now += 80
        }
        val rpm = history.getValue(0x0C).filter { it > 10_000 }.zipWithNext { a, b -> b - a }.sorted()
        assertTrue(rpm[rpm.size / 2] < 3500, "RPM median ${rpm[rpm.size / 2]} ms")
        assertTrue(history.getValue(0x31).size >= 3)
        assertTrue(history.getValue(0x31).zipWithNext { a, b -> b - a }.all { it >= 60_000 })
        assertTrue(history.getValue(0x05).size < history.getValue(0x0C).size)
    }
    @Test fun repeatedFailuresBackOffAndSuccessRestoresCadence() {
        val s = PollingScheduler(); assertEquals(12, s.next(setOf(12), 0, 500))
        s.complete(12, 0, false); s.complete(12, 500, false); s.complete(12, 1000, false)
        assertNull(s.next(setOf(12), 2000, 500)); assertEquals(12, s.next(setOf(12), 11_000, 500))
        s.complete(12, 11_000, true); assertEquals(12, s.next(setOf(12), 11_500, 500))
    }
    @Test fun unsupportedSensorsAreNotCountedAsTransmissionLossAndMixedCadencesDoNotExpireAlerts() {
        val analyzer = DiagnosticAnalyzer(AlertSettings(holdMs = 1000))
        analyzer.accept("7E8", "78.2", "EGT", "°C", null, "UNSUPPORTED", 0, "utc", 100)
        val m = analyzer.snapshot().measurements.single(); assertEquals(0L, m.missing); assertEquals(1L, m.unsupported)
        analyzer.accept("7E8", "05", "Płyn", "°C", 120.0, "OK", 0, "t0", 100, 15_000)
        analyzer.accept("7E8", "0D", "Prędkość", "km/h", 0.0, "OK", 6000, "t6", 100, 5000)
        analyzer.accept("7E8", "05", "Płyn", "°C", 120.0, "OK", 7000, "t7", 100, 15_000)
        assertTrue(analyzer.snapshot().alerts.any { it.rule == "coolant" && it.endedUtc == null })
    }
}
