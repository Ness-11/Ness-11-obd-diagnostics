package pl.obd.readonly

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import pl.obd.core.AlertSettings
import java.util.Locale

@Composable
fun AnalysisScreen(state: UiState, model: ObdViewModel) {
    var draft by remember(state.alertSettings) { mutableStateOf(state.alertSettings) }
    val alerts = state.analysis.alerts
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 12.dp)) {
        item {
            Text("Analiza i alerty", style = MaterialTheme.typography.titleLarge)
            Text("Zestawienie całego bieżącego połączenia. Alerty są wskazówką do sprawdzenia, nie rozpoznaniem uszkodzenia.")
            OutlinedButton(onClick = { model.preset(pl.obd.core.SignalCatalog.byId.keys) }, enabled = !state.recording) { Text("Wybierz wszystkie dostępne pomiary") }
            Text("Potem uruchom Live lub zapis CSV. Więcej parametrów oznacza dłuższy cykl i gorszą widoczność krótkich zdarzeń.", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { model.markIgnitionOff(!state.ignitionOff) }, enabled = state.connected) { Text(if (state.ignitionOff) "Oznacz: zapłon włączony" else "Oznacz: zapłon wyłączony") }
            if (state.ignitionOff) Text("Zapłon oznaczony jako wyłączony: alerty parametrów wstrzymane, braki danych nadal zapisywane.", color = MaterialTheme.colorScheme.secondary)
            val currentIds = state.liveIds + if (state.recording) state.recordIds else emptySet()
            val noReply = state.analysis.measurements.filter { it.ecu != "Adapter" && it.signalId in currentIds }
            if (noReply.isNotEmpty() && noReply.all { it.lastStatus != "OK" }) Text(if (state.ignitionOff) "Brak odpowiedzi ECU po oznaczeniu wyłączenia zapłonu." else "Brak poprawnych odpowiedzi ECU — sprawdź zapłon i połączenie.", color = MaterialTheme.colorScheme.secondary)
            state.readiness.forEach { (ecu, status) -> Text("$ecu · MIL ${if (status.mil) "WŁĄCZONA" else "wyłączona"} · licznik DTC ${status.dtcCount} · niegotowe monitory ${status.monitors.count { !it.complete }}", color = if (status.mil) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary) }
            Text("Status MIL i monitorów odczytywany co 30 s podczas Live, jeśli ECU go obsługuje.", style = MaterialTheme.typography.bodySmall)
            Text("Aktywne alerty: ${alerts.count { it.endedUtc == null }} · zarejestrowane: ${alerts.size}", style = MaterialTheme.typography.titleMedium)
            if (alerts.isEmpty()) Text("Brak przekroczeń spełniających reguły. Reguły oceniają tylko odczytywane PID-y.", style = MaterialTheme.typography.bodySmall)
        }
        items(alerts.asReversed()) { alert ->
            Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
                Text("${alert.ecu} · ${if (alert.endedUtc == null) "AKTYWNY" else "ZAKOŃCZONY"}", color = if (alert.endedUtc == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary)
                Text(alert.message)
                Text("Od ${alert.startedUtc} ${alert.endReason.orEmpty()}", style = MaterialTheme.typography.bodySmall)
            } }
        }
        item {
            Text("Progi użytkownika", style = MaterialTheme.typography.titleMedium)
            Text("Podtrzymanie i histereza ograniczają pojedyncze skoki. Niskie napięcie i korekty wymagają świeżego RPM > 500 z tego samego ECU. Reguły mieszanki z PID 34 są wyłączone. Progi nie pochodzą ze specyfikacji Twojej Skody.", style = MaterialTheme.typography.bodySmall)
            Row { Text("Alerty włączone", Modifier.weight(1f)); Switch(checked = draft.enabled, onCheckedChange = { draft = draft.copy(enabled = it) }, enabled = !state.recording) }
            Threshold("Płyn: próg wysoki", draft.coolantHigh, "°C", 80f..140f, !state.recording) { draft = draft.copy(coolantHigh = it) }
            Threshold("Olej: próg wysoki", draft.oilHigh, "°C", 90f..170f, !state.recording) { draft = draft.copy(oilHigh = it) }
            Threshold("Napięcie ECU: próg niski", draft.voltageLow, "V", 9f..13f, !state.recording) { draft = draft.copy(voltageLow = it) }
            Threshold("Napięcie ECU: próg wysoki", draft.voltageHigh, "V", 14f..17f, !state.recording) { draft = draft.copy(voltageHigh = it) }
            Threshold("Korekty: wartość bezwzględna", draft.trimAbs, "%", 5f..50f, !state.recording) { draft = draft.copy(trimAbs = it) }
            Threshold("Podtrzymanie", draft.holdMs / 1000.0, "s", 1f..120f, !state.recording) { draft = draft.copy(holdMs = (it * 1000).toLong()) }
            Button(onClick = { model.setAlertSettings(draft) }, enabled = !state.recording && draft != state.alertSettings) { Text("Zapisz progi") }
            if (state.recording) Text("Zatrzymaj zapis, aby zmienić progi. Raport zachowuje progi obowiązujące w sesji.", style = MaterialTheme.typography.bodySmall)
            Text("Zestawienie pomiarów", style = MaterialTheme.typography.titleMedium)
        }
        items(state.analysis.measurements, key = { "${it.ecu}:${it.signalId}" }) { m ->
            Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
                Text("${m.ecu} · ${m.name}", style = MaterialTheme.typography.titleSmall)
                fun n(v: Double?) = v?.let { String.format(Locale.getDefault(), "%.2f", it) } ?: "—"
                Text("Min ${n(m.minimum)} · średnia ${n(m.average)} · max ${n(m.maximum)} ${m.unit}")
                val total = m.ok + m.missing
                Text("OK ${m.ok} · braki ${m.missing} · jakość ${if (total > 0) m.ok * 100 / total else 0}% · ${m.lastStatus}", style = MaterialTheme.typography.bodySmall)
                Text("Śr. odstęp ${n(m.meanIntervalMs?.div(1000))} s · śr. RTT ${n(m.meanLatencyMs)} ms", style = MaterialTheme.typography.bodySmall)
            } }
        }
        item { SelectionContainer { Text(state.analysis.annotations.takeLast(8).joinToString("\n"), style = MaterialTheme.typography.bodySmall) } }
    }
}

@Composable
private fun Threshold(title: String, value: Double, unit: String, range: ClosedFloatingPointRange<Float>, enabled: Boolean, change: (Double) -> Unit) {
    Text("$title: ${String.format(Locale.getDefault(), "%.1f", value)} $unit", style = MaterialTheme.typography.bodySmall)
    Slider(value = value.toFloat(), onValueChange = { change(kotlin.math.round(it * 10.0) / 10.0) }, valueRange = range, enabled = enabled)
}
