package pl.obd.core

import kotlin.test.*

class DiagnosticAnalysisTest {
    private fun sample(a: DiagnosticAnalyzer, id: String, value: Double?, time: Long, ecu: String = "7E8", status: String = if (value == null) "NO_DATA" else "OK") = a.accept(ecu, id, id, "unit", value, status, time, "utc-$time", 150)
    @Test fun statisticsPreserveMissingAndSeparateEcus() {
        val a = DiagnosticAnalyzer()
        sample(a, "0C", 800.0, 0); sample(a, "0C", null, 1000); sample(a, "0C", 1000.0, 2000); sample(a, "0C", 2000.0, 2000, "7E9")
        val m = a.snapshot().measurements.first { it.ecu == "7E8" }
        assertEquals(2L, m.ok); assertEquals(1L, m.missing); assertEquals(900.0, m.average)
        assertEquals(800.0, m.minimum); assertEquals(2000.0, m.meanIntervalMs)
        assertEquals(2, a.snapshot().measurements.size)
    }
    @Test fun durationHysteresisAndNoRepeatedEvents() {
        val a = DiagnosticAnalyzer(AlertSettings(holdMs = 2000))
        sample(a, "05", 111.0, 0); sample(a, "05", 111.0, 1000)
        assertTrue(a.snapshot().alerts.isEmpty())
        sample(a, "05", 111.0, 2000); sample(a, "05", 109.0, 3000)
        assertEquals(1, a.snapshot().alerts.size); assertNull(a.snapshot().alerts.single().endedUtc)
        sample(a, "05", 106.0, 4000)
        assertEquals("utc-4000", a.snapshot().alerts.single().endedUtc)
    }
    @Test fun missingAndLongGapResetEvidenceWithoutClaimingRecovery() {
        val a = DiagnosticAnalyzer(AlertSettings(holdMs = 2000))
        sample(a, "05", 111.0, 0); sample(a, "05", null, 1000); sample(a, "05", 111.0, 2000); sample(a, "05", 111.0, 3000)
        assertTrue(a.snapshot().alerts.isEmpty())
        sample(a, "05", 111.0, 4000); sample(a, "05", 111.0, 20_000)
        assertNotNull(a.snapshot().alerts.single().endedUtc)
        assertTrue(a.snapshot().alerts.single().endReason!!.contains("przerwana"))
        sample(a, "05", 111.0, 21_000); assertEquals(1, a.snapshot().alerts.size)
    }
    @Test fun lowVoltageNeedsFreshRunningRpmFromSameEcu() {
        val a = DiagnosticAnalyzer(AlertSettings(holdMs = 1000))
        sample(a, "0C", 800.0, 0, "7E9"); sample(a, "42", 11.0, 0); sample(a, "42", 11.0, 1000)
        assertTrue(a.snapshot().alerts.isEmpty())
        sample(a, "0C", 800.0, 1500); sample(a, "42", 11.0, 1600); sample(a, "42", 11.0, 2700)
        assertEquals("low_voltage", a.snapshot().alerts.single().rule)
        sample(a, "0C", 0.0, 2800); sample(a, "42", 11.0, 2900)
        assertNotNull(a.snapshot().alerts.single().endedUtc)
    }
    @Test fun ignitionMarkerSuspendsAlertsAndKeepsQualityCounts() {
        val a = DiagnosticAnalyzer(AlertSettings(holdMs = 1000))
        a.markIgnitionOff(true, "off"); sample(a, "05", 120.0, 0); sample(a, "05", 120.0, 1000); sample(a, "05", null, 2000)
        assertTrue(a.snapshot().alerts.isEmpty()); assertEquals(1L, a.snapshot().measurements.single().missing)
        a.markIgnitionOff(false, "on"); sample(a, "05", 120.0, 3000); sample(a, "05", 120.0, 4000)
        assertEquals(1, a.snapshot().alerts.size); assertEquals(2, a.snapshot().annotations.size)
    }
    @Test fun noMixtureOrManufacturerAlertsAreGuessed() {
        val a = DiagnosticAnalyzer(AlertSettings(holdMs = 1000))
        sample(a, "34.lambda", .0759, 0); sample(a, "34.lambda", .0759, 1000)
        assertTrue(a.snapshot().alerts.isEmpty())
        a.configure(AlertSettings(enabled = false), "disabled")
        sample(a, "05", 130.0, 2000); sample(a, "05", 130.0, 30_000)
        assertTrue(a.snapshot().alerts.isEmpty())
        assertFailsWith<IllegalArgumentException> { AlertSettings(holdMs = 0) }
    }
    @Test fun csvRoundTripSupportsQuotesAndMissingData() {
        val row = CsvSample("utc", 100, "7E8", "10", "MAF, \"test\"", null, "g/s", "NO_DATA", 200)
        assertEquals(row, CsvSample.parseLine(row.line().trimEnd()))
        assertFailsWith<IllegalArgumentException> { CsvSample.parseLine("a,b") }
        assertFailsWith<IllegalArgumentException> { CsvSample.parseLine("\"unfinished") }
    }
    @Test fun extendedStandardDecodersAndRealWidebandBytes() {
        fun d(pid: Int, vararg data: Int) = SignalCatalog.decode(EcuPayload("7E8", listOf(0x41, pid) + data.toList()), pid)
        assertEquals(460.0, d(0x3C, 0x13, 0x88).single().value)
        assertEquals(5.0, d(0x5E, 0, 100).single().value)
        assertEquals(60.0, d(0x4E, 0, 60).single().value)
        val raw = d(0x34, 0x1D, 0xC3, 0x82, 0xC6)
        assertEquals(7619.0 / 32768, raw[0].value); assertEquals(2.7734375, raw[1].value)
        assertFailsWith<IllegalArgumentException> { d(0x4E, 0) }
    }
    @Test fun eventHistoryIsBoundedAndFinishClosesActive() {
        val a = DiagnosticAnalyzer(AlertSettings(holdMs = 1000))
        repeat(220) { i -> val t = i * 3000L; sample(a, "05", 120.0, t); sample(a, "05", 120.0, t + 1000); sample(a, "05", 100.0, t + 2000) }
        assertEquals(200, a.snapshot().alerts.size)
        sample(a, "05", 120.0, 700_000); sample(a, "05", 120.0, 701_000); a.finish("end", "Rozłączenie")
        assertTrue(a.snapshot().alerts.all { it.endedUtc != null })
    }
}
