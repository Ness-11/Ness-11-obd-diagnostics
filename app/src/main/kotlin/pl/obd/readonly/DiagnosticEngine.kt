package pl.obd.readonly

import android.app.Application
import android.bluetooth.BluetoothManager
import android.net.Uri
import android.os.SystemClock
import java.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import pl.obd.bluetooth.*
import pl.obd.core.*

data class Sample(val signalId: String, val name: String, val value: Double, val unit: String, val time: Long)
data class LinkStats(val requests: Long = 0, val issues: Long = 0, val latencies: List<Long> = emptyList())
data class UiState(
    val devices: List<PairedAdapter> = emptyList(), val selected: String? = null,
    val status: String = "Rozłączono", val connected: Boolean = false, val busy: Boolean = false,
    val demo: Boolean = false, val info: AdapterInfo? = null,
    val supported: Map<String, Set<Int>> = emptyMap(), val vin: Map<String, String> = emptyMap(),
    val dtcs: Map<String, List<String>> = emptyMap(), val dtcScan: DtcScan? = null, val autoDtcs: Boolean = true,
    val scaling: Map<String, PidScaling> = emptyMap(), val unavailable: Set<String> = emptySet(), val freshness: Map<String, Long> = emptyMap(), val readiness: Map<String, Readiness> = emptyMap(),
    val samples: Map<String, Sample> = emptyMap(), val history: Map<String, List<ChartPoint>> = emptyMap(),
    val liveIds: Set<String> = SignalCatalog.defaultIds, val recordIds: Set<String> = SignalCatalog.defaultIds,
    val live: Boolean = false, val recording: Boolean = false, val activeRecording: RecordingInfo? = null,
    val recordings: List<RecordingInfo> = emptyList(), val cyclePauseMs: Long = 500,
    val chartWindowMs: Long = 60_000, val stats: LinkStats = LinkStats(),
    val analysis: AnalysisSnapshot = AnalysisSnapshot(), val alertSettings: AlertSettings = AlertSettings(),
    val ignitionOff: Boolean = false, val savedReport: String? = null,
    val issues: List<String> = emptyList(), val message: String? = null
)

