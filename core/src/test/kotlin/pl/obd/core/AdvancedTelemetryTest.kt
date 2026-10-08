package pl.obd.core

import kotlin.test.*
import java.util.Locale

class AdvancedTelemetryTest {
    private fun decode(pid: Int, vararg bytes: Int) = SignalCatalog.decode(EcuPayload("7E8", listOf(0x41, pid) + bytes.toList()), pid)
    @Test fun airAndFuelUnits() {
        assertEquals(100.0, decode(0x0B, 100).single().value)
        assertEquals(25.0, decode(0x0F, 65).single().value)
        assertEquals(6.25, decode(0x10, 2, 0x71).single().value)
        assertEquals(30000.0, decode(0x23, 0x0B, 0xB8).single().value)
        assertEquals(60.0, decode(0x1F, 0, 60).single().value)
        assertEquals(0.0, decode(0x06, 128).single().value)
        assertEquals(-100.0, decode(0x07, 0).single().value)
        assertEquals(20.0, decode(0x0E, 168).single().value)
    }
    @Test fun widebandDecodesBothMeasurementsFromOneRequest() {
        val values = decode(0x34, 0x80, 0, 0x80, 0)
        assertEquals(listOf("34.lambda", "34.current"), values.map { it.definition.id })
        assertEquals(listOf(1.0, 0.0), values.map { it.value })
        assertEquals(listOf(0x34), SignalCatalog.pollingPids(setOf("34.lambda", "34.current"), setOf(0x34)))
    }
    @Test fun shortExtendedPayloadsAndUnsupportedPidRejected() {
        assertFailsWith<IllegalArgumentException> { decode(0x10, 1) }
        assertFailsWith<IllegalArgumentException> { decode(0x34, 0x80, 0, 0x80) }
        assertFailsWith<IllegalArgumentException> { ReadOnlyCommand.parse("0122") }
        assertEquals(setOf("ATRV", "0C"), SignalCatalog.available(setOf("ATRV", "0C", "06", "unknown"), setOf(12)))
    }
    @Test fun allCatalogCommandsRemainMode01Reads() {
        SignalCatalog.pids.forEach { assertEquals("01%02X".format(it), ReadOnlyCommand.pid(it).wire) }
        assertTrue(SignalCatalog.definitions.map { it.id }.distinct().size == SignalCatalog.definitions.size)
        assertEquals("0101", ReadOnlyCommand.parse("0101").wire)
        assertFailsWith<IllegalArgumentException> { ReadOnlyCommand.parse("04") }
    }
    @Test fun chartBreaksMissingValuesAndLongPauses() {
        val points = listOf(ChartPoint(0, 1.0), ChartPoint(100, 2.0), ChartPoint(200, null), ChartPoint(300, 3.0), ChartPoint(9000, 4.0))
        assertEquals(listOf(2, 1, 1), ChartSeries.segments(points, 9000, 10_000, 1000).map { it.size })
        assertEquals(listOf(ChartPoint(9000, 4.0)), ChartSeries.segments(points, 9000, 1000, 1000).flatten())
        var buffer = emptyList<ChartPoint>()
        repeat(1000) { buffer = ChartSeries.append(buffer, ChartPoint(it.toLong(), 1.0), 20) }
        assertEquals(20, buffer.size); assertEquals(980L, buffer.first().elapsedMs)
    }
    @Test fun csvKeepsUnitsUtcLocaleAndMissingValues() {
        val old = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            val row = CsvSample("2026-10-05T00:00:00Z", 50, "7E8", "10", "MAF, \"test\"", 6.25, "g/s", "OK", 150).line()
            assertTrue("6.250000" in row)
            assertTrue("\"MAF, \"\"test\"\"\"" in row)
            val missing = CsvSample("time", 100, "7E8", "10", "MAF", null, "g/s", "NO_DATA", 200).line()
            assertTrue(",MAF,,g/s,NO_DATA," in missing)
        } finally { Locale.setDefault(old) }
    }
    @Test fun readinessSeparatesSupportedFromIncompleteMonitors() {
        val spark = Readiness.decode(EcuPayload("7E8", listOf(0x41, 1, 0x82, 0x27, 0x65, 0x40)))
        assertTrue(spark.mil); assertEquals(2, spark.dtcCount)
        assertFalse(spark.monitors.first { it.name == "Układ paliwowy" }.complete)
        assertFalse(spark.monitors.first { it.name == "Grzałka O2" }.complete)
        assertTrue(spark.monitors.first { it.name == "Katalizator" }.complete)
        val compression = Readiness.decode(EcuPayload("7E8", listOf(0x41, 1, 0, 0x0F, 0xC9, 0x40)))
        assertTrue(compression.ignition.startsWith("Samoczynny"))
        assertFalse(compression.monitors.first { it.name == "Filtr cząstek" }.complete)
        assertFailsWith<IllegalArgumentException> { Readiness.decode(EcuPayload("7E8", listOf(0x41, 1, 0, 0))) }
    }
}
