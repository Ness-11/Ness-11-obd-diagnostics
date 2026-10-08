package pl.obd.core

/** Reassembles received ISO-TP only. Never sends flow control or arbitrary CAN frames. */
object ObdParser {
    data class Result(val payloads: List<EcuPayload>, val issues: List<String>)
    private data class Partial(val length: Int, val data: MutableList<Int>, var sequence: Int = 1)
    fun parse(response: ElmResponse, service: Int, pid: Int? = null, protocol: Int? = null): Result {
        val out = mutableListOf<EcuPayload>()
        val issues = mutableListOf<String>()
        val pending = mutableMapOf<String, Partial>()
        val expected = service + 0x40
        fun emit(ecu: String, bytes: List<Int>) {
            if (bytes.firstOrNull() != expected || (pid != null && bytes.getOrNull(1) != pid)) {
                issues += "$ecu: nieoczekiwany service/PID"
            } else out += EcuPayload(ecu, bytes)
        }
        val lines = response.lines.filterNot { it.startsWith("SEARCHING", true) || it.startsWith("BUS INIT", true) || it == "OK" || it == "NO DATA" || it == "?" }
        // Some CAF1 clones print a hex byte-count and numbered chunks, with no CAN ID.
        if (lines.any { Regex("^[0-9A-Fa-f]+:").containsMatchIn(it) }) {
            val length = lines.firstOrNull()?.takeIf { Regex("^[0-9A-Fa-f]{3}$").matches(it) }?.toInt(16)
            var index = 0
            val data = mutableListOf<Int>()
            for (line in lines.drop(if (length != null) 1 else 0)) {
                val m = Regex("^([0-9A-Fa-f]+):\\s*(.*)$").matchEntire(line)
                if (m == null || m.groupValues[1].toInt(16) != index++) {
                    issues += "ELM: brak lub zła kolejność fragmentów"; continue
                }
                val bytes = hexBytes(m.groupValues[2])
                if (bytes == null) issues += "ELM: niepoprawny fragment" else data += bytes
            }
            if (length == null || data.size < length || issues.isNotEmpty()) issues += "ELM: niekompletna odpowiedź wieloramkowa"
            else emit("ELM", data.take(length))
            return Result(out, issues)
        }
        for (line in lines) {
            var text = line.trim()
            var ecu = "OBD"
            val id = Regex("^([0-9A-Fa-f]{3}|[0-9A-Fa-f]{8})\\s+(.+)$").matchEntire(text)
            if (id != null) { ecu = id.groupValues[1].uppercase(); text = id.groupValues[2] }
            // Optional explicit DLC, printed as a single digit by some ELM-compatible adapters.
            val dlc = if (ecu != "OBD" && Regex("^[0-8]\\s+").containsMatchIn(text)) text.first().digitToInt() else null
            if (dlc != null) text = text.substringAfter(' ').trim()
            val b = hexBytes(text)
            if (b == null || b.isEmpty()) { issues += "$ecu: nierozpoznana linia: $line"; continue }
            if (b.size > 8 && ecu != "OBD" && b[0] != expected) { issues += "$ecu: ramka CAN dłuższa niż 8 bajtów"; continue }
            if (dlc != null && b.size != dlc) { issues += "$ecu: niekompletna ramka CAN DLC=$dlc, odebrano ${b.size}"; continue }
            if ((protocol ?: -1) in 1..5) {
                val checksumOk = if ((protocol ?: -1) in 1..2) j1850Crc(b.dropLast(1)) == b.last() else b.dropLast(1).sum().and(255) == b.last()
                if (b.size >= 5 && b[3] == expected && checksumOk) emit(b.take(3).joinToString("") { "%02X".format(it) }, b.drop(3).dropLast(1))
                else issues += "OBD: niepoprawna odpowiedź legacy lub suma kontrolna"
                continue
            }
            if (ecu == "OBD") {
                when {
                    b[0] == expected -> emit(ecu, b)
                    b.size >= 4 && b[3] == expected -> {
                        // Legacy ISO 9141 / KWP headers + checksum. Verify before stripping.
                        if (b.size >= 5 && b.dropLast(1).sum().and(255) == b.last()) emit(b.take(3).joinToString("") { "%02X".format(it) }, b.drop(3).dropLast(1))
                        else issues += "OBD: niepoprawna suma kontrolna odpowiedzi legacy"
                    }
                    else -> issues += "OBD: brak nagłówka odpowiedzi"
                }
                continue
            }
            if (b[0] == expected) { emit(ecu, b); continue } // Some CAF1 firmwares hide PCI.
            when (b[0] ushr 4) {
                0 -> {
                    val length = b[0] and 15
                    if (pending.remove(ecu) != null) issues += "$ecu: przerwana odpowiedź wieloramkowa"
                    if (length == 0 || length > b.size - 1) issues += "$ecu: ucięta ramka pojedyncza"
                    else emit(ecu, b.drop(1).take(length))
                }
                1 -> {
                    if (pending.remove(ecu) != null) issues += "$ecu: brak końca poprzedniej odpowiedzi"
                    if (b.size != 8) { issues += "$ecu: ucięta pierwsza ramka"; continue }
                    val length = ((b[0] and 15) shl 8) or b[1]
                    if (length <= 7) { issues += "$ecu: niepoprawna długość ISO-TP"; continue }
                    pending[ecu] = Partial(length, b.drop(2).toMutableList())
                }
                2 -> {
                    val p = pending[ecu]
                    if (p == null) { issues += "$ecu: ramka kontynuacji bez początku"; continue }
                    if ((b[0] and 15) != p.sequence || b.size < 2) {
                        pending.remove(ecu); issues += "$ecu: brak lub zła kolejność ramek ISO-TP"; continue
                    }
                    if (b.size < 8 && p.data.size + b.size - 1 < p.length) {
                        pending.remove(ecu); issues += "$ecu: ucięta ramka kontynuacji"; continue
                    }
                    p.data += b.drop(1)
                    p.sequence = (p.sequence + 1) and 15
                    if (p.data.size >= p.length) { emit(ecu, p.data.take(p.length)); pending.remove(ecu) }
                }
                else -> issues += "$ecu: nieoczekiwany typ ramki"
            }
        }
        pending.forEach { (ecu, p) -> issues += "$ecu: niekompletna odpowiedź ${p.data.size}/${p.length} bajtów" }
        if (out.isEmpty() && issues.isEmpty()) issues += "Brak kompletnej odpowiedzi OBD"
        return Result(out, issues)
    }
    private fun j1850Crc(bytes: List<Int>): Int {
        var crc = 255
        bytes.forEach { byte ->
            crc = crc xor byte
            repeat(8) { crc = if (crc and 128 != 0) ((crc shl 1) xor 0x1D) and 255 else (crc shl 1) and 255 }
        }
        return crc xor 255
    }
    private fun hexBytes(text: String): List<Int>? {
        val hex = text.replace(" ", "").replace("\t", "")
        if (hex.length % 2 != 0 || !Regex("[0-9A-Fa-f]+").matches(hex)) return null
        return hex.chunked(2).map { it.toInt(16) }
    }
}
