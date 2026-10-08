package pl.obd.readonly

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.OutputStream
import java.time.Instant
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import pl.obd.core.*

data class RecordingInfo(val id: String, val started: String, val ended: String?, val rows: Long, val demo: Boolean, val status: String, val signals: Int)
/** Independent from the byte-exact ELM log. CSV records only explicitly selected measurements. */
class RecordingStore(context: Context) {
    private val directory = File(context.filesDir, "recordings").apply { mkdirs() }
    private val lock = Any()
    private var active: RecordingInfo? = null
    private var activeSignals = emptySet<String>()
    private var originMs = 0L
    private var analysis = DiagnosticAnalyzer()
    fun start(signals: Set<String>, demo: Boolean, elapsedMs: Long, alertSettings: AlertSettings = AlertSettings(), ignitionOff: Boolean = false): RecordingInfo = synchronized(lock) {
        check(active == null)
        require(signals.isNotEmpty()) { "Wybierz parametry do zapisu" }
        val now = Instant.now().toString()
        val id = "obd-data-${System.currentTimeMillis()}-${UUID.randomUUID().toString().take(8)}"
        File(directory, "$id.csv").writeText(CsvSample.HEADER)
        val info = RecordingInfo(id, now, null, 0, demo, "active", signals.size)
        analysis = DiagnosticAnalyzer(alertSettings).also { it.configure(alertSettings, now); if (ignitionOff) it.markIgnitionOff(true, now) }
        active = info; activeSignals = signals.toSet(); originMs = elapsedMs
        try { writeMetadata(info) } catch (e: Exception) { active = null; activeSignals = emptySet(); throw e }
        info
    }
    fun append(utc: String, elapsedMs: Long, ecu: String, signalId: String, name: String, value: Double?, unit: String, status: String, latencyMs: Long, freshnessMs: Long = 5000): RecordingInfo? = synchronized(lock) {
        val info = active ?: return null
        if (signalId !in activeSignals) { analysis.context(ecu, signalId, value, status, elapsedMs); return info }
        val row = CsvSample(utc, (elapsedMs - originMs).coerceAtLeast(0), ecu, signalId, name, value, unit, status, latencyMs)
        // Each append closes the stream, so a process death does not lose an in-memory batch.
        File(directory, "${info.id}.csv").appendText(row.line())
        analysis.accept(ecu, signalId, name, unit, value, status, elapsedMs, utc, latencyMs, freshnessMs)
        info.copy(rows = info.rows + 1).also { active = it }
    }
    fun stop(reason: String = "stopped"): RecordingInfo? = synchronized(lock) {
        val info = active ?: return null
        val done = info.copy(ended = Instant.now().toString(), status = reason)
        try { analysis.finish(requireNotNull(done.ended), "Koniec zapisu: $reason"); File(directory, "${info.id}.report.md").writeText(analysis.snapshot().markdown(reportTitle(done)) + dtcReport(info.id)); writeMetadata(done) } finally { active = null; activeSignals = emptySet() }
        done
    }
    fun list(): List<RecordingInfo> = synchronized(lock) {
        directory.listFiles { f -> f.extension == "json" }.orEmpty().mapNotNull { f ->
            try {
                val o = JSONObject(f.readText())
                val id = o.getString("id")
                if (!File(directory, "$id.csv").isFile) return@mapNotNull null
                active?.takeIf { it.id == id } ?: RecordingInfo(id, o.getString("started"), o.optString("ended").takeIf { it.isNotEmpty() }, if (o.optString("status") == "active") File(directory, "$id.csv").useLines { (it.count() - 1).coerceAtLeast(0).toLong() } else o.optLong("rows"), o.optBoolean("demo"), if (o.optString("status") == "active") "interrupted" else o.optString("status"), o.getJSONArray("selected").length())
            } catch (_: Exception) { null }
        }.sortedByDescending { it.started }
    }
    fun export(id: String, output: OutputStream) {
        val snapshot = synchronized(lock) {
            require(Regex("obd-data-[0-9]+-[a-f0-9]{8}").matches(id)) { "Niepoprawny identyfikator zapisu" }
            val file = File(directory, "$id.csv")
            require(file.isFile) { "Nie znaleziono zapisu" }
            File.createTempFile("export", ".csv", directory).also { file.copyTo(it, overwrite = true) }
        }
        try { snapshot.inputStream().use { it.copyTo(output) } } finally { snapshot.delete() }
    }
    fun annotate(utc: String, text: String) = synchronized(lock) {
        active?.let { info -> analysis.addAnnotation(utc, text); File(directory, "${info.id}.notes.jsonl").appendText(JSONObject().put("utc", utc).put("text", text).toString() + "\n") }
    }
    fun markIgnitionOff(off: Boolean, utc: String) = synchronized(lock) { if (active != null) analysis.markIgnitionOff(off, utc) }
    fun report(id: String): String = synchronized(lock) {
        require(Regex("obd-data-[0-9]+-[a-f0-9]{8}").matches(id)) { "Niepoprawny identyfikator zapisu" }
        val info = list().firstOrNull { it.id == id } ?: error("Nie znaleziono zapisu")
        if (active?.id == id) return@synchronized analysis.snapshot().markdown(reportTitle(info)) + dtcReport(id)
        val saved = File(directory, "$id.report.md")
        if (saved.isFile) return@synchronized saved.readText()
        // Legacy or interrupted CSV: statistics can be reconstructed; original alert settings cannot.
        val rebuilt = DiagnosticAnalyzer(AlertSettings(enabled = false))
        File(directory, "$id.csv").useLines { lines ->
            val iterator = lines.iterator()
            require(iterator.hasNext() && iterator.next().trimStart('\uFEFF') == CsvSample.HEADER.trimEnd()) { "Nieznany format CSV" }
            iterator.forEach { line -> if (line.isNotBlank()) {
                val r = CsvSample.parseLine(line)
                rebuilt.accept(r.ecu, r.signalId, r.name, r.unit, r.value, r.status, r.elapsedMs, r.utc, r.latencyMs)
            } }
        }
        rebuilt.snapshot().markdown(reportTitle(info)) + dtcReport(id) +
            File(directory, "$id.notes.jsonl").takeIf { it.isFile }?.let { "\nZachowane adnotacje sesji (JSONL):\n" + it.readText() }.orEmpty() + "\nRaport odtworzony z CSV: historia alertów i adnotacje niedostępne.\n"
    }
    fun comparison(): String = synchronized(lock) {
        val completed = list().filter { it.status != "active" }
        val mode = completed.firstOrNull()?.demo ?: false
        val chosen = completed.filter { it.demo == mode }.take(3)
        require(chosen.size >= 2) { "Potrzebne są co najmniej dwie sesje tego samego typu (DEMO lub OBD)" }
        val summaries = chosen.map { info ->
            val analyzer = DiagnosticAnalyzer(AlertSettings(enabled = false))
            File(directory, "${info.id}.csv").useLines { lines -> lines.drop(1).filter { it.isNotBlank() }.forEach { line ->
                val r = CsvSample.parseLine(line)
                analyzer.accept(r.ecu, r.signalId, r.name, r.unit, r.value, r.status, r.elapsedMs, r.utc, r.latencyMs)
            } }
            analyzer.snapshot().measurements.associateBy { "${it.ecu}:${it.signalId}" }
        }
        buildString {
            append("# Porównanie ostatnich sesji ${if (mode) "DEMO" else "OBD"}\n\nRóżne trasy, obciążenia i temperatury mogą wyjaśniać różnice. To nie jest automatyczna diagnoza. Tabela obejmuje sumę dostępnych pomiarów, brak parametru oznaczony —.\n\n")
            chosen.forEachIndexed { index, info -> append("${index + 1}. ${info.started} · ${info.rows} wierszy · ${info.status}\n") }
            append("\n| ECU / parametr / jednostka | " + chosen.indices.joinToString(" | ") { "Sesja ${it + 1}: min / średnia / max; OK / braki" } + " |\n|---|" + chosen.joinToString("|") { "---" } + "|\n")
            summaries.flatMap { it.keys }.distinct().sorted().forEach { key ->
                val def = summaries.firstNotNullOf { it[key] }
                append("| ${def.ecu} / ${def.name} / ${def.unit} | ")
                append(summaries.joinToString(" | ") { map -> map[key]?.let { m ->
                    fun n(v: Double?) = v?.let { java.lang.String.format(java.util.Locale.ROOT, "%.2f", it) } ?: "—"
                    "${n(m.minimum)} / ${n(m.average)} / ${n(m.maximum)}; ${m.ok} / ${m.missing}"
                } ?: "—" }); append(" |\n")
            }
        }
    }
    fun recordDtcs(scan: DtcScan) = synchronized(lock) {
        val info = active ?: return@synchronized
        val groups = JSONArray()
        scan.groups.forEach { g -> groups.put(JSONObject().put("ecu", g.ecu).put("service", g.service).put("name", g.name).put("status", g.status).put("codes", JSONArray(g.codes)).put("issues", JSONArray(g.issues))) }
        File(directory, "${info.id}.dtc.jsonl").appendText(JSONObject().put("utc", scan.utc).put("groups", groups).toString() + "\n")
    }
    private fun dtcReport(id: String): String = buildString {
        append("\n## Odczyty DTC (03 / 07 / 0A)\n\n")
        val file = File(directory, "$id.dtc.jsonl")
        if (!file.isFile) { append("Brak zapisanego skanu DTC. CSV i licznik MIL nie potwierdzają braku błędów.\n"); return@buildString }
        append("Każdy skan ma własny czas UTC; odczyty kolejno. NO_DATA / INVALID nie potwierdzają braku DTC. Historia obejmuje wszystkie skany tej sesji.\n\n")
        append("| UTC | ECU | Rodzaj | Status | Kody / uwagi |\n|---|---|---|---|---|\n")
        file.useLines { lines -> lines.forEach { line ->
            try {
                val o = JSONObject(line); val groups = o.getJSONArray("groups")
                for (i in 0 until groups.length()) {
                    val g = groups.getJSONObject(i)
                    val codes = g.getJSONArray("codes"); val issues = g.getJSONArray("issues")
                    val detail = if (g.getString("status") == "OK" && codes.length() == 0) "Brak DTC w poprawnej odpowiedzi" else (0 until codes.length()).joinToString { codes.getString(it) }
                    append("| ${o.getString("utc")} | ${g.getString("ecu")} | ${g.getString("name")} | ${g.getString("status")} | $detail ${(0 until issues.length()).joinToString { issues.getString(it).replace("|", "/") }} |\n")
                }
            } catch (_: Exception) { append("| — | — | — | INVALID | Niekompletny wpis skanu w pliku sesji |\n") }
        } }
    }
    fun exportBundle(id: String, output: OutputStream) {
        // Freeze a coherent snapshot of active files under the same append lock.
        val entries = synchronized(lock) {
            val text = report(id)
            active?.takeIf { it.id == id }?.let { writeMetadata(it) }
            val files = listOf("$id.csv", "$id.json", "$id.dtc.jsonl", "$id.notes.jsonl")
            files.mapNotNull { name -> File(directory, name).takeIf { it.isFile }?.let { name to it.readBytes() } } +
                listOf("$id.report.md" to text.toByteArray(Charsets.UTF_8))
        }
        ZipOutputStream(output).use { zip -> entries.forEach { (name, bytes) -> zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() } }
    }
    fun exportReport(id: String, output: OutputStream) { output.write(report(id).toByteArray(Charsets.UTF_8)) }
    private fun reportTitle(info: RecordingInfo) = "Sesja ${info.started} · ${if (info.demo) "DEMO" else "OBD"} · ${info.status} · ${info.rows} próbek"
    private fun writeMetadata(info: RecordingInfo) {
        val o = JSONObject().put("id", info.id).put("started", info.started).put("ended", info.ended ?: "").put("rows", info.rows).put("demo", info.demo).put("status", info.status).put("selected", JSONArray(activeSignals.sorted()))
        File(directory, "${info.id}.json").writeText(o.toString())
    }
}