/** Application-owned session; the foreground service keeps active recordings alive without an Activity. */
class DiagnosticEngine(private val application: Application) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val settings = application.getSharedPreferences("diagnostics", 0)
    private val mutable = MutableStateFlow(UiState(
        autoDtcs = settings.getBoolean("autoDtcs", true),
        liveIds = settings.getStringSet("liveIds", SignalCatalog.defaultIds).orEmpty().intersect(SignalCatalog.byId.keys),
        recordIds = settings.getStringSet("recordIds", SignalCatalog.defaultIds).orEmpty().intersect(SignalCatalog.byId.keys),
        cyclePauseMs = settings.getLong("cyclePauseMs", 500).coerceIn(100, 5000),
        chartWindowMs = settings.getLong("chartWindowMs", 60_000).coerceIn(30_000, 180_000),
        alertSettings = AlertSettings(enabled = settings.getBoolean("alertsEnabled", true),
            coolantHigh = settings.getFloat("coolantHigh", 110f).toDouble().coerceIn(80.0, 140.0),
            oilHigh = settings.getFloat("oilHigh", 130f).toDouble().coerceIn(90.0, 170.0),
            voltageLow = settings.getFloat("voltageLow", 11.5f).toDouble().coerceIn(9.0, 13.0),
            voltageHigh = settings.getFloat("voltageHigh", 15.5f).toDouble().coerceIn(14.0, 17.0),
            trimAbs = settings.getFloat("trimAbs", 20f).toDouble().coerceIn(5.0, 50.0),
            holdMs = settings.getLong("alertHoldMs", 10_000).coerceIn(1000, 120_000))
    ))
    val state = mutable.asStateFlow()
    val logs = LogStore(application)
    private val recorder = RecordingStore(application)
    private var analyzer = DiagnosticAnalyzer(mutable.value.alertSettings)
    private val adapter get() = application.getSystemService(BluetoothManager::class.java)?.adapter
    private var session: ElmSession? = null
    @Volatile private var client: ObdClient? = null
    private var connection: Job? = null
    private var monitor: Job? = null
    private var poll: Job? = null
    @Volatile private var generation = 0L
    @Volatile private var uiVisible = false
    private var operationJob: Job? = null
    private var healthReadMs = 0L
    private var dtcReadMs = 0L
    private val scheduler = PollingScheduler()
    private val operations = Mutex()
    init { refreshRecordings() }

    fun showMessage(message: String?) { mutable.update { it.copy(message = message) } }
    fun clearMessage() = showMessage(null)
    private fun report(issues: List<String>) {
        if (issues.isNotEmpty()) mutable.update { it.copy(issues = (it.issues + issues).takeLast(100)) }
    }
    fun setAlertSettings(config: AlertSettings) {
        if (mutable.value.recording) { showMessage("Zatrzymaj zapis przed zmianą progów"); return }
        analyzer.configure(config, Instant.now().toString())
        mutable.update { it.copy(alertSettings = config, analysis = analyzer.snapshot()) }
        settings.edit().putBoolean("alertsEnabled", config.enabled).putFloat("coolantHigh", config.coolantHigh.toFloat())
            .putFloat("oilHigh", config.oilHigh.toFloat()).putFloat("voltageLow", config.voltageLow.toFloat())
            .putFloat("voltageHigh", config.voltageHigh.toFloat()).putFloat("trimAbs", config.trimAbs.toFloat())
            .putLong("alertHoldMs", config.holdMs).apply()
    }
    fun markIgnitionOff(off: Boolean) {
        val utc = Instant.now().toString()
        analyzer.markIgnitionOff(off, utc); recorder.markIgnitionOff(off, utc)
        if (!off) { scheduler.reset(); dtcReadMs = 0L }
        mutable.update { it.copy(ignitionOff = off, analysis = analyzer.snapshot()) }
    }
    fun viewReport(id: String) { scope.launch {
        try { val text = withContext(Dispatchers.IO) { recorder.report(id) }; mutable.update { it.copy(savedReport = text) } }
        catch (e: Exception) { showMessage("Błąd raportu: ${e.message}") }
    } }
    fun compareRecordings() { scope.launch {
        try { val text = withContext(Dispatchers.IO) { recorder.comparison() }; mutable.update { it.copy(savedReport = text) } }
        catch (e: Exception) { showMessage("Błąd porównania: ${e.message}") }
    } }
    fun closeReport() { mutable.update { it.copy(savedReport = null) } }
    fun exportReport(id: String, uri: Uri) = export(uri) { out -> recorder.exportReport(id, out) }
    fun refreshDevices() {
        try { mutable.update { it.copy(devices = pairedAdapters(adapter), message = null) } }
        catch (e: Exception) { showMessage(e.message) }
    }
    fun select(address: String) { mutable.update { it.copy(selected = address) } }
    fun setUiVisible(visible: Boolean) {
        uiVisible = visible
        if (!visible && !mutable.value.recording) {
            analyzer.finish(Instant.now().toString(), "Live wstrzymany w tle — ocena przerwana")
            mutable.update { it.copy(live = false, analysis = analyzer.snapshot()) }
        }
    }
    fun toggleSignal(id: String, record: Boolean) {
        if (id !in SignalCatalog.byId || (record && mutable.value.recording)) return
        mutable.update { s ->
            val selected = if (record) s.recordIds else s.liveIds
            val next = if (id in selected) selected - id else selected + id
            if (record) s.copy(recordIds = next) else s.copy(liveIds = next)
        }
        settings.edit().putStringSet(if (record) "recordIds" else "liveIds", (if (record) mutable.value.recordIds else mutable.value.liveIds).toSet()).apply()
    }
    fun preset(ids: Set<String>) {
        if (mutable.value.recording) return
        val next = ids.intersect(SignalCatalog.byId.keys)
        mutable.update { it.copy(liveIds = next, recordIds = next) }
        settings.edit().putStringSet("liveIds", next).putStringSet("recordIds", next).apply()
    }
    fun setCyclePause(ms: Long) {
        val value = ms.coerceIn(100, 5000)
        mutable.update { it.copy(cyclePauseMs = value) }; settings.edit().putLong("cyclePauseMs", value).apply()
    }
    fun setAutoDtcs(enabled: Boolean) {
        if (mutable.value.recording) return
        mutable.update { it.copy(autoDtcs = enabled) }; settings.edit().putBoolean("autoDtcs", enabled).apply()
    }
    fun exportBundle(id: String, uri: Uri) = export(uri) { recorder.exportBundle(id, it) }
    fun setChartWindow(ms: Long) {
        val value = ms.coerceIn(30_000, 180_000)
        mutable.update { it.copy(chartWindowMs = value) }; settings.edit().putLong("chartWindowMs", value).apply()
    }
    fun connect(demo: Boolean = false) {
        if (mutable.value.busy || mutable.value.connected) return
        val before = mutable.value
        if (!demo && before.selected == null) { showMessage("Wybierz sparowany adapter"); return }
        val token = ++generation
        healthReadMs = 0L; dtcReadMs = 0L; scheduler.reset()
        analyzer = DiagnosticAnalyzer(before.alertSettings).also { it.configure(before.alertSettings, Instant.now().toString()) }
        mutable.value = before.copy(status = "Łączenie…", busy = true, demo = demo, info = null, supported = emptyMap(), vin = emptyMap(), dtcs = emptyMap(), dtcScan = null, scaling = emptyMap(), unavailable = emptySet(), freshness = emptyMap(), readiness = emptyMap(), samples = emptyMap(), history = emptyMap(), stats = LinkStats(), analysis = AnalysisSnapshot(), ignitionOff = false, issues = emptyList(), message = null, live = false)
        connection = scope.launch {
            var opened: ElmSession? = null
            var transport: ByteTransport? = null
            try {
                transport = if (demo) DemoTransport() else BluetoothTransport(requireNotNull(adapter), requireNotNull(before.selected))
                val log = withContext(Dispatchers.IO) { logs.newSession() }
                val s = ElmSession(transport, log); opened = s; session = s
                mutable.update { it.copy(status = "Inicjalizacja ELM…") }
                val obd = ObdClient(s)
                val result = withContext(Dispatchers.IO) { s.start()
                    val info = obd.initialize(); val supported = obd.supported()
                    Triple(info, supported, loadScaling(obd, supported.byEcu)) }
                if (generation != token) { s.close(); return@launch }
                client = obd
                mutable.update { it.copy(status = "Połączono", connected = true, busy = false, info = result.first, supported = result.second.byEcu, scaling = result.third, issues = it.issues + result.first.warnings + result.second.issues) }
                monitor = scope.launch {
                    val reason = s.failure.filterNotNull().first()
                    if (generation == token) {
                        client = null
                        analyzer.finish(Instant.now().toString(), "Utrata połączenia")
                        mutable.update { it.copy(status = reason, connected = false, busy = false, live = false, analysis = analyzer.snapshot()) }
                        endRecording("connection_lost")
                    }
                }
            } catch (e: CancellationException) { opened?.close(); transport?.close(); throw e }
            catch (e: Exception) {
                opened?.close(); transport?.close()
                if (generation == token) { client = null; mutable.update { it.copy(status = "Błąd połączenia", busy = false, connected = false, message = e.message) } }
            }
        }
    }
    fun disconnect() {
        ++generation
        mutable.update { it.copy(status = "Rozłączono", connected = false, busy = false, live = false) }
        analyzer.finish(Instant.now().toString(), "Rozłączenie"); mutable.update { it.copy(analysis = analyzer.snapshot()) }
        monitor?.cancel(); monitor = null
        session?.close(); session = null; client = null
        poll?.cancel(); poll = null; connection?.cancel(); connection = null
        operationJob?.cancel(); operationJob = null
        stopRecording("disconnected")
    }
    fun pauseLive() {
        analyzer.finish(Instant.now().toString(), "Zatrzymanie Live — ocena przerwana")
        mutable.update { it.copy(live = false, analysis = analyzer.snapshot()) }
        if (mutable.value.recording) stopRecording()
    }
    private fun supportedUnion() = mutable.value.supported.values.flatten().toSet()
    private fun requestedIds(s: UiState): Set<String> = s.liveIds + (if (s.recording) s.recordIds else emptySet())
    fun startLive() {
        val s = mutable.value
        if (!s.connected || s.busy) return
        if (SignalCatalog.available(requestedIds(s), supportedUnion()).isEmpty()) { showMessage("Wybierz obsługiwane parametry w zakładce Parametry"); return }
        mutable.update { it.copy(live = true) }
        if (poll?.isActive == true) return
        val token = generation
        val obd = client ?: return
        poll = scope.launch {
            try {
                while (mutable.value.live && mutable.value.connected && generation == token) {
                    operations.withLock {
                        withContext(Dispatchers.IO) {
                            val snapshot = mutable.value
                            val ids = SignalCatalog.available(requestedIds(snapshot), snapshot.supported.values.flatten().toSet())
                            val selected = SignalCatalog.pollingPids(ids, snapshot.supported.values.flatten().toSet()).filter { candidate ->
                                val fields = SignalCatalog.definitions.filter { it.pid == candidate && it.id in ids }
                                snapshot.supported.filterValues { candidate in it }.keys.any { ecu -> fields.any { "$ecu:${it.id}" !in snapshot.unavailable } }
                            }.toSet() + if ("ATRV" in ids) setOf(-1) else emptySet()
                            val pid = scheduler.next(selected, SystemClock.elapsedRealtime(), snapshot.cyclePauseMs)
                            if (pid != null && pid != -1 && !snapshot.busy) {
                                if (!mutable.value.live || mutable.value.busy || !mutable.value.connected) return@withContext
                                val start = SystemClock.elapsedRealtime()
                                val result = obd.query(1, pid)
                                currentCoroutineContext().ensureActive()
                                if (generation != token) return@withContext
                                val end = SystemClock.elapsedRealtime()
                                updateStats(end - start, result.issues.isNotEmpty())
                                report(result.issues)
                                val seen = mutableSetOf<String>()
                                result.payloads.forEach { payload ->
                                    try {
                                        SignalCatalog.decode(payload, pid, if (0x4F in snapshot.supported[payload.ecu].orEmpty()) snapshot.scaling[payload.ecu] else PidScaling.DEFAULT).filter { it.definition.id in ids }.forEach { value ->
                                            val def = value.definition
                                            val key = "${payload.ecu}:${def.id}"
                                            seen += key
                                            sample(key, payload.ecu, def, value.value, end, end - start, "OK")
                                        }
                                        SignalCatalog.definitions.filter { it.pid == pid && it.id in ids && it.supportBit != null }.forEach { def ->
                                            if (!SignalCatalog.sensorSupported(def, payload.bytes.drop(2))) {
                                                val key = "${payload.ecu}:${def.id}"; seen += key
                                                sample(key, payload.ecu, def, null, end, end - start, "UNSUPPORTED")
                                            }
                                        }
                                    } catch (e: IllegalArgumentException) { report(listOf("${payload.ecu}: ${e.message}")) }
                                }
                                val expectedEcus = snapshot.supported.filterValues { pid in it }.keys
                                val defs = SignalCatalog.definitions.filter { it.pid == pid && it.id in ids }
                                expectedEcus.forEach { ecu -> defs.forEach { def ->
                                    val key = "$ecu:${def.id}"
                                    if (key !in seen) sample(key, ecu, def, null, end, end - start, if (pid in setOf(0x0B, 0x34, 0x44) && 0x4F in snapshot.supported[ecu].orEmpty() && ecu !in snapshot.scaling) "SCALE_UNKNOWN" else if (result.issues.any { "NO_DATA" in it }) "NO_DATA" else "INVALID")
                                } }
                                scheduler.complete(pid, end, seen.any { it !in mutable.value.unavailable })
                                delay(80)
                            }
                            if (1 in snapshot.supported.values.flatten() && mutable.value.live && !mutable.value.busy && SystemClock.elapsedRealtime() - healthReadMs >= 30_000) {
                                val result = obd.query(1, 1)
                                currentCoroutineContext().ensureActive()
                                if (generation != token) return@withContext
                                healthReadMs = SystemClock.elapsedRealtime()
                                updateReadiness(result)
                                delay(80)
                            }
                            if (pid == -1 && mutable.value.live && mutable.value.connected && !mutable.value.busy) {
                                val start = SystemClock.elapsedRealtime()
                                try {
                                    val voltage = obd.voltage(); val end = SystemClock.elapsedRealtime()
                                    updateStats(end - start, false)
                                    sample("Adapter:ATRV", "Adapter", SignalCatalog.byId.getValue("ATRV"), voltage, end, end - start, "OK")
                                } catch (e: CancellationException) { throw e }
                                catch (e: Exception) {
                                    val end = SystemClock.elapsedRealtime(); updateStats(end - start, true)
                                    sample("Adapter:ATRV", "Adapter", SignalCatalog.byId.getValue("ATRV"), null, end, end - start, "ERROR")
                                    report(listOf(e.message ?: "Błąd ATRV"))
                                    if (session?.failure?.value != null) throw e
                                }
                                scheduler.complete(-1, SystemClock.elapsedRealtime(), "Adapter:ATRV" in mutable.value.samples)
                                delay(80)
                            }
                            if (mutable.value.recording && mutable.value.autoDtcs && !mutable.value.busy && !mutable.value.ignitionOff && SystemClock.elapsedRealtime() - dtcReadMs >= 60_000) scanDtcs(obd)
                        }
                    }
                    delay(25)
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { report(listOf(e.message ?: "Błąd odczytu")); if (generation == token) { analyzer.finish(Instant.now().toString(), "Błąd odczytu — ocena przerwana"); mutable.update { it.copy(live = false, analysis = analyzer.snapshot()) }; endRecording("read_error") } }
            finally {
                if (generation == token) {
                    poll = null
                    // A start click at the end of the previous polling job must not leave Live stuck.
                    if (mutable.value.live && mutable.value.connected) startLive()
                }
            }
        }
    }
    private fun updateStats(latency: Long, issue: Boolean) {
        mutable.update { s -> s.copy(stats = s.stats.copy(requests = s.stats.requests + 1, issues = s.stats.issues + if (issue) 1 else 0, latencies = (s.stats.latencies + latency).takeLast(200))) }
    }
    private fun sample(key: String, ecu: String, definition: SignalDefinition, value: Double?, time: Long, latency: Long, status: String) {
        val utc = Instant.now().toString()
        val current = mutable.value
        val ids = SignalCatalog.available(requestedIds(current), current.supported.values.flatten().toSet())
        val fastCount = SignalCatalog.pollingPids(ids, current.supported.values.flatten().toSet()).count { PollingScheduler.isFast(it) }
        val latencies = current.stats.latencies.sorted()
        val median = latencies.getOrNull(latencies.size / 2) ?: 160L
        val cadence = PollingScheduler.interval(definition.pid ?: -1, current.cyclePauseMs)
        val freshness = maxOf(5000L, cadence * 3, (fastCount + 2) * (median + 80) * 2)
        analyzer.accept(ecu, definition.id, definition.name, definition.unit, value, status, time, utc, latency, freshness)
        val analysis = analyzer.snapshot()
        mutable.update { s ->
            val points = ChartSeries.append(s.history[key].orEmpty(), ChartPoint(time, value))
            val samples = if (value == null) s.samples - key else s.samples + (key to Sample(definition.id, definition.name, value, definition.unit, time))
            s.copy(samples = samples, history = s.history + (key to points), analysis = analysis,
                unavailable = if (status == "UNSUPPORTED") s.unavailable + key else if (status == "OK") s.unavailable - key else s.unavailable,
                freshness = s.freshness + (key to freshness))
        }
        val recording = recorder.append(utc, time, ecu, definition.id, definition.name, value, definition.unit, status, latency, freshness)
        if (recording != null) mutable.update { if (it.recording) it.copy(activeRecording = recording) else it }
    }
    suspend fun beginRecording(): Boolean {
        val before = mutable.value
        if (before.recording) return true
        if (!before.connected || before.busy) { showMessage("Najpierw połącz adapter"); return false }
        val ids = SignalCatalog.available(before.recordIds, supportedUnion())
        if (ids.isEmpty()) { showMessage("Wybierz obsługiwane parametry w kolumnie Zapis"); return false }
        return try {
            val info = withContext(Dispatchers.IO) { recorder.start(ids, before.demo, SystemClock.elapsedRealtime(), before.alertSettings, before.ignitionOff) }
            if (!mutable.value.connected) { withContext(Dispatchers.IO) { recorder.stop("disconnected") }; false }
            else {
                mutable.update { it.copy(recording = true, activeRecording = info) }
                operations.withLock { withContext(Dispatchers.IO) {
                    recorder.annotate(Instant.now().toString(), "Skalowanie PID 4F: ${mutable.value.scaling}; ECU z niepotwierdzonym 4F: ${mutable.value.supported.filterValues { 0x4F in it }.keys - mutable.value.scaling.keys}; VIN: ${mutable.value.vin}")
                    if (before.autoDtcs && !before.ignitionOff) client?.let { scanDtcs(it) }
                } }
                refreshRecordings(); if (mutable.value.recording && mutable.value.connected) { startLive(); true } else false
            }
        } catch (e: CancellationException) { withContext(NonCancellable) { endRecording("interrupted") }; throw e }
        catch (e: Exception) { endRecording("read_error"); showMessage("Nie można rozpocząć zapisu: ${e.message}"); false }
    }
    private suspend fun endRecording(reason: String) {
        try { withContext(Dispatchers.IO) { recorder.stop(reason) } }
        catch (e: Exception) { showMessage("Błąd zamykania CSV: ${e.message}") }
        finally { if (!uiVisible) analyzer.finish(Instant.now().toString(), "Koniec zapisu w tle — ocena przerwana"); mutable.update { it.copy(analysis = analyzer.snapshot(), recording = false, activeRecording = null, live = if (!uiVisible) false else it.live) }; refreshRecordings() }
    }
    fun stopRecording(reason: String = "stopped") { scope.launch { endRecording(reason) } }
    private fun refreshRecordings() { scope.launch { val rows = withContext(Dispatchers.IO) { recorder.list() }; mutable.update { it.copy(recordings = rows) } } }
    private fun operation(block: suspend (ObdClient) -> Unit) {
        if (!mutable.value.connected || mutable.value.busy) return
        val token = generation
        val expected = client ?: return
        mutable.update { it.copy(busy = true) }
        operationJob = scope.launch {
            try { operations.withLock { withContext(Dispatchers.IO) { if (generation == token) block(expected) } } }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (generation == token) showMessage(e.message) }
            finally { if (generation == token) mutable.update { it.copy(busy = false) } }
        }
    }
    fun readVin() = operation { obd ->
        mutable.update { it.copy(vin = emptyMap()) }
        val r = obd.vin(); currentCoroutineContext().ensureActive(); if (client === obd) { report(r.issues); mutable.update { it.copy(vin = r.byEcu) }; annotate("VIN: ${r.byEcu}; uwagi: ${r.issues}") }
    }
    private suspend fun scanDtcs(obd: ObdClient) {
        val r = obd.dtcScan(mutable.value.info?.isCan == true, mutable.value.supported.keys)
        currentCoroutineContext().ensureActive(); if (client !== obd) return
        dtcReadMs = SystemClock.elapsedRealtime()
        report(r.groups.flatMap { it.issues }.distinct())
        mutable.update { it.copy(dtcScan = r, dtcs = r.groups.filter { g -> g.status == "OK" }.associate { g -> "${g.ecu} · ${g.name}" to g.codes }) }
        recorder.recordDtcs(r)
        annotate("Odczyt DTC ${r.utc}: " + r.groups.joinToString { "${it.ecu}/${it.name} ${it.status}: ${it.codes}" } + "; NO DATA nie potwierdza braku DTC")
    }
    fun readDtcs() = operation { scanDtcs(it) }
    private suspend fun loadScaling(obd: ObdClient, supported: Map<String, Set<Int>>): Map<String, PidScaling> {
        if (supported.values.none { 0x4F in it }) return emptyMap()
        val r = obd.query(1, 0x4F); report(r.issues)
        val out = mutableMapOf<String, PidScaling>()
        r.payloads.forEach { p -> try { out[p.ecu] = PidScaling.decode(p) } catch (e: IllegalArgumentException) { report(listOf("${p.ecu}: ${e.message}")) } }
        (supported.filterValues { 0x4F in it }.keys - out.keys).forEach { report(listOf("$it: niepotwierdzony PID 4F — MAP/EQR/prąd sondy niedostępne do ponownego odczytu bitmap")) }
        return out
    }
    fun readSupported() = operation { obd ->
        val r = obd.supported(); val scaling = loadScaling(obd, r.byEcu)
        currentCoroutineContext().ensureActive(); if (client === obd) {
            report(r.issues); mutable.update { it.copy(supported = r.byEcu, scaling = scaling, unavailable = emptySet()) }; scheduler.reset()
            annotate("Odświeżono bitmapy i skalowanie PID 4F: $scaling")
        }
    }
    private fun annotate(text: String) {
        val utc = Instant.now().toString(); analyzer.addAnnotation(utc, text); recorder.annotate(utc, text)
        mutable.update { it.copy(analysis = analyzer.snapshot()) }
    }
    private fun updateReadiness(r: ObdParser.Result) {
        report(r.issues)
        val decoded = mutableMapOf<String, Readiness>()
        r.payloads.forEach { p -> try { decoded[p.ecu] = Readiness.decode(p) } catch (e: IllegalArgumentException) { report(listOf("${p.ecu}: ${e.message}")) } }
        if (decoded != mutable.value.readiness) {
            annotate(if (decoded.isEmpty()) "Status OBD: brak poprawnej odpowiedzi; wcześniejszy status nie potwierdza stanu bieżącego" else decoded.entries.joinToString("; ") { (ecu, status) -> "$ecu: MIL ${status.mil}, licznik DTC ${status.dtcCount}, niegotowe monitory ${status.monitors.filter { !it.complete }.joinToString { it.name }}" })
        }
        mutable.update { it.copy(readiness = decoded) }
    }
    fun readReadiness() = operation { obd ->
        val r = obd.query(1, 1); currentCoroutineContext().ensureActive(); if (client !== obd) return@operation
        healthReadMs = SystemClock.elapsedRealtime(); updateReadiness(r)
    }
    fun terminal(input: String) {
        val command = try { ReadOnlyCommand.parse(input) } catch (e: IllegalArgumentException) { showMessage(e.message); return }
        if (command.wire.startsWith("AT") && command.wire !in setOf("ATI", "ATDP", "ATDPN", "ATRV")) { showMessage("Terminal przyjmuje wyłącznie zapytania read-only"); return }
        operation { obd -> val r = obd.session.execute(command); showMessage("${r.command}: ${r.status}") }
    }
    fun exportLog(uri: Uri) = export(uri) { out -> logs.export(out) }
    fun exportRecording(id: String, uri: Uri) = export(uri) { out -> recorder.export(id, out) }
    private fun export(uri: Uri, write: (java.io.OutputStream) -> Unit) {
        scope.launch {
            try {
                withContext(Dispatchers.IO) { application.contentResolver.openOutputStream(uri)?.use(write) ?: error("Nie można otworzyć pliku") }
                showMessage("Plik wyeksportowany")
            } catch (e: Exception) { showMessage(e.message) }
        }
    }
}
