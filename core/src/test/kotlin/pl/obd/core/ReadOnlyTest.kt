package pl.obd.core

import kotlin.test.*

class ReadOnlyTest {
    @Test fun rejectEveryWriteServiceAndRawCan() {
        for (service in listOf("04", "08", "1003", "1101", "2701", "2E123400", "2F123401", "31010100", "3D00", "ATMA", "ATSH7E0", "ATCAF0", "ATFCSM1")) {
            assertFailsWith<IllegalArgumentException>(service) { ReadOnlyCommand.parse(service) }
        }
    }
    @Test fun rejectInjectionEvenAfterWhitespaceNormalization() {
        for (s in listOf("03\r04", "03\n04", "03;04", "03>04", "010C00", "22F190", "01FF", "ATZ\r")) assertFailsWith<IllegalArgumentException> { ReadOnlyCommand.parse(s) }
    }
    @Test fun allowKnownQueriesOnly() {
        for (s in listOf("01 0c", "0100", "0120", "0142", "03", "07", "0A", "0902", "ATRV")) assertTrue(ReadOnlyCommand.parse(s).wire.isNotEmpty())
    }
}
