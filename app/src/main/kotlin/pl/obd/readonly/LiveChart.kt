package pl.obd.readonly

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import pl.obd.core.*
import kotlin.math.abs
import kotlin.math.max

fun displayValue(value: Double, unit: String): String = when {
    unit == "λ" -> "%.3f".format(value)
    unit == "V" || unit == "g/s" || unit == "mA" -> "%.2f".format(value)
    abs(value) >= 1000 || unit == "rpm" || unit == "km" || unit == "s" -> "%.0f".format(value)
    else -> "%.1f".format(value)
}

@Composable
fun LiveChart(points: List<ChartPoint>, nowMs: Long, windowMs: Long, gapMs: Long, unit: String) {
    val segments = remember(points, nowMs, windowMs, gapMs) { ChartSeries.segments(points, nowMs, windowMs, gapMs) }
    val visible = segments.flatten()
    if (visible.isEmpty()) { Text("Brak próbek w tym oknie", style = MaterialTheme.typography.bodySmall); return }
    val minimum = visible.minOf { it.value!! }
    val maximum = visible.maxOf { it.value!! }
    val padding = max((maximum - minimum) * .1, max(abs(maximum) * .01, .1))
    val bottom = minimum - padding
    val top = maximum + padding
    val lineColor = MaterialTheme.colorScheme.primary
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val textColor = MaterialTheme.colorScheme.onSurfaceVariant
    var cursor by remember { mutableStateOf<ChartPoint?>(null) }
    var canvasWidth by remember { mutableFloatStateOf(1f) }
    val currentPoints by rememberUpdatedState(visible)
    val currentNow by rememberUpdatedState(nowMs)
    val leftPx = with(androidx.compose.ui.platform.LocalDensity.current) { 48.dp.toPx() }
    val rightPx = with(androidx.compose.ui.platform.LocalDensity.current) { 8.dp.toPx() }
    fun select(x: Float) {
        val fraction = ((x - leftPx) / (canvasWidth - leftPx - rightPx).coerceAtLeast(1f)).coerceIn(0f, 1f)
        val time = currentNow - windowMs + (windowMs * fraction).toLong()
        cursor = currentPoints.minByOrNull { abs(it.elapsedMs - time) }
    }
    Canvas(Modifier.fillMaxWidth().height(150.dp).onSizeChanged { canvasWidth = it.width.toFloat() }
        .pointerInput(windowMs) { detectTapGestures { select(it.x) } }
        .pointerInput(windowMs) { detectHorizontalDragGestures(onDragStart = { select(it.x) }) { change, _ -> select(change.position.x); change.consume() } }) {
        val left = 48.dp.toPx(); val right = size.width - 8.dp.toPx()
        val yTop = 8.dp.toPx(); val yBottom = size.height - 22.dp.toPx()
        fun x(point: ChartPoint) = left + ((point.elapsedMs - (nowMs - windowMs)).toFloat() / windowMs) * (right - left)
        fun y(value: Double) = yBottom - ((value - bottom) / (top - bottom)).toFloat() * (yBottom - yTop)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = textColor.toArgb(); textSize = 10.dp.toPx() }
        for (fraction in listOf(0.0, .5, 1.0)) {
            val value = bottom + (top - bottom) * fraction
            val yy = y(value)
            drawLine(gridColor, Offset(left, yy), Offset(right, yy), strokeWidth = 1f)
            drawContext.canvas.nativeCanvas.drawText(displayValue(value, unit), 0f, yy + 4.dp.toPx(), paint)
        }
        segments.forEach { segment ->
            if (segment.size == 1) drawCircle(lineColor, 2.dp.toPx(), Offset(x(segment[0]), y(segment[0].value!!)))
            else {
                val path = Path()
                segment.forEachIndexed { index, point -> if (index == 0) path.moveTo(x(point), y(point.value!!)) else path.lineTo(x(point), y(point.value!!)) }
                drawPath(path, lineColor, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx()))
            }
        }
        cursor?.takeIf { it.elapsedMs >= nowMs - windowMs }?.let { point ->
            drawLine(textColor, Offset(x(point), yTop), Offset(x(point), yBottom), 1f)
            drawCircle(Color.White, 4.dp.toPx(), Offset(x(point), y(point.value!!)))
        }
        drawContext.canvas.nativeCanvas.drawText("−${windowMs / 1000}s", left, size.height - 3.dp.toPx(), paint)
        drawContext.canvas.nativeCanvas.drawText("teraz", right - 30.dp.toPx(), size.height - 3.dp.toPx(), paint)
    }
    Text("Okno: min ${displayValue(minimum, unit)} · max ${displayValue(maximum, unit)} · śr. ${displayValue(visible.map { it.value!! }.average(), unit)} $unit", style = MaterialTheme.typography.bodySmall)
    cursor?.let { Text("Kursor: ${displayValue(it.value!!, unit)} $unit · ${((nowMs - it.elapsedMs).coerceAtLeast(0) / 1000)} s temu", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
}
