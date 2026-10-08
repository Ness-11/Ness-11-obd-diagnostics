package pl.obd.core

import java.util.Locale

data class ChartPoint(val elapsedMs: Long, val value: Double?)
/** Missing values and long pauses break a graph, never interpolate them as zero. */
object ChartSeries {
    fun segments(points: List<ChartPoint>, nowMs: Long, windowMs: Long, maxGapMs: Long): List<List<ChartPoint>> {
        val result = mutableListOf<MutableList<ChartPoint>>()
        var segment: MutableList<ChartPoint>? = null
        var previous: Long? = null
        for (point in points) {
            if (point.elapsedMs < nowMs - windowMs || point.elapsedMs > nowMs) continue
            if (point.value == null || !point.value.isFinite()) { segment = null; previous = null; continue }
            if (segment == null || previous?.let { point.elapsedMs - it > maxGapMs } == true) {
                segment = mutableListOf(); result += segment
            }
            segment.add(point); previous = point.elapsedMs
        }
        return result
    }
    fun append(points: List<ChartPoint>, point: ChartPoint, capacity: Int = 2000): List<ChartPoint> {
        require(capacity > 0)
        return (points + point).takeLast(capacity)
    }
}
data class CsvSample(val utc: String, val elapsedMs: Long, val ecu: String, val signalId: String, val name: String, val value: Double?, val unit: String, val status: String, val latencyMs: Long) {
    fun line(): String = listOf(utc, elapsedMs.toString(), ecu, signalId, name, value?.let { String.format(Locale.ROOT, "%.6f", it) } ?: "", unit, status, latencyMs.toString()).joinToString(",") { escape(it) } + "\n"
    companion object {
        const val HEADER = "utc,elapsed_ms,ecu,signal_id,name,value,unit,status,latency_ms\n"
        /** One physical row of this application's CSV; supports escaped quotes and commas. */
        fun parseLine(line: String): CsvSample {
            val fields = mutableListOf<String>(); val field = StringBuilder(); var quoted = false; var i = 0
            while (i < line.length) {
                val c = line[i]
                if (c == '"') {
                    if (quoted && i + 1 < line.length && line[i + 1] == '"') { field.append('"'); i++ }
                    else quoted = !quoted
                } else if (c == ',' && !quoted) { fields += field.toString(); field.setLength(0) }
                else field.append(c)
                i++
            }
            require(!quoted) { "Niepełny wiersz CSV" }; fields += field.toString()
            require(fields.size == 9) { "Niepoprawny schemat CSV" }
            val value = fields[5].takeIf { it.isNotEmpty() }?.toDouble()
            require(value == null || value.isFinite())
            return CsvSample(fields[0], fields[1].toLong(), fields[2], fields[3], fields[4], value, fields[6], fields[7], fields[8].toLong())
        }
        private fun escape(s: String) = if (s.any { it in charArrayOf(',', '"', '\r', '\n') }) "\"" + s.replace("\"", "\"\"") + "\"" else s
    }
}
