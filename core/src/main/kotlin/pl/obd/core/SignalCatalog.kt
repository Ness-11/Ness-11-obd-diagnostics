package pl.obd.core

/** Explicit J1979 numeric decoders. Unknown PIDs never enter the transport whitelist. */
data class SignalDefinition(val id: String, val pid: Int?, val name: String, val unit: String, val group: String, val minimumBytes: Int, internal val convert: (List<Int>) -> Double, val supportBit: Int? = null)
data class SignalValue(val definition: SignalDefinition, val value: Double)
object SignalCatalog {
    private fun word(b: List<Int>, offset: Int = 0) = b[offset] * 256 + b[offset + 1]
    private fun signal(pid: Int, name: String, unit: String, group: String, size: Int = 1, suffix: String = "", f: (List<Int>) -> Double) =
        SignalDefinition("%02X".format(pid) + suffix, pid, name, unit, group, size, f)
    private fun sensor(pid: Int, suffix: String, name: String, unit: String, size: Int, bit: Int, f: (List<Int>) -> Double) =
        SignalDefinition("%02X".format(pid) + suffix, pid, name, unit, "Emisje / czujniki", size, f, bit)
    fun sensorSupported(def: SignalDefinition, data: List<Int>) = def.supportBit?.let { data[0] and (1 shl it) != 0 } ?: true
    val definitions = listOf(
        signal(0x04, "Obciążenie obliczone", "%", "Silnik") { it[0] * 100.0 / 255 },
        signal(0x05, "Temperatura płynu", "°C", "Temperatury") { it[0] - 40.0 },
        signal(0x0C, "RPM", "rpm", "Silnik", 2) { word(it) / 4.0 },
        signal(0x0D, "Prędkość", "km/h", "Silnik") { it[0].toDouble() },
        signal(0x11, "Położenie przepustnicy", "%", "Dolot") { it[0] * 100.0 / 255 },
        signal(0x42, "Napięcie ECU", "V", "Zasilanie", 2) { word(it) / 1000.0 },
        signal(0x0B, "MAP — ciśnienie bezwzględne", "kPa", "Dolot") { it[0].toDouble() },
        signal(0x0F, "Temperatura dolotu", "°C", "Temperatury") { it[0] - 40.0 },
        signal(0x10, "MAF — przepływ powietrza", "g/s", "Dolot", 2) { word(it) / 100.0 },
        signal(0x0E, "Wyprzedzenie zapłonu", "°", "Silnik") { it[0] / 2.0 - 64 },
        signal(0x06, "Korekta krótka bank 1", "%", "Paliwo") { it[0] * 100.0 / 128 - 100 },
        signal(0x07, "Korekta długa bank 1", "%", "Paliwo") { it[0] * 100.0 / 128 - 100 },
        signal(0x08, "Korekta krótka bank 2", "%", "Paliwo") { it[0] * 100.0 / 128 - 100 },
        signal(0x09, "Korekta długa bank 2", "%", "Paliwo") { it[0] * 100.0 / 128 - 100 },
        signal(0x0A, "Ciśnienie paliwa", "kPa", "Paliwo") { it[0] * 3.0 },
        signal(0x1F, "Czas pracy silnika", "s", "Liczniki", 2) { word(it).toDouble() },
        signal(0x21, "Dystans z kontrolką MIL", "km", "Liczniki", 2) { word(it).toDouble() },
        signal(0x23, "Ciśnienie szyny paliwa (gauge)", "kPa", "Paliwo", 2) { word(it) * 10.0 },
        signal(0x2F, "Poziom paliwa", "%", "Paliwo") { it[0] * 100.0 / 255 },
        signal(0x30, "Rozgrzania od kasowania DTC", "", "Liczniki") { it[0].toDouble() },
        signal(0x31, "Dystans od kasowania DTC", "km", "Liczniki", 2) { word(it).toDouble() },
        signal(0x33, "Ciśnienie atmosferyczne", "kPa", "Dolot") { it[0].toDouble() },
        signal(0x34, "EQR — współczynnik B1S1 (PID 34)", "ratio", "Paliwo", 4, ".lambda") { word(it) / 32768.0 },
        signal(0x34, "Prąd sondy B1S1", "mA", "Paliwo", 4, ".current") { word(it, 2) / 256.0 - 128 },
        signal(0x44, "Zadany EQR (PID 44)", "ratio", "Paliwo", 2) { word(it) / 32768.0 },
        signal(0x45, "Względne położenie przepustnicy", "%", "Dolot") { it[0] * 100.0 / 255 },
        signal(0x46, "Temperatura otoczenia", "°C", "Temperatury") { it[0] - 40.0 },
        signal(0x49, "Pedał przyspieszenia D", "%", "Dolot") { it[0] * 100.0 / 255 },
        signal(0x4A, "Pedał przyspieszenia E", "%", "Dolot") { it[0] * 100.0 / 255 },
        signal(0x4C, "Zadane sterowanie przepustnicą", "%", "Dolot") { it[0] * 100.0 / 255 },
        signal(0x5C, "Temperatura oleju", "°C", "Temperatury") { it[0] - 40.0 },
        signal(0x3C, "Temperatura katalizatora B1S1", "°C", "Temperatury", 2) { word(it) / 10.0 - 40 },
        signal(0x3D, "Temperatura katalizatora B2S1", "°C", "Temperatury", 2) { word(it) / 10.0 - 40 },
        signal(0x3E, "Temperatura katalizatora B1S2", "°C", "Temperatury", 2) { word(it) / 10.0 - 40 },
        signal(0x3F, "Temperatura katalizatora B2S2", "°C", "Temperatury", 2) { word(it) / 10.0 - 40 },
        signal(0x43, "Obciążenie bezwzględne", "%", "Silnik", 2) { word(it) * 100.0 / 255 },
        signal(0x47, "Położenie przepustnicy B", "%", "Dolot") { it[0] * 100.0 / 255 },
        signal(0x48, "Położenie przepustnicy C", "%", "Dolot") { it[0] * 100.0 / 255 },
        signal(0x4B, "Pedał przyspieszenia F", "%", "Dolot") { it[0] * 100.0 / 255 },
        signal(0x4D, "Czas z MIL", "min", "Liczniki", 2) { word(it).toDouble() },
        signal(0x4E, "Czas od kasowania DTC", "min", "Liczniki", 2) { word(it).toDouble() },
        signal(0x52, "Udział etanolu", "%", "Paliwo") { it[0] * 100.0 / 255 },
        signal(0x59, "Ciśnienie szyny paliwa absolutne", "kPa", "Paliwo", 2) { word(it) * 10.0 },
        signal(0x5A, "Względne położenie pedału", "%", "Dolot") { it[0] * 100.0 / 255 },
        signal(0x5E, "Zużycie paliwa — przepływ", "L/h", "Paliwo", 2) { word(it) / 20.0 },
        sensor(0x67, ".1", "Temperatura płynu czujnik 1", "°C", 3, 0) { it[1] - 40.0 },
        sensor(0x67, ".2", "Temperatura płynu czujnik 2", "°C", 3, 1) { it[2] - 40.0 },
        sensor(0x69, ".commandA", "EGR A — zadane", "%", 7, 0) { it[1] * 100.0 / 255 },
        sensor(0x69, ".actualA", "EGR A — rzeczywiste", "%", 7, 1) { it[2] * 100.0 / 255 },
        sensor(0x69, ".errorA", "EGR A — błąd regulacji", "%", 7, 2) { it[3] * 100.0 / 128 - 100 },
        sensor(0x69, ".commandB", "EGR B — zadane", "%", 7, 3) { it[4] * 100.0 / 255 },
        sensor(0x69, ".actualB", "EGR B — rzeczywiste", "%", 7, 4) { it[5] * 100.0 / 255 },
        sensor(0x69, ".errorB", "EGR B — błąd regulacji", "%", 7, 5) { it[6] * 100.0 / 128 - 100 },
        sensor(0x77, ".1", "Dolot bank 1 czujnik 1", "°C", 5, 0) { it[1] - 40.0 },
        sensor(0x77, ".2", "Dolot bank 1 czujnik 2", "°C", 5, 1) { it[2] - 40.0 },
        sensor(0x77, ".3", "Dolot bank 2 czujnik 1", "°C", 5, 2) { it[3] - 40.0 },
        sensor(0x77, ".4", "Dolot bank 2 czujnik 2", "°C", 5, 3) { it[4] - 40.0 },
        sensor(0x78, ".1", "EGT bank 1 czujnik 1", "°C", 9, 0) { word(it, 1) / 10.0 - 40 },
        sensor(0x78, ".2", "EGT bank 1 czujnik 2", "°C", 9, 1) { word(it, 3) / 10.0 - 40 },
        sensor(0x78, ".3", "EGT bank 1 czujnik 3", "°C", 9, 2) { word(it, 5) / 10.0 - 40 },
        sensor(0x78, ".4", "EGT bank 1 czujnik 4", "°C", 9, 3) { word(it, 7) / 10.0 - 40 },
        sensor(0x79, ".1", "EGT bank 2 czujnik 1", "°C", 9, 0) { word(it, 1) / 10.0 - 40 },
        sensor(0x79, ".2", "EGT bank 2 czujnik 2", "°C", 9, 1) { word(it, 3) / 10.0 - 40 },
        sensor(0x79, ".3", "EGT bank 2 czujnik 3", "°C", 9, 2) { word(it, 5) / 10.0 - 40 },
        sensor(0x79, ".4", "EGT bank 2 czujnik 4", "°C", 9, 3) { word(it, 7) / 10.0 - 40 },
        sensor(0x83, ".1", "NOx bank 1 czujnik 1", "ppm", 9, 0) { word(it, 1).toDouble() },
        sensor(0x83, ".2", "NOx bank 1 czujnik 2", "ppm", 9, 1) { word(it, 3).toDouble() },
        sensor(0x83, ".3", "NOx bank 2 czujnik 1", "ppm", 9, 2) { word(it, 5).toDouble() },
        sensor(0x83, ".4", "NOx bank 2 czujnik 2", "ppm", 9, 3) { word(it, 7).toDouble() },
        sensor(0x85, ".use", "Średnie zużycie AdBlue", "L/h", 10, 0) { word(it, 1) / 200.0 },
        sensor(0x85, ".demand", "Zadane średnie zużycie AdBlue", "L/h", 10, 1) { word(it, 3) / 200.0 },
        sensor(0x85, ".level", "Poziom AdBlue", "%", 10, 2) { it[5] * 100.0 / 255 },
        sensor(0x85, ".warning", "Czas pracy z ostrzeżeniem NOx", "s", 10, 3) { it.drop(6).take(4).fold(0L) { n, b -> n * 256 + b }.toDouble() },
        sensor(0x8B, ".active", "Regeneracja DPF (0 nie, 1 tak)", "", 7, 0) { (it[1] and 1).toDouble() },
        sensor(0x8B, ".type", "Typ regeneracji DPF (0 pasywna, 1 aktywna)", "", 7, 1) { ((it[1] shr 1) and 1).toDouble() },
        sensor(0x8B, ".nox", "Regeneracja adsorbera NOx (0 nie, 1 tak)", "", 7, 2) { ((it[1] shr 2) and 1).toDouble() },
        sensor(0x8B, ".desulf", "Odsiarczanie adsorbera NOx (0 nie, 1 tak)", "", 7, 3) { ((it[1] shr 3) and 1).toDouble() },
        sensor(0x8B, ".trigger", "Znormalizowany wyzwalacz regeneracji DPF", "%", 7, 4) { it[2] * 100.0 / 255 },
        sensor(0x8B, ".interval", "Średni czas między regeneracjami DPF", "min", 7, 5) { word(it, 3).toDouble() },
        sensor(0x8B, ".distance", "Średni dystans między regeneracjami DPF", "km", 7, 6) { word(it, 5).toDouble() },
        SignalDefinition("ATRV", null, "Napięcie adaptera", "V", "Zasilanie", 0, { error("ATRV has a text decoder") })
    )
    val byId = definitions.associateBy { it.id }
    val pids = definitions.mapNotNull { it.pid }.toSet()
    val defaultIds = setOf("04", "05", "0C", "0D", "11", "42", "ATRV")
    fun decode(payload: EcuPayload, pid: Int, scaling: PidScaling? = PidScaling.DEFAULT): List<SignalValue> {
        require(payload.bytes.take(2) == listOf(0x41, pid)) { "Niepoprawny service/PID" }
        val defs = definitions.filter { it.pid == pid }
        require(defs.isNotEmpty()) { "Nieobsługiwany dekoder PID" }
        val data = payload.bytes.drop(2)
        require(data.size >= defs.maxOf { it.minimumBytes }) { "Niekompletny PID %02X".format(pid) }
        return defs.filter { sensorSupported(it, data) }.map { def ->
            val affected = def.id in setOf("0B", "34.lambda", "34.current", "44")
            require(!affected || scaling != null) { "SCALE_UNKNOWN: brak potwierdzonego PID 4F" }
            val value = when (def.id) {
                "0B" -> scaling!!.map(data[0])
                "34.lambda", "44" -> scaling!!.equivalence(word(data))
                "34.current" -> scaling!!.current(word(data, 2))
                else -> def.convert(data)
            }
            SignalValue(def, value)
        }
    }
    fun available(ids: Set<String>, supported: Set<Int>) = ids.filter { id -> byId[id]?.let { it.pid == null || it.pid in supported } == true }.toSet()
    /** A multi-value PID is requested only once even if both signals are selected. */
    fun pollingPids(ids: Set<String>, supported: Set<Int>) = available(ids, supported).mapNotNull { byId.getValue(it).pid }.distinct().sorted()
}
