package pl.obd.core

/** No public raw constructor. The same policy applies to UI and internal traffic. */
class ReadOnlyCommand private constructor(val wire: String, val timeoutMs: Long) {
    companion object {
        private val adapter = setOf("ATZ", "ATI", "ATE0", "ATL0", "ATS1", "ATH1", "ATSP0", "ATAT2", "ATAL", "ATCFC1", "ATCAF1", "ATDPN", "ATDP", "ATRV")
        val supportPages = (0..0xE0 step 0x20).toSet()
        val livePids = SignalCatalog.pids + setOf(0x01, 0x4F)
        fun parse(input: String): ReadOnlyCommand {
            require(input.none { it == '\r' || it == '\n' || it == ';' || it == '>' }) { "Jedno polecenie bez separatorów" }
            val s = input.trim().uppercase(java.util.Locale.ROOT).replace(" ", "")
            val pid = if (s.length == 4 && s.startsWith("01")) s.takeLast(2).toIntOrNull(16) else null
            require(s in adapter || s in setOf("03", "07", "0A", "0900", "0902") || (pid != null && (pid in supportPages || pid in livePids))) {
                "Polecenie poza listą read-only MVP"
            }
            return ReadOnlyCommand(s, if (s == "ATZ" || s.startsWith("09") || s == "0100") 20_000 else 8_000)
        }
        fun pid(pid: Int) = parse("01%02X".format(pid))
    }
}
