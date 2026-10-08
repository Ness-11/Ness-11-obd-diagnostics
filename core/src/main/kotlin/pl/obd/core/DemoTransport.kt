package pl.obd.core

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlin.math.sin
import kotlin.math.roundToInt

/** Deterministic ECU simulator for UI checks without a car. Not a hardware validation. */
class DemoTransport : ByteTransport {
    private val incoming = Channel<ByteArray>(Channel.UNLIMITED)
    private var rest = byteArrayOf()
    private var counter = 0
    override suspend fun open() { }
    override suspend fun read(buffer: ByteArray): Int {
        if (rest.isEmpty()) rest = incoming.receiveCatching().getOrNull() ?: return -1
        val n = minOf(buffer.size, rest.size)
        rest.copyInto(buffer, endIndex = n)
        rest = rest.drop(n).toByteArray()
        return n
    }
    override suspend fun write(bytes: ByteArray) {
        delay(25)
        val cmd = bytes.toString(Charsets.US_ASCII).trim()
        val answer = when (cmd) {
            "" -> ""
            "ATZ", "ATI" -> "ELM327 v1.5 (DEMO)"
            "ATDP" -> "ISO 15765-4 (CAN 11/500)"
            "ATDPN" -> "A6"
            "ATRV" -> "12.6V"
            "0902" -> "7E8 10 14 49 02 01 57 56 57\r7E8 21 5A 5A 5A 31 4A 5A 58\r7E8 22 57 30 30 30 30 30 31"
            "03" -> "7E8 04 43 01 01 33"
            "07" -> "7E8 02 47 00"
            "0A" -> "7E8 02 4A 00"
            else -> when {
                cmd.startsWith("AT") -> "OK"
                cmd.startsWith("01") && cmd.length == 4 -> {
                    val pid = cmd.takeLast(2).toInt(16)
                    if (pid in ReadOnlyCommand.supportPages) {
                        val supported = SignalCatalog.pids + setOf(1, 0x4F)
                        val bitmap = MutableList(4) { 0 }
                        for (id in supported.filter { it > pid && it <= pid + 32 }) {
                            val bit = id - pid - 1
                            bitmap[bit / 8] = bitmap[bit / 8] or (1 shl (7 - bit % 8))
                        }
                        if (supported.any { it > pid + 32 }) bitmap[3] = bitmap[3] or 1
                        frame(listOf(0x41, pid) + bitmap)
                    } else {
                        val wave = sin(counter++ / 15.0)
                        val data = when (pid) {
                            1 -> listOf(0, 7, 0xE5, 0x40)
                            4 -> listOf((100 + 40 * wave).roundToInt())
                            5 -> listOf((120 + 10 * wave).roundToInt())
                            6, 7, 8, 9 -> listOf((128 + 10 * wave).roundToInt())
                            10 -> listOf(80)
                            11 -> listOf((120 + 20 * wave).roundToInt())
                            12 -> word(((1000 + 300 * wave) * 4).roundToInt())
                            13 -> listOf((50 + 10 * wave).roundToInt())
                            14 -> listOf(160)
                            15 -> listOf((65 + 5 * wave).roundToInt())
                            16 -> word(((12 + 3 * wave) * 100).roundToInt())
                            17, 0x45, 0x49, 0x4A, 0x4C -> listOf((70 + 20 * wave).roundToInt())
                            0x1F -> word(counter + 60)
                            0x21, 0x31 -> word(100)
                            0x23 -> word((3000 + 300 * wave).roundToInt())
                            0x2F -> listOf(180)
                            0x30 -> listOf(5)
                            0x33 -> listOf(100)
                            0x34 -> word(((1 + .04 * wave) * 32768).roundToInt()) + word(((128 + .5 * wave) * 256).roundToInt())
                            0x42 -> word(12600)
                            0x44 -> word(32768)
                            0x46 -> listOf(60)
                            0x5C -> listOf((130 + 10 * wave).roundToInt())
                            0x3C, 0x3D, 0x3E, 0x3F -> word(5000)
                            0x43 -> word(200)
                            0x47, 0x48, 0x4B, 0x52, 0x5A -> listOf(100)
                            0x4D, 0x4E -> word(counter)
                            0x59 -> word(3000)
                            0x5E -> word(100)
                            0x4F -> listOf(0, 0, 0, 0)
                            0x67 -> listOf(3, 120, 115)
                            0x69 -> listOf(7, 90, 85, 128, 0, 0, 0)
                            0x77 -> listOf(3, 65, 70, 0, 0)
                            0x78, 0x79 -> listOf(3) + word(5000) + word(4500) + word(0) + word(0)
                            0x83 -> listOf(3) + word(150) + word(25) + word(0) + word(0)
                            0x85 -> listOf(15) + word(40) + word(50) + listOf(180, 0, 0, 1, 0)
                            0x8B -> listOf(0x73, 3, 150) + word(120) + word(400)
                            else -> emptyList()
                        }
                        if (data.isEmpty()) "NO DATA" else frame(listOf(0x41, pid) + data)
                    }
                }
                else -> "NO DATA"
            }
        }
        // Split byte stream in the middle of lines to exercise prompt framing.
        val data = "$answer\r>".toByteArray(Charsets.US_ASCII)
        val half = data.size / 2
        incoming.send(data.copyOfRange(0, half))
        incoming.send(data.copyOfRange(half, data.size))
    }
    private fun word(value: Int) = listOf((value shr 8) and 255, value and 255)
    private fun frame(payload: List<Int>): String {
        fun line(bytes: List<Int>) = "7E8 " + bytes.joinToString(" ") { "%02X".format(it) }
        if (payload.size <= 7) return line(listOf(payload.size) + payload)
        val lines = mutableListOf(line(listOf(0x10 or (payload.size shr 8), payload.size and 255) + payload.take(6)))
        payload.drop(6).chunked(7).forEachIndexed { i, bytes -> lines += line(listOf(0x20 or ((i + 1) and 15)) + bytes) }
        return lines.joinToString("\r")
    }
    override fun close() { incoming.close() }
}
