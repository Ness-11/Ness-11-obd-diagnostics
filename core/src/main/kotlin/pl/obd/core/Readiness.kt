package pl.obd.core

data class MonitorStatus(val name: String, val complete: Boolean)
data class Readiness(val mil: Boolean, val dtcCount: Int, val ignition: String, val monitors: List<MonitorStatus>) {
    companion object {
        fun decode(payload: EcuPayload): Readiness {
            require(payload.bytes.take(2) == listOf(0x41, 1) && payload.bytes.size >= 6) { "Niekompletny status monitorów" }
            val (a, b, c, d) = payload.bytes.drop(2).take(4)
            val compression = b and 8 != 0
            val monitors = mutableListOf<MonitorStatus>()
            listOf("Wypadanie zapłonów", "Układ paliwowy", "Komponenty").forEachIndexed { i, name ->
                if (b and (1 shl i) != 0) monitors += MonitorStatus(name, b and (1 shl (i + 4)) == 0)
            }
            val names = if (compression) mapOf(0 to "Katalizator NMHC", 1 to "NOx/SCR", 3 to "Doładowanie", 5 to "Czujnik spalin", 6 to "Filtr cząstek", 7 to "EGR/VVT")
                else mapOf(0 to "Katalizator", 1 to "Podgrzewany katalizator", 2 to "EVAP", 3 to "Powietrze wtórne", 4 to "Klimatyzacja", 5 to "Sonda O2", 6 to "Grzałka O2", 7 to "EGR/VVT")
            names.forEach { (bit, name) -> if (c and (1 shl bit) != 0) monitors += MonitorStatus(name, d and (1 shl bit) == 0) }
            return Readiness(a and 128 != 0, a and 127, if (compression) "Samoczynny (compression)" else "Iskrowy (spark)", monitors)
        }
    }
}
