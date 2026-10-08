package pl.obd.readonly

import android.content.Context
import java.io.File
import java.io.OutputStream
import java.time.Instant
import java.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import pl.obd.core.*

data class LogLine(val id: Long, val time: String, val direction: Direction, val text: String)
/** Full raw bytes as base64 in append-only TSV; UI keeps just the most recent 300 chunks. */
class LogStore(context: Context) {
    private val dir = File(context.filesDir, "sessions").apply { mkdirs() }
    private val lock = Any()
    private val mutableLines = MutableStateFlow<List<LogLine>>(emptyList())
    val lines = mutableLines.asStateFlow()
    private var nextId = 0L
    private var current: File? = null
    fun newSession(): RawLog = synchronized(lock) {
        val file = File(dir, "obd-${System.currentTimeMillis()}-${java.util.UUID.randomUUID().toString().take(8)}.tsv")
        file.writeText("# OBD READ-ONLY raw log v1; UTC timestamp\tdirection\tbase64(bytes)\n")
        current = file
        mutableLines.value = emptyList()
        RawLog { direction, bytes -> synchronized(lock) {
            val time = Instant.now().toString()
            file.appendText("$time\t$direction\t${Base64.getEncoder().encodeToString(bytes)}\n")
            val visible = bytes.toString(Charsets.US_ASCII).replace("\r", "\\r").replace("\n", "\\n")
            mutableLines.value = (mutableLines.value + LogLine(nextId++, time, direction, visible)).takeLast(300)
        } }
    }
    fun export(output: OutputStream) {
        // Snapshot under the logger lock; writing to a document provider never blocks RX logging.
        val snapshot = synchronized(lock) {
            val file = current ?: error("Brak logu do eksportu")
            File.createTempFile("obd-export", ".tsv", dir).also { file.copyTo(it, overwrite = true) }
        }
        try { snapshot.inputStream().use { it.copyTo(output) } } finally { snapshot.delete() }
    }
}
