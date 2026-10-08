package pl.obd.core

/** Implementations must make close() idempotent and unblock open/read/write. */
interface ByteTransport {
    suspend fun open()
    suspend fun read(buffer: ByteArray): Int
    suspend fun write(bytes: ByteArray)
    fun close()
}

enum class Direction { TX, RX, EVENT }
fun interface RawLog { fun record(direction: Direction, bytes: ByteArray) }
fun RawLog.event(message: String) = record(Direction.EVENT, message.toByteArray(Charsets.UTF_8))

/** Future binary CAN/ISO-TP transports can implement this without exposing ELM ASCII. */
interface DiagnosticChannel {
    suspend fun read(request: ReadRequest): DiagnosticReply
}
data class DiagnosticReply(val payloads: List<EcuPayload>, val issues: List<String>)
data class ReadRequest(val service: Int, val pid: Int? = null) {
    init { require(service in setOf(1, 3, 7, 9, 10)) { "Service poza zakresem read-only MVP" } }
}
data class EcuPayload(val ecu: String, val bytes: List<Int>)
