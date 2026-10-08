package pl.obd.core

object J1979 {
    data class Reading(val pid: Int, val name: String, val value: Double, val unit: String)
    private fun data(payload: EcuPayload, service: Int, pid: Int? = null): List<Int> {
        require(payload.bytes.firstOrNull() == service + 0x40) { "Niepoprawny service" }
        if (pid != null) require(payload.bytes.getOrNull(1) == pid) { "Niepoprawny PID" }
        return payload.bytes.drop(if (pid == null) 1 else 2)
    }
    fun supported(payload: EcuPayload, page: Int): Set<Int> {
        val b = data(payload, 1, page)
        require(b.size >= 4) { "Niekompletna bitmapa PID" }
        return (1..32).filter { bit -> b[(bit - 1) / 8] and (1 shl (7 - (bit - 1) % 8)) != 0 }.map { page + it }.toSet()
    }
    fun live(payload: EcuPayload, pid: Int): Reading {
        val value = SignalCatalog.decode(payload, pid).first()
        return Reading(pid, value.definition.name, value.value, value.definition.unit)
    }
    fun vin(payload: EcuPayload): String {
        val b = data(payload, 9, 2)
        require(b.firstOrNull() == 1 && b.size == 18) { "VIN: oczekiwano licznika 01 i 17 znaków" }
        val vin = b.drop(1).map(Int::toChar).joinToString("")
        require(Regex("[A-HJ-NPR-Z0-9]{17}").matches(vin)) { "VIN: niepoprawne znaki" }
        return vin
    }
    fun legacyVin(packets: List<EcuPayload>): String {
        require(packets.size == 5) { "VIN legacy: oczekiwano 5 fragmentów" }
        val parts = packets.map { data(it, 9, 2) }.sortedBy { it.firstOrNull() }
        require(parts.map { it.firstOrNull() } == (1..5).toList() && parts.all { it.size == 5 }) { "VIN legacy: zła kolejność lub długość" }
        val bytes = parts.flatMap { it.drop(1) }
        require(bytes.take(3) == listOf(0, 0, 0)) { "VIN legacy: niepoprawne wypełnienie" }
        return vin(EcuPayload(packets.first().ecu, listOf(0x49, 2, 1) + bytes.drop(3)))
    }
    fun dtcs(payload: EcuPayload, service: Int, can: Boolean): List<String> {
        val raw = data(payload, service)
        val bytes = if (can) {
            require(raw.isNotEmpty()) { "DTC: brak licznika" }
            val count = raw.first()
            require(raw.size >= 1 + count * 2) { "DTC: niekompletna lista" }
            require(raw.drop(1 + count * 2).all { it == 0 }) { "DTC: licznik nie zgadza się z danymi" }
            raw.drop(1).take(count * 2)
        } else {
            require(raw.size % 2 == 0) { "DTC: niekompletna para" }
            raw
        }
        return bytes.chunked(2).filter { it != listOf(0, 0) }.map { (a, b) ->
            val family = "PCBU"[a ushr 6]
            "$family${(a ushr 4) and 3}${(a and 15).toString(16).uppercase()}%02X".format(b)
        }
    }
}
