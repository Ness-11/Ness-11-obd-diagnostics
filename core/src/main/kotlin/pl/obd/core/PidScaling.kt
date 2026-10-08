package pl.obd.core

/** J1979 PID 4F external-test-equipment scaling, per ECU. Zero selects the default. */
data class PidScaling(val maxEquivalence: Int = 0, val maxVoltage: Int = 0, val maxCurrent: Int = 0, val maxMap: Int = 0) {
    fun map(raw: Int) = if (maxMap == 0) raw.toDouble() else raw * maxMap * 10.0 / 255
    fun equivalence(raw: Int) = if (maxEquivalence == 0) raw / 32768.0 else raw * maxEquivalence.toDouble() / 65535
    fun current(raw: Int) = if (maxCurrent == 0) raw / 256.0 - 128 else (raw - 32768) * maxCurrent.toDouble() / 32768
    companion object {
        val DEFAULT = PidScaling()
        fun decode(p: EcuPayload): PidScaling {
            require(p.bytes.take(2) == listOf(0x41, 0x4F) && p.bytes.size >= 6) { "Niekompletny PID 4F" }
            val b = p.bytes.drop(2); return PidScaling(b[0], b[1], b[2], b[3])
        }
    }
}
