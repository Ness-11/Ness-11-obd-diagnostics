package pl.obd.core

import java.util.Locale
import kotlin.math.abs

data class AlertSettings(val enabled: Boolean = true, val coolantHigh: Double = 110.0, val oilHigh: Double = 130.0, val voltageLow: Double = 11.5, val voltageHigh: Double = 15.5, val trimAbs: Double = 20.0, val holdMs: Long = 10_000) {
    init { require(coolantHigh in 80.0..140.0 && oilHigh in 90.0..170.0 && voltageLow in 9.0..13.0 && voltageHigh in 14.0..17.0 && voltageLow < voltageHigh && trimAbs in 5.0..50.0 && holdMs in 1000..120_000) }
}
data class MeasurementSummary(val ecu: String, val signalId: String, val name: String, val unit: String, val ok: Long, val missing: Long, val minimum: Double?, val maximum: Double?, val average: Double?, val meanIntervalMs: Double?, val meanLatencyMs: Double, val lastStatus: String, val unsupported: Long = 0)
data class DiagnosticAlert(val rule: String, val ecu: String, val signalId: String, val message: String, val startedUtc: String, val endedUtc: String? = null, val endReason: String? = null)
data class AnalysisSnapshot(val measurements: List<MeasurementSummary> = emptyList(), val alerts: List<DiagnosticAlert> = emptyList(), val annotations: List<String> = emptyList()) {
    fun markdown(title: String): String = buildString {
        append("# ").append(title).append("\n\nObserwacje read-only. Progi użytkownika są wskazówkami, a nie specyfikacją producenta ani diagnozą uszkodzenia. Średnie są arytmetyczne z próbek. Pomiary wykonywane kolejno. RPM z Live może służyć jako kontekst alertu nawet gdy nie wybrano go do CSV. Historia raportu obejmuje ostatnie 200 alertów i 200 adnotacji.\n\n")
        append("| ECU | Parametr | Jednostka | OK | Braki | Nieobsługiwane | Min | Średnia | Max | Śr. odstęp ms | Śr. RTT ms | Ostatni status |\n|---|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---|\n")
        measurements.forEach { m -> append("| ${m.ecu} | ${m.name} (${m.signalId}) | ${m.unit} | ${m.ok} | ${m.missing} | ${m.unsupported} | ${number(m.minimum)} | ${number(m.average)} | ${number(m.maximum)} | ${number(m.meanIntervalMs)} | ${number(m.meanLatencyMs)} | ${m.lastStatus} |\n") }
        append("\n## Alerty\n\n")
        if (alerts.isEmpty()) append("Brak zarejestrowanych przekroczeń. To nie potwierdza sprawności auta; reguły wymagają odczytu odpowiednich PID-ów.\n")
        alerts.forEach { append("- ${it.startedUtc} · ${it.ecu} · ${it.message} · koniec: ${it.endedUtc ?: "aktywne przy eksporcie"} ${it.endReason.orEmpty()}\n") }
        append("\n## Adnotacje\n\n"); annotations.forEach { append("- $it\n") }
        append("\nLambda/EQR: zachowane przeliczenie PID SAE; bez reguł diagnozy mieszanki i bez automatycznego odwracania. DTC i monitory OBD wymagają osobnego odczytu. Brak odpowiedzi nie oznacza braku DTC.\n")
    }
    companion object { private fun number(v: Double?) = v?.let { String.format(Locale.ROOT, "%.3f", it) } ?: "—" }
}

