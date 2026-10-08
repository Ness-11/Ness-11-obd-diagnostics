package pl.obd.core

import java.io.IOException
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class ElmSessionTest {
    private class Fake : ByteTransport {
        val inbound = Channel<ByteArray>(Channel.UNLIMITED)
        val writes = mutableListOf<String>()
        var closed = false
        override suspend fun open() { }
        override suspend fun read(buffer: ByteArray): Int {
            val bytes = inbound.receiveCatching().getOrNull() ?: return -1
            bytes.copyInto(buffer); return bytes.size
        }
        override suspend fun write(bytes: ByteArray) {
            val cmd = bytes.toString(Charsets.US_ASCII).trim()
            writes += cmd
            if (cmd == "ATZ") inject("ELM327 v1.5\r>")
        }
        suspend fun inject(s: String) = inbound.send(s.toByteArray(Charsets.US_ASCII))
        override fun close() { closed = true; inbound.close() }
    }
    @Test fun queueDoesNotWriteBeforePromptEvenAcrossFragments() = runTest {
        val fake = Fake(); val log = mutableListOf<Pair<Direction, String>>()
        val s = ElmSession(fake, RawLog { d, b -> log += d to b.toString(Charsets.US_ASCII) }, StandardTestDispatcher(testScheduler))
        s.start()
        val first = async { s.execute(ReadOnlyCommand.parse("ATI")) }
        runCurrent()
        val second = async { s.execute(ReadOnlyCommand.parse("ATDP")) }
        runCurrent()
        fake.inject("ATI\rELM327 v1.5\r"); runCurrent()
        assertEquals(listOf("ATZ", "ATI"), fake.writes); assertFalse(first.isCompleted)
        fake.inject(">"); runCurrent()
        assertEquals(listOf("ATZ", "ATI", "ATDP"), fake.writes)
        assertEquals(listOf("ELM327 v1.5"), first.await().lines)
        fake.inject("CAN\r>"); runCurrent(); assertEquals("CAN", second.await().lines.single())
        assertTrue(log.any { it.first == Direction.RX && it.second == ">" })
        s.close()
    }
    @Test fun timeoutClosesTransportAndRejectsQueuedTraffic() = runTest {
        val fake = Fake(); val s = ElmSession(fake, RawLog { _, _ -> }, StandardTestDispatcher(testScheduler)); s.start()
        val first = async { runCatching { s.execute(ReadOnlyCommand.parse("ATI")) } }; runCurrent()
        val second = async { runCatching { s.execute(ReadOnlyCommand.parse("ATDP")) } }; runCurrent()
        fake.inject("PARTIAL WITHOUT PROMPT"); runCurrent()
        advanceTimeBy(8_001); runCurrent()
        assertIs<ElmTimeout>(first.await().exceptionOrNull()); assertTrue(second.await().isFailure)
        assertTrue(fake.closed); assertEquals(listOf("ATZ", "ATI"), fake.writes)
    }
    @Test fun eofFailsPendingRequest() = runTest {
        val fake = Fake(); val s = ElmSession(fake, RawLog { _, _ -> }, StandardTestDispatcher(testScheduler)); s.start()
        val response = async { runCatching { s.execute(ReadOnlyCommand.parse("03")) } }; runCurrent()
        fake.inbound.close(); runCurrent()
        assertIs<IOException>(response.await().exceptionOrNull()); assertTrue(fake.closed)
    }
    @Test fun statusesSearchingAndQuestionMark() = runTest {
        val fake = Fake(); val s = ElmSession(fake, RawLog { _, _ -> }, StandardTestDispatcher(testScheduler)); s.start()
        for ((raw, status) in listOf("OK" to ElmStatus.OK, "?" to ElmStatus.UNKNOWN, "SEARCHING...\rNO DATA" to ElmStatus.NO_DATA, "CAN ERROR" to ElmStatus.ERROR)) {
            val response = async { s.execute(ReadOnlyCommand.parse("010C")) }; runCurrent()
            fake.inject("$raw\r>"); runCurrent()
            val result = response.await(); assertEquals(status, result.status)
            if (status == ElmStatus.NO_DATA) assertTrue(result.searching)
        }
        s.close()
    }
    @Test fun duplicatePromptInSameChunkCannotCompleteNextCommand() = runTest {
        val fake = Fake(); val s = ElmSession(fake, RawLog { _, _ -> }, StandardTestDispatcher(testScheduler)); s.start()
        val first = async { s.execute(ReadOnlyCommand.parse("ATI")) }; runCurrent()
        val second = async { s.execute(ReadOnlyCommand.parse("ATDP")) }; runCurrent()
        fake.inject("ELM327\r>>"); runCurrent()
        assertTrue(first.isCompleted); assertFalse(second.isCompleted)
        fake.inject("CAN\r>"); runCurrent(); assertEquals("CAN", second.await().lines.single())
        s.close()
    }
    @Test fun fullDemoHandshakeAndReads() = runTest {
        val s = ElmSession(DemoTransport(), RawLog { _, _ -> }, StandardTestDispatcher(testScheduler)); s.start()
        val obd = ObdClient(s); val info = obd.initialize()
        assertTrue(info.isCan)
        assertTrue(12 in obd.supported().byEcu.getValue("7E8"))
        assertEquals("WVWZZZ1JZXW000001", obd.vin().byEcu.getValue("7E8"))
        assertEquals(listOf("P0133"), obd.dtcs(true).byEcu.getValue("7E8 · Zapisane"))
        assertEquals(12.6, obd.voltage()); s.close()
    }
}
