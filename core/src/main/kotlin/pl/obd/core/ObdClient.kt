package pl.obd.core

import java.io.IOException

data class AdapterInfo(val identity: String, val protocol: String, val protocolCode: String, val warnings: List<String>) {
    val isCan: Boolean get() = (protocolCode.removePrefix("A").toIntOrNull(16) ?: -1) in 6..9
}
data class SupportedPids(val byEcu: Map<String, Set<Int>>, val issues: List<String>)
data class DiagnosticResult<T>(val byEcu: Map<String, T>, val issues: List<String>)

data class DtcGroup(val ecu: String, val service: Int, val name: String, val status: String, val codes: List<String>, val issues: List<String> = emptyList())
data class DtcScan(val utc: String, val groups: List<DtcGroup>)

/** One consumer uses this facade for initialization, scans and polling. */
class ObdClient(val session: ElmSession, private val profile: AdapterProfile = Elm327Profile) : DiagnosticChannel {
    private var protocolNumber: Int? = null
    private suspend fun command(s: String) = session.execute(ReadOnlyCommand.parse(s))
    suspend fun initialize(): AdapterInfo {
        val warnings = mutableListOf<String>()
        val identity = command("ATI").also { requireGood(it) }.lines.joinToString(" ")
        for (step in profile.setup) {
            val r = session.execute(step.command)
            if (r.status != ElmStatus.OK) {
                if (step.optional) warnings += "${step.command.wire}: ${r.status}"
                else throw IOException("Inicjalizacja ${step.command.wire}: ${r.status}")
            }
        }
        command("0100").also { requireGood(it) }
        val protocol = command("ATDP").also { requireGood(it) }.lines.joinToString(" ")
        val code = command("ATDPN").also { requireGood(it) }.lines.lastOrNull()?.trim()?.uppercase() ?: ""
        if ((code.removePrefix("A").toIntOrNull(16) ?: -1) !in 1..9) throw IOException("Nieobsługiwany/nieustalony protokół: $code")
        protocolNumber = code.removePrefix("A").toInt(16)
        return AdapterInfo(identity, protocol, code, warnings)
    }
    private fun requireGood(r: ElmResponse) {
        if (r.status in setOf(ElmStatus.ERROR, ElmStatus.UNKNOWN, ElmStatus.NO_DATA)) throw IOException("${r.command}: ${r.status}")
    }
    suspend fun query(service: Int, pid: Int? = null): ObdParser.Result {
        val wire = "%02X".format(service) + (pid?.let { "%02X".format(it) } ?: "")
        val response = command(wire)
        if (response.status in setOf(ElmStatus.NO_DATA, ElmStatus.UNKNOWN, ElmStatus.ERROR)) return ObdParser.Result(emptyList(), listOf("$wire: ${response.status}"))
        return ObdParser.parse(response, service, pid, protocolNumber)
    }
    override suspend fun read(request: ReadRequest): DiagnosticReply {
        val result = query(request.service, request.pid)
        return DiagnosticReply(result.payloads, result.issues)
    }
    suspend fun supported(): SupportedPids {
        val map = mutableMapOf<String, MutableSet<Int>>()
        val issues = mutableListOf<String>()
        for (page in ReadOnlyCommand.supportPages.sorted()) {
            val r = query(1, page)
            issues += r.issues
            r.payloads.forEach { payload ->
                try { map.getOrPut(payload.ecu) { mutableSetOf() } += J1979.supported(payload, page) }
                catch (e: IllegalArgumentException) { issues += "${payload.ecu}: ${e.message}" }
            }
            if (map.values.none { page + 32 in it }) break
        }
        if (map.isEmpty()) issues += "Nie udało się odczytać bitmap PID"
        return SupportedPids(map.mapValues { it.value.toSet() }, issues)
    }
    suspend fun vin(): DiagnosticResult<String> {
        val r = query(9, 2)
        val issues = r.issues.toMutableList()
        val out = mutableMapOf<String, String>()
        r.payloads.groupBy { it.ecu }.forEach { (ecu, packets) ->
            try { out[ecu] = if ((protocolNumber ?: -1) in 1..5) J1979.legacyVin(packets) else J1979.vin(packets.single()) }
            catch (e: IllegalArgumentException) { issues += "$ecu: ${e.message}" }
        }
        return DiagnosticResult(out, issues)
    }
    suspend fun dtcScan(can: Boolean, expectedEcus: Set<String> = emptySet()): DtcScan {
        val out = mutableListOf<DtcGroup>()
        for ((service, name) in listOf(3 to "Zapisane", 7 to "Oczekujące", 10 to "Trwałe")) {
            val r = query(service)
            val received = mutableSetOf<String>()
            r.payloads.groupBy { it.ecu }.forEach { (ecu, packets) ->
                received += ecu
                try { out += DtcGroup(ecu, service, name, "OK", packets.flatMap { J1979.dtcs(it, service, can) }.distinct(), r.issues) }
                catch (e: IllegalArgumentException) { out += DtcGroup(ecu, service, name, "INVALID", emptyList(), r.issues + listOfNotNull(e.message)) }
            }
            val missing = expectedEcus - received
            (if (missing.isEmpty() && received.isEmpty()) setOf("Nieustalone ECU") else missing).forEach { ecu ->
                out += DtcGroup(ecu, service, name, if (r.issues.any { "NO_DATA" in it }) "NO_DATA" else "INVALID", emptyList(), r.issues)
            }
        }
        return DtcScan(java.time.Instant.now().toString(), out)
    }
    suspend fun dtcs(can: Boolean): DiagnosticResult<List<String>> {
        val scan = dtcScan(can)
        return DiagnosticResult(scan.groups.filter { it.status == "OK" }.associate { "${it.ecu} · ${it.name}" to it.codes }, scan.groups.flatMap { it.issues }.distinct())
    }
    suspend fun voltage(): Double {
        val r = command("ATRV")
        requireGood(r)
        return Regex("([0-9]+(?:\\.[0-9]+)?)\\s*V", RegexOption.IGNORE_CASE)
            .find(r.lines.joinToString(" "))?.groupValues?.get(1)?.toDouble() ?: throw IOException("ATRV: niepoprawne napięcie")
    }
}
