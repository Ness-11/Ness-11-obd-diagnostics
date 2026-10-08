package pl.obd.core

import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class ElmTimeout(command: String) : IOException("Brak > po $command; sesja zamknięta")
enum class ElmStatus { DATA, OK, UNKNOWN, NO_DATA, ERROR }
data class ElmResponse(val command: String, val raw: String, val lines: List<String>, val status: ElmStatus, val searching: Boolean)

/** FIFO mutex is the command queue. One reader owns the byte stream for the entire session. */
class ElmSession(private val transport: ByteTransport, private val log: RawLog, ioDispatcher: CoroutineDispatcher = Dispatchers.IO) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val queue = Mutex()
    private val gate = Any()
    private var pending: CompletableDeferred<String>? = null
    private var reader: Job? = null
    private var started = false
    private var closed = false
    private val mutableFailure = MutableStateFlow<String?>(null)
    val failure = mutableFailure.asStateFlow()

    suspend fun start() {
        try {
            withTimeout(15_000) { transport.open() }
            synchronized(gate) { check(!closed); check(!started); started = true }
            reader = scope.launch { readLoop() }
            // Ignore power-on banners/prompts before the explicit synchronization exchange.
            delay(300)
            // A bare CR would repeat the adapter's previous command, which may be unsafe.
            val reset = execute(ReadOnlyCommand.parse("ATZ"))
            if (reset.status in setOf(ElmStatus.ERROR, ElmStatus.UNKNOWN, ElmStatus.NO_DATA)) throw IOException("ATZ: ${reset.status}")
        } catch (e: Exception) { fail(e); throw e }
    }

    suspend fun execute(command: ReadOnlyCommand): ElmResponse {
        val raw = exchange(command.wire, command.timeoutMs)
        val lines = raw.split('\r', '\n').map(String::trim).filter { it.isNotEmpty() && it.replace(" ", "").uppercase() != command.wire }
        val upper = lines.joinToString(" ").uppercase()
        val status = when {
            lines.any { it == "?" } -> ElmStatus.UNKNOWN
            "NO DATA" in upper -> ElmStatus.NO_DATA
            listOf("UNABLE TO CONNECT", "CAN ERROR", "BUS ERROR", "STOPPED", "BUFFER FULL", "BUS INIT: ERROR", "LV RESET").any { it in upper } -> ElmStatus.ERROR
            lines.any { it == "OK" } -> ElmStatus.OK
            else -> ElmStatus.DATA
        }
        return ElmResponse(command.wire, raw, lines, status, "SEARCHING" in upper)
    }

    private suspend fun exchange(wire: String, timeout: Long): String = queue.withLock {
        val reply = CompletableDeferred<String>()
        synchronized(gate) {
            check(started && !closed) { "Sesja nie jest połączona" }
            check(pending == null)
            pending = reply
        }
        try {
            val bytes = "$wire\r".toByteArray(Charsets.US_ASCII)
            withTimeout(timeout) {
                log.record(Direction.TX, bytes)
                transport.write(bytes)
                reply.await()
            }
        } catch (e: TimeoutCancellationException) {
            val error = ElmTimeout(wire)
            fail(error)
            throw error
        } catch (e: Exception) {
            // Cancellation during an exchange also destroys synchronization.
            fail(e)
            throw e
        } finally {
            synchronized(gate) { if (pending === reply) pending = null }
        }
    }

    private suspend fun readLoop() {
        val bytes = ByteArray(2048)
        val response = StringBuilder()
        try {
            while (currentCoroutineContext().isActive) {
                val n = transport.read(bytes)
                if (n < 0) throw IOException("Adapter zamknął strumień")
                if (n == 0) continue
                log.record(Direction.RX, bytes.copyOf(n))
                synchronized(gate) {
                    for (i in 0 until n) {
                        val char = (bytes[i].toInt() and 255).toChar()
                        if (char == '>') {
                            val body = response.toString()
                            response.clear()
                            pending?.takeUnless { it.isCompleted }?.complete(body)
                        } else {
                            if (pending?.isCompleted == false) response.append(char)
                            if (response.length > 262_144) throw IOException("Odpowiedź bez > przekroczyła 256 KiB")
                        }
                    }
                }
            }
        } catch (e: Exception) { if (!closed) fail(e) }
    }

    private fun fail(error: Exception) {
        synchronized(gate) {
            if (closed) return
            closed = true
            mutableFailure.value = error.message ?: "Utrata połączenia"
            pending?.completeExceptionally(error)
        }
        try { log.event("Sesja zamknięta: ${error.message}") } catch (_: Exception) { /* Original I/O error is already reported. */ }
        finally { transport.close(); scope.cancel() }
    }
    override fun close() = fail(IOException("Rozłączono"))
}
