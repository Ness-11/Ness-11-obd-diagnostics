package pl.obd.readonly

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import pl.obd.core.*

class MainActivity : ComponentActivity() {
    private val engine get() = (application as ObdApplication).engine
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val model: ObdViewModel = viewModel()
            val state by model.state.collectAsStateWithLifecycle()
            val lines by model.logs.lines.collectAsStateWithLifecycle()
            var pendingPermission by remember { mutableStateOf<(() -> Unit)?>(null) }
            var reportId by rememberSaveable { mutableStateOf<String?>(null) }
            var exportId by rememberSaveable { mutableStateOf<String?>(null) }
            fun startService() {
                try { ContextCompat.startForegroundService(this, Intent(this, RecordingService::class.java)) }
                catch (e: Exception) { model.showMessage("Nie można rozpocząć zapisu: ${e.message}") }
            }
            val notifications = androidx.activity.compose.rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { startService() }
            fun recordingWithNotifications() {
                if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                else startService()
            }
            val permission = androidx.activity.compose.rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                if (granted) pendingPermission?.invoke() else model.showMessage("Uprawnienie Bluetooth jest wymagane do połączenia i zapisu w tle")
                pendingPermission = null
            }
            fun bluetoothPermission(action: () -> Unit) {
                if (Build.VERSION.SDK_INT >= 31 && ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) { pendingPermission = action; permission.launch(Manifest.permission.BLUETOOTH_CONNECT) }
                else action()
            }
            val exportRaw = androidx.activity.compose.rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/tab-separated-values")) { it?.let(model::export) }
            val exportCsv = androidx.activity.compose.rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri -> if (uri != null) exportId?.let { model.exportRecording(it, uri) }; exportId = null }
            val exportZip = androidx.activity.compose.rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri -> if (uri != null) exportId?.let { model.exportBundle(it, uri) }; exportId = null }
            val exportReport = androidx.activity.compose.rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri -> if (uri != null) reportId?.let { model.exportReport(it, uri) }; reportId = null }
            val colors = darkColorScheme(primary = Color(0xFF58DEBC), secondary = Color(0xFF8DBBFF), background = Color(0xFF10171E), surface = Color(0xFF19232E))
            MaterialTheme(colorScheme = colors) {
                Surface(Modifier.fillMaxSize()) {
                    DiagnosticScreen(state, lines, model,
                        refresh = { bluetoothPermission { model.refreshDevices() } },
                        startRecording = { bluetoothPermission { recordingWithNotifications() } },
                        exportRaw = { exportRaw.launch("obd-raw-${System.currentTimeMillis()}.tsv") },
                        exportCsv = { id -> exportId = id; exportCsv.launch("$id.csv") },
                        exportBundle = { id -> exportId = id; exportZip.launch("$id.zip") },
                        exportReport = { id -> reportId = id; exportReport.launch("$id-raport.md") })
                }
            }
        }
    }
    override fun onStart() { super.onStart(); engine.setUiVisible(true) }
    override fun onStop() { engine.setUiVisible(false); super.onStop() }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun DiagnosticScreen(state: UiState, lines: List<LogLine>, model: ObdViewModel, refresh: () -> Unit, startRecording: () -> Unit, exportRaw: () -> Unit, exportCsv: (String) -> Unit, exportReport: (String) -> Unit, exportBundle: (String) -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(4) }
    var input by rememberSaveable { mutableStateOf("010C") }
    var clock by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(Unit) { while (true) { delay(500); clock = SystemClock.elapsedRealtime() } }
    val supported = state.supported.values.flatten().toSet()
    val latencies = state.stats.latencies.sorted()
    val median = if (latencies.isEmpty()) 155L else latencies[latencies.size / 2]
    Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(horizontal = 14.dp)) {
        Text("OBD / DIAGNOSTICS", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 12.dp))
        Text("READ ONLY · v0.4 · Bluetooth Classic", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
        Card(Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Column(Modifier.padding(10.dp)) {
                Text((if (state.demo) "DEMO · " else "") + state.status, color = MaterialTheme.colorScheme.primary)
                state.info?.let { Text(it.protocol, style = MaterialTheme.typography.bodySmall) }
                if (state.analysis.alerts.any { it.endedUtc == null }) TextButton(onClick = { tab = 6 }) { Text("⚠ Aktywne alerty: ${state.analysis.alerts.count { it.endedUtc == null }}", color = MaterialTheme.colorScheme.error) }
                if (state.recording) Text("● ZAPIS CSV · ${state.activeRecording?.rows ?: 0} wierszy · także w tle", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                if (state.connected || state.busy) TextButton(onClick = model::disconnect, contentPadding = PaddingValues(0.dp)) { Text("Rozłącz") }
            }
        }
        state.message?.let { message -> Row(Modifier.fillMaxWidth()) {
            Text(message, Modifier.weight(1f).padding(vertical = 6.dp), color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = model::clearMessage) { Text("OK") }
        } }
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        ScrollableTabRow(selectedTabIndex = tab, edgePadding = 0.dp) {
            listOf("Live", "Parametry", "OBD", "Zapisy", "Adapter", "Terminal", "Analiza").forEachIndexed { i, title -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(title) }) }
        }
        state.savedReport?.let { text ->
            AlertDialog(onDismissRequest = model::closeReport, confirmButton = { TextButton(onClick = model::closeReport) { Text("Zamknij") } },
                title = { Text("Raport sesji") }, text = { LazyColumn { item { SelectionContainer { Text(text, style = MaterialTheme.typography.bodySmall) } } } })
        }
        when (tab) {
            6 -> Box(Modifier.weight(1f)) { AnalysisScreen(state, model) }
            0 -> LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 12.dp)) {
                item {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { if (state.live) model.pauseLive() else model.startLive() }, enabled = state.connected && !state.busy) { Text(if (state.live) (if (state.recording) "Zatrzymaj wszystko" else "Zatrzymaj Live") else "Start Live") }
                        OutlinedButton(onClick = { if (state.recording) model.stopRecording() else startRecording() }, enabled = state.connected && !state.busy) { Text(if (state.recording) "Stop zapisu" else "Zapisz CSV") }
                    }
                    Text("Parametry i wybór zapisu → zakładka Parametry", style = MaterialTheme.typography.bodySmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(30_000L, 60_000L, 180_000L).forEach { ms -> FilterChip(selected = state.chartWindowMs == ms, onClick = { model.setChartWindow(ms) }, label = { Text("${ms / 1000} s") }) }
                    }
                    Text("${state.stats.requests} zapytań Live · ${state.stats.issues} z uwagami · mediana ${median} ms", style = MaterialTheme.typography.bodySmall)
                    Text("Szybkie odczyty mają priorytet. Rzeczywiste odstępy próbek → Analiza.", style = MaterialTheme.typography.bodySmall)
                }
                val keys = state.history.keys.filter { it.substringAfter(':') in state.liveIds }.sorted()
                if (keys.isEmpty()) item { Text("Wybierz parametry i uruchom Live. Dotknięcie wykresu pokazuje wartość; przeciągaj poziomo, aby przesuwać kursor.") }
                items(keys, key = { it }) { key ->
                    val definition = SignalCatalog.byId.getValue(key.substringAfter(':'))
                    val sample = state.samples[key]
                    val points = state.history[key].orEmpty()
                    val plotNow = if (state.live) clock else points.lastOrNull()?.elapsedMs ?: clock
                    val staleAfter = state.freshness[key] ?: 5000L
                    val gapMs = staleAfter
                    val age = sample?.let { (clock - it.time).coerceAtLeast(0) }
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text("${key.substringBefore(':')} · ${definition.name}", style = MaterialTheme.typography.titleSmall)
                            Text(sample?.let { "${displayValue(it.value, it.unit)} ${it.unit}" } ?: "Brak poprawnego odczytu", style = MaterialTheme.typography.headlineSmall)
                            Text(when { age == null -> "Luka w danych"; age > staleAfter -> "NIEAKTUALNE · ${age / 1000} s temu"; else -> "${age / 1000} s temu" }, color = if (age == null || age > staleAfter) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.bodySmall)
                            LiveChart(points, plotNow, state.chartWindowMs, gapMs, definition.unit)
                        }
                    }
                }
                item { Text("Wykresy mają własną skalę i jednostki. Braki odpowiedzi tworzą przerwy; nie są interpolowane jako zero.", style = MaterialTheme.typography.bodySmall); Issues(state.issues) }
            }
            1 -> LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(vertical = 12.dp)) {
                item {
                    Text("Na żywo — karty i wykresy. Zapis — tylko te dane trafią do CSV. Wybór jest zapamiętywany.")
                    Text(if (state.recording) "Zatrzymaj zapis, aby zmienić jego parametry." else "Zapis może obejmować inne parametry niż widoczne wykresy.", style = MaterialTheme.typography.bodySmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("Wszystkie dostępne" to SignalCatalog.byId.keys, "Podstawowe" to SignalCatalog.defaultIds, "Dolot" to setOf("0C", "0B", "10", "0F", "33", "11"), "Paliwo" to setOf("0C", "23", "34.lambda", "34.current", "06", "07", "2F")).forEach { (name, ids) -> AssistChip(onClick = { model.preset(ids) }, enabled = !state.recording, label = { Text(name) }) }
                    }
                    if (state.connected) {
                        val unknown = supported - SignalCatalog.pids - ReadOnlyCommand.supportPages - setOf(1, 0x4F)
                        Text("Pokrycie: ${supported.intersect(SignalCatalog.pids).size} dekodowanych PID-ów z ${supported.size} pozycji bitmapy (w tym strony bitmap i status).", style = MaterialTheme.typography.bodySmall)
                        if (unknown.isNotEmpty()) Text("Obsługiwane przez ECU, jeszcze bez dekodera: " + unknown.sorted().joinToString(" ") { "%02X".format(it) }, style = MaterialTheme.typography.bodySmall)
                    }
                    Text("Docelowy odstęp szybkich odczytów (zależny od liczby PID i adaptera). Temperatury co 5 s, liczniki co 60 s:", style = MaterialTheme.typography.bodySmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(100L, 500L, 1000L, 2000L).forEach { ms -> FilterChip(selected = state.cyclePauseMs == ms, onClick = { model.setCyclePause(ms) }, label = { Text("$ms ms") }) }
                    }
                    Row(Modifier.fillMaxWidth()) { Text("Parametr", Modifier.weight(1f)); Text("Live", Modifier.width(48.dp)); Text("Zapis", Modifier.width(48.dp)) }
                }
                SignalCatalog.definitions.groupBy { it.group }.forEach { (group, definitions) ->
                    item(key = "group-$group") { Text(group, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 10.dp)) }
                    items(definitions, key = { it.id }) { definition ->
                        val ecus = state.supported.filterValues { definition.pid in it }.keys
                        val maskAbsent = ecus.isNotEmpty() && ecus.all { "$it:${definition.id}" in state.unavailable }
                        val available = !state.connected || (definition.pid == null || definition.pid in supported) && !maskAbsent
                        Row(Modifier.fillMaxWidth()) {
                            Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                                Text(definition.name, color = if (available) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline)
                                Text((definition.pid?.let { "PID %02X".format(it) } ?: "ATRV") + " · ${definition.unit} · ${PollingScheduler.label(definition.pid, state.cyclePauseMs)}" + if (!available) " · nieobsługiwany" else if (!state.connected) " · do sprawdzenia" else "", style = MaterialTheme.typography.bodySmall)
                            }
                            Checkbox(checked = definition.id in state.liveIds, onCheckedChange = { model.toggleSignal(definition.id, false) }, enabled = available, modifier = Modifier.width(48.dp))
                            Checkbox(checked = definition.id in state.recordIds, onCheckedChange = { model.toggleSignal(definition.id, true) }, enabled = available && !state.recording, modifier = Modifier.width(48.dp))
                        }
                    }
                }
            }
            2 -> LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 12.dp)) {
                item {
                    Text("Standardowe OBD silnika. To nie jest jeszcze pełny skan modułów VAG.")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = model::readVin, enabled = state.connected && !state.busy) { Text("VIN") }
                        OutlinedButton(onClick = model::readDtcs, enabled = state.connected && !state.busy) { Text("DTC") }
                        OutlinedButton(onClick = model::readReadiness, enabled = state.connected && !state.busy && 1 in supported) { Text("Monitory OBD") }
                    }
                    Text("Ręczny odczyt na chwilę wstrzymuje polling. Zapis CSV pozostaje aktywny.", style = MaterialTheme.typography.bodySmall)
                }
                items(state.vin.toList(), key = { "vin-${it.first}" }) { (ecu, vin) -> SelectionContainer { Text("VIN $ecu: $vin", fontFamily = FontFamily.Monospace) } }
                item {
                    Row { Checkbox(state.autoDtcs, onCheckedChange = model::setAutoDtcs, enabled = !state.recording); Text("Skan DTC przy rozpoczęciu zapisu i co 60 s", Modifier.padding(top = 12.dp)) }
                    Text("Odczyty 03 / 07 / 0A trafiają do raportu i pakietu ZIP. Brak obsługi danego trybu nie oznacza braku DTC.", style = MaterialTheme.typography.bodySmall)
                    state.dtcScan?.let { Text("Ostatni skan UTC: ${it.utc}", style = MaterialTheme.typography.bodySmall) }
                }
                items(state.dtcScan?.groups.orEmpty(), key = { "dtc-${it.ecu}-${it.service}" }) { g -> Text("${g.ecu} · ${g.name}: " + if (g.status != "OK") "${g.status} — stan niepotwierdzony" else if (g.codes.isEmpty()) "Brak DTC w poprawnej odpowiedzi" else g.codes.joinToString()) }
                items(state.readiness.toList(), key = { "ready-${it.first}" }) { (ecu, readiness) ->
                    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
                        Text("$ecu · MIL ${if (readiness.mil) "WŁĄCZONA" else "wyłączona"} · DTC ${readiness.dtcCount}", style = MaterialTheme.typography.titleSmall)
                        Text(readiness.ignition, style = MaterialTheme.typography.bodySmall)
                        readiness.monitors.forEach { monitor -> Text("${monitor.name}: ${if (monitor.complete) "gotowy" else "NIEGOTOWY"}", color = if (monitor.complete) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary) }
                        Text("Gotowość monitorów nie oznacza oceny sprawności auta.", style = MaterialTheme.typography.bodySmall)
                    } }
                }
                item { Text("NO DATA nie potwierdza braku DTC. Nie ma kasowania, kodowania ani testów wykonawczych.", style = MaterialTheme.typography.bodySmall); Issues(state.issues) }
            }
            3 -> LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 12.dp)) {
                item {
                    Text("Rejestrator parametrów", style = MaterialTheme.typography.titleLarge)
                    Text("Wybrane pomiary zapisują się do CSV z czasem UTC, ECU, jednostką, statusem i czasem odpowiedzi. Pliki zostają po rozłączeniu.")
                    Button(onClick = { if (state.recording) model.stopRecording() else startRecording() }, enabled = state.connected && !state.busy) { Text(if (state.recording) "Zatrzymaj zapis" else "Rozpocznij zapis") }
                    Text("Zapis działa także przy wygaszonym ekranie. Zatrzymasz go tutaj lub w powiadomieniu. Po zerwaniu połączenia nie wznawia się automatycznie.", style = MaterialTheme.typography.bodySmall)
                }
                item { OutlinedButton(onClick = model::compareRecordings, enabled = state.recordings.count { it.status != "active" } >= 2) { Text("Porównaj ostatnie sesje") } }
                if (state.recordings.isEmpty()) item { Text("Brak zapisanych sesji") }
                items(state.recordings, key = { it.id }) { info ->
                    val active = info.id == state.activeRecording?.id
                    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
                        Text((if (info.demo) "DEMO · " else "") + info.started.replace('T', ' ').substringBefore('.'), style = MaterialTheme.typography.titleSmall)
                        val status = if (active) "W TRAKCIE" else when (info.status) { "stopped" -> "Zatrzymano"; "disconnected" -> "Rozłączono"; "connection_lost" -> "Utrata połączenia"; "interrupted" -> "Przerwane zakończenie"; "read_error" -> "Błąd odczytu"; "service_stopped" -> "Zatrzymano usługę"; else -> info.status }
                        Text("$status · ${info.signals} parametrów · ${if (active) state.activeRecording?.rows else info.rows} wierszy", style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { model.viewReport(info.id) }) { Text("Zestawienie i alerty") }
                        OutlinedButton(onClick = { exportBundle(info.id) }) { Text("Eksportuj sesję ZIP (CSV + raport + DTC)") }
                        TextButton(onClick = { exportReport(info.id) }) { Text("Eksportuj raport") }
                        OutlinedButton(onClick = { exportCsv(info.id) }) { Text(if (active) "Eksportuj bieżący CSV" else "Eksportuj CSV") }
                    } }
                }
            }
            4 -> LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 12.dp)) {
                item { Text("Sparuj adapter w ustawieniach Bluetooth. Włącz zapłon, odśwież listę i połącz."); TextButton(onClick = refresh, enabled = !state.connected && !state.busy) { Text("Odśwież urządzenia") } }
                items(state.devices, key = { it.address }) { device -> OutlinedCard(onClick = { model.select(device.address) }, enabled = !state.connected && !state.busy, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(10.dp)) { RadioButton(selected = device.address == state.selected, onClick = { model.select(device.address) }, enabled = !state.connected && !state.busy); Column(Modifier.padding(start = 8.dp)) { Text(device.name); Text(device.address, style = MaterialTheme.typography.bodySmall) } }
                } }
                item {
                    Button(onClick = { model.connect() }, enabled = !state.connected && !state.busy && state.selected != null, modifier = Modifier.fillMaxWidth()) { Text("Połącz przez SPP") }
                    OutlinedButton(onClick = { model.connect(demo = true) }, enabled = !state.connected && !state.busy, modifier = Modifier.fillMaxWidth()) { Text("Uruchom demonstrację") }
                    Text("Demo generuje fikcyjne pomiary; zapis jest oznaczony DEMO.", style = MaterialTheme.typography.bodySmall)
                    Text("Obsługiwane PID-y", style = MaterialTheme.typography.titleMedium)
                }
                items(state.supported.toList(), key = { it.first }) { (ecu, pids) -> SelectionContainer { Text("$ecu: " + pids.sorted().joinToString(" ") { "%02X".format(it) }, fontFamily = FontFamily.Monospace) } }
                item { OutlinedButton(onClick = model::readSupported, enabled = state.connected && !state.busy) { Text("Odczytaj bitmapy ponownie") }; Issues(state.issues) }
            }
            5 -> Column(Modifier.weight(1f)) {
                Text("Terminal read-only. Pełny log komunikacji TSV jest niezależny od wyboru parametrów CSV.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = input, onValueChange = { input = it }, singleLine = true, label = { Text("Polecenie") }, modifier = Modifier.weight(1f))
                    Button(onClick = { model.terminal(input) }, enabled = state.connected && !state.busy, modifier = Modifier.padding(top = 8.dp)) { Text("Wyślij") }
                }
                TextButton(onClick = exportRaw, enabled = lines.isNotEmpty()) { Text("Eksportuj pełny log TSV") }
                LazyColumn(Modifier.weight(1f).fillMaxWidth().background(Color(0xFF090F14)), reverseLayout = true, contentPadding = PaddingValues(8.dp)) {
                    items(lines.asReversed(), key = { it.id }) { line -> SelectionContainer { Text("${line.time.substringAfter('T')} ${line.direction} ${line.text}", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, color = if (line.direction == Direction.TX) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface) } }
                }
                Text("Ostatnie 300 fragmentów; eksport obejmuje całą sesję.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
@Composable
private fun Issues(issues: List<String>) {
    if (issues.isNotEmpty()) { Text("Uwagi do odpowiedzi", style = MaterialTheme.typography.titleMedium); issues.takeLast(10).forEach { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) } }
}
