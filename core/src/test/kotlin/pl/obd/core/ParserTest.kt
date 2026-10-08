package pl.obd.core

import kotlin.test.*

class ParserTest {
    private fun parse(raw: String, service: Int = 1, pid: Int? = 12) = ObdParser.parse(ElmResponse("", raw, raw.split('\r'), ElmStatus.DATA, false), service, pid)
    @Test fun rpmAndAllLiveFormulas() {
        val p = parse("7E8 04 41 0C 1A F8").payloads.single()
        assertEquals(1726.0, J1979.live(p, 12).value)
        val values = listOf(4 to listOf(255), 5 to listOf(0), 13 to listOf(100), 17 to listOf(255), 66 to listOf(0x30, 0xD4))
        val expected = listOf(100.0, -40.0, 100.0, 100.0, 12.5)
        values.zip(expected).forEach { (v, result) -> assertEquals(result, J1979.live(EcuPayload("7E8", listOf(0x41, v.first) + v.second), v.first).value) }
    }
    @Test fun vinReassembledByCanIdWithPadding() {
        val r = parse("7E8 10 14 49 02 01 57 56 57\r7E8 21 5A 5A 5A 31 4A 5A 58\r7E8 22 57 30 30 30 30 30 31", 9, 2)
        assertTrue(r.issues.isEmpty())
        assertEquals("WVWZZZ1JZXW000001", J1979.vin(r.payloads.single()))
    }
    @Test fun missingFinalFrameCannotProduceVin() {
        val r = parse("7E8 10 14 49 02 01 57 56 57\r7E8 21 5A 5A 5A 31 4A 5A 58", 9, 2)
        assertTrue(r.payloads.isEmpty()); assertTrue(r.issues.any { "13/20" in it })
    }
    @Test fun wrongSequenceRejected() {
        val r = parse("7E8 10 14 49 02 01 57 56 57\r7E8 22 5A 5A 5A 31 4A 5A 58", 9, 2)
        assertTrue(r.payloads.isEmpty()); assertTrue(r.issues.isNotEmpty())
    }
    @Test fun multipleEcusStaySeparate() {
        val r = parse("7E8 04 41 0C 1A F8\r7E9 04 41 0C 0F A0")
        assertEquals(setOf("7E8", "7E9"), r.payloads.map { it.ecu }.toSet())
    }
    @Test fun can29BitAndDlc() {
        val r = parse("18DAF110 8 04 41 0C 1A F8 00 00 00")
        assertTrue(r.issues.isEmpty()); assertEquals("18DAF110", r.payloads.single().ecu)
    }
    @Test fun truncatedSingleFrameAndShortPid() {
        assertTrue(parse("7E8 04 41 0C 1A").payloads.isEmpty())
        assertFailsWith<IllegalArgumentException> { J1979.live(EcuPayload("7E8", listOf(0x41, 12, 0x1A)), 12) }
    }
    @Test fun numberedElmFormatValidatesLengthAndIndex() {
        val good = parse("014\r0: 49 02 01 57 56 57\r1: 5A 5A 5A 31 4A 5A 58\r2: 57 30 30 30 30 30 31", 9, 2)
        assertEquals("WVWZZZ1JZXW000001", J1979.vin(good.payloads.single()))
        assertTrue(parse("014\r0: 49 02 01 57 56 57", 9, 2).payloads.isEmpty())
        assertTrue(parse("014\r0: 49 02 01 57 56 57\r2: 5A 5A 5A 31 4A 5A 58", 9, 2).payloads.isEmpty())
    }
    @Test fun bitmapBigEndianAndContinuation() {
        val p = EcuPayload("7E8", listOf(0x41, 0, 0x18, 0x18, 0x80, 1))
        assertEquals(setOf(4, 5, 12, 13, 17, 32), J1979.supported(p, 0))
        assertEquals(setOf(66), J1979.supported(EcuPayload("7E8", listOf(0x41, 0x40, 0x40, 0, 0, 0)), 64))
    }
    @Test fun canDtcCountAndAllFamilies() {
        val p = EcuPayload("7E8", listOf(0x43, 4, 1, 0x33, 0x52, 0x34, 0xA1, 0x23, 0xC0, 0x01))
        assertEquals(listOf("P0133", "C1234", "B2123", "U0001"), J1979.dtcs(p, 3, true))
        assertEquals(emptyList(), J1979.dtcs(EcuPayload("7E8", listOf(0x43, 0)), 3, true))
        assertFailsWith<IllegalArgumentException> { J1979.dtcs(EcuPayload("7E8", listOf(0x43, 2, 1, 0x33)), 3, true) }
    }
    @Test fun legacyDtcAndChecksum() {
        val r = parse("48 6B 10 43 01 33 3A", 3, null)
        assertTrue(r.issues.isEmpty())
        assertEquals(listOf("P0133"), J1979.dtcs(r.payloads.single(), 3, false))
        assertTrue(parse("48 6B 10 43 01 33 00", 3, null).payloads.isEmpty())
    }
    @Test fun serviceAndPidMustMatch() {
        assertTrue(parse("7E8 03 41 0D 00").payloads.isEmpty())
        assertFailsWith<IllegalArgumentException> { J1979.vin(EcuPayload("7E8", listOf(0x49, 2, 1) + List(17) { 'I'.code })) }
    }
}