/** Bounded, per-ECU statistics. Alerts need consecutive fresh samples, context and hysteresis. */
class DiagnosticAnalyzer(settings: AlertSettings = AlertSettings()) {
    private data class Stats(val ecu: String, val id: String, val name: String, val unit: String, var ok: Long = 0, var missing: Long = 0, var unsupported: Long = 0, var min: Double? = null, var max: Double? = null, var mean: Double = 0.0, var lastOk: Long? = null, var intervalTotal: Long = 0, var intervals: Long = 0, var latencyMean: Double = 0.0, var status: String = "")
    private data class Fresh(val value: Double, val time: Long, val gap: Long = 5000)
    private data class Pending(val since: Long, var last: Long, val utc: String, var gap: Long)
    private val stats = linkedMapOf<String, Stats>()
    private val fresh = mutableMapOf<String, Fresh>()
    private val pending = mutableMapOf<String, Pending>()
    private val events = mutableListOf<DiagnosticAlert>()
    private val notes = mutableListOf<String>()
    private var config = settings
    private var expectedSilence = false
    @Synchronized fun configure(settings: AlertSettings, utc: String) {
        endAll(utc, "Zmiana ustawień"); config = settings
        note(utc, "Progi: płyn ${settings.coolantHigh} °C; olej ${settings.oilHigh} °C; napięcie ${settings.voltageLow}–${settings.voltageHigh} V; korekty ±${settings.trimAbs} %; podtrzymanie ${settings.holdMs / 1000} s; włączone ${settings.enabled}")
    }
    @Synchronized fun markIgnitionOff(off: Boolean, utc: String) {
        expectedSilence = off; endAll(utc, "Adnotacja zapłonu")
        fresh.clear(); note(utc, if (off) "Użytkownik: zapłon wyłączony; alerty parametrów wstrzymane" else "Użytkownik: zapłon włączony; alerty parametrów wznowione")
    }
    private fun note(utc: String, text: String) { notes += "$utc · $text"; if (notes.size > 200) notes.removeAt(0) }
    /** Context from Live can gate an alert without adding an unselected measurement to CSV/statistics. */
    @Synchronized fun context(ecu: String, id: String, value: Double?, status: String, ms: Long) {
        if (id != "0C") return
        if (value != null && value.isFinite() && status == "OK") fresh["$ecu:$id"] = Fresh(value, ms)
        else fresh.remove("$ecu:$id")
    }
    @Synchronized fun addAnnotation(utc: String, text: String) { note(utc, text) }
    @Synchronized fun finish(utc: String, reason: String) { endAll(utc, reason); fresh.clear() }
    private fun endAll(utc: String, reason: String) {
        pending.clear(); events.indices.forEach { i -> if (events[i].endedUtc == null) events[i] = events[i].copy(endedUtc = utc, endReason = reason) }
    }
    @Synchronized fun accept(ecu: String, id: String, name: String, unit: String, value: Double?, status: String, ms: Long, utc: String, latency: Long, freshnessMs: Long = 5000) {
        val gap = freshnessMs.coerceAtLeast(1000)
        pending.filterValues { ms - it.last > it.gap }.keys.toList().forEach { expire(it, utc, "Brak świeżych danych — ocena przerwana") }
        events.filter { it.endedUtc == null }.forEach { e ->
            val last = fresh["${e.ecu}:${e.signalId}"]
            if (last == null || ms - last.time > last.gap) expire("${e.ecu}:${e.rule}", utc, "Brak świeżych danych — ocena przerwana")
        }
        val key = "$ecu:$id"; val s = stats.getOrPut(key) { Stats(ecu, id, name, unit) }
        val total = s.ok + s.missing + s.unsupported + 1; s.latencyMean += (latency - s.latencyMean) / total; s.status = status
        val valid = value != null && value.isFinite() && status == "OK"
        if (id == "8B.active" && valid && fresh[key]?.value != value)
            note(utc, "$ecu: PID 8B — regeneracja DPF ${if (value == 1.0) "w toku" else "nie jest w toku"}; stan odczytany, bez uruchamiania regeneracji")
        if (!valid) { if (status == "UNSUPPORTED") s.unsupported++ else s.missing++; fresh.remove(key) }
        else {
            val v = requireNotNull(value); s.ok++; s.mean += (v - s.mean) / s.ok
            s.min = minOf(s.min ?: v, v); s.max = maxOf(s.max ?: v, v)
            s.lastOk?.let { if (ms > it) { s.intervalTotal += ms - it; s.intervals++ } }; s.lastOk = ms
            fresh[key] = Fresh(v, ms, gap)
        }
        if (!config.enabled || expectedSilence || !valid) { if (!valid) rules(id).forEach { expire("$ecu:$it", utc, "Brak danych — ocena przerwana") }; return }
        val v = requireNotNull(value)
        val running = fresh["$ecu:0C"]?.let { ms - it.time in 0..it.gap && it.value > 500 } == true
        when (id) {
            "05" -> rule("coolant", ecu, id, "Temperatura płynu ≥ ${config.coolantHigh} °C", v >= config.coolantHigh, v < config.coolantHigh - 3, ms, utc, gap)
            "5C" -> rule("oil", ecu, id, "Temperatura oleju ≥ ${config.oilHigh} °C", v >= config.oilHigh, v < config.oilHigh - 3, ms, utc, gap)
            "42" -> {
                if (!running) expire("$ecu:low_voltage", utc, "Brak świeżego potwierdzenia pracy silnika")
                else rule("low_voltage", ecu, id, "Niskie napięcie ECU ≤ ${config.voltageLow} V przy pracującym silniku", v <= config.voltageLow, v > config.voltageLow + .3, ms, utc, gap)
                rule("high_voltage", ecu, id, "Wysokie napięcie ECU ≥ ${config.voltageHigh} V", v >= config.voltageHigh, v < config.voltageHigh - .3, ms, utc, gap)
            }
            "06", "07", "08", "09" -> {
                if (!running) expire("$ecu:trim_$id", utc, "Brak świeżego potwierdzenia pracy silnika")
                else rule("trim_$id", ecu, id, "Korekta $id poza ±${config.trimAbs} %; sprawdź warunki regulacji paliwa", abs(v) >= config.trimAbs, abs(v) < config.trimAbs - 3, ms, utc, gap)
            }
        }
    }
    private fun rules(id: String) = when (id) { "05" -> listOf("coolant"); "5C" -> listOf("oil"); "42" -> listOf("low_voltage", "high_voltage"); "06", "07", "08", "09" -> listOf("trim_$id"); else -> emptyList() }
    private fun rule(rule: String, ecu: String, id: String, message: String, exceeded: Boolean, recovered: Boolean, ms: Long, utc: String, gap: Long) {
        val key = "$ecu:$rule"; val active = events.any { it.ecu == ecu && it.rule == rule && it.endedUtc == null }
        if (active) { if (recovered) expire(key, utc, "Powrót poniżej progu z histerezą"); return }
        if (!exceeded) { pending.remove(key); return }
        val p = pending.getOrPut(key) { Pending(ms, ms, utc, gap) }; p.last = ms; p.gap = gap
        if (ms - p.since >= config.holdMs) {
            events += DiagnosticAlert(rule, ecu, id, message, p.utc); pending.remove(key)
            if (events.size > 200) { val index = events.indexOfFirst { it.endedUtc != null }; if (index >= 0) events.removeAt(index) }
        }
    }
    private fun expire(key: String, utc: String, reason: String) {
        pending.remove(key); events.indices.forEach { i -> if ("${events[i].ecu}:${events[i].rule}" == key && events[i].endedUtc == null) events[i] = events[i].copy(endedUtc = utc, endReason = reason) }
    }
    @Synchronized fun snapshot(): AnalysisSnapshot = AnalysisSnapshot(stats.values.map { s -> MeasurementSummary(s.ecu, s.id, s.name, s.unit, s.ok, s.missing, s.min, s.max, s.mean.takeIf { s.ok > 0 }, (s.intervalTotal.toDouble() / s.intervals).takeIf { s.intervals > 0 }, s.latencyMean, s.status, s.unsupported) }, events.toList(), notes.toList())
}
