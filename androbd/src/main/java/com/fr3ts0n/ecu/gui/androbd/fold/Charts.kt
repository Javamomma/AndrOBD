package com.fr3ts0n.ecu.gui.androbd.fold

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** min / average / max of the samples inside a time range */
class Stats(val min: Float, val avg: Float, val max: Float)

fun Series.stats(from: Long, to: Long): Stats? {
    var lo = Float.MAX_VALUE
    var hi = -Float.MAX_VALUE
    var sum = 0.0
    var n = 0
    for (i in 0 until size) {
        val tt = t[i]
        if (tt < from) continue
        if (tt > to) break
        val vv = v[i]
        lo = min(lo, vv)
        hi = max(hi, vv)
        sum += vv
        n++
    }
    if (n == 0) {
        // only a held value from before the window
        val last = (size - 1 downTo 0).firstOrNull { t[it] <= to } ?: return null
        return Stats(v[last], v[last], v[last])
    }
    return Stats(lo, (sum / n).toFloat(), hi)
}

/** index of the sample nearest to time [x] (binary search) */
private fun Series.nearest(x: Long): Int {
    if (size == 0) return -1
    var lo = 0
    var hi = size - 1
    while (lo < hi) {
        val mid = (lo + hi) ushr 1
        if (t[mid] < x) lo = mid + 1 else hi = mid
    }
    return if (lo > 0 && abs(t[lo - 1] - x) < abs(t[lo] - x)) lo - 1 else lo
}

private class ValueRange(val lo: Float, val hi: Float)

private fun Series.range(to: Long): ValueRange? {
    var lo = Float.MAX_VALUE
    var hi = -Float.MAX_VALUE
    var n = 0
    for (i in 0 until size) {
        if (t[i] > to) break
        lo = min(lo, v[i])
        hi = max(hi, v[i])
        n++
    }
    if (n == 0) return null
    val span = hi - lo
    val pad = if (span < 1e-6f) max(abs(hi) * 0.08f, 1f) else span * 0.16f
    var a = lo - pad
    val b = hi + pad
    if (lo >= 0f && a < 0f) a = 0f
    return ValueRange(a, b)
}

/**
 * Screen-space polyline for a series. Dense data is reduced to min/max per pixel column so
 * long sessions stay fast without losing peaks.
 */
private fun Series.points(
    from: Long, to: Long, left: Float, top: Float, w: Float, h: Float, r: ValueRange,
): FloatArray {
    val spanT = (to - from).coerceAtLeast(1L).toFloat()
    val spanV = (r.hi - r.lo).takeIf { it > 0f } ?: 1f
    fun x(tt: Long) = left + (tt - from) / spanT * w
    fun y(vv: Float) = top + h - (vv - r.lo) / spanV * h

    var visible = 0
    for (i in 0 until size) if (t[i] <= to) visible++ else break
    if (visible == 0) return FloatArray(0)

    if (visible <= w * 2) {
        val out = FloatArray(visible * 2)
        for (i in 0 until visible) {
            out[2 * i] = x(t[i])
            out[2 * i + 1] = y(v[i])
        }
        return out
    }
    val out = ArrayList<Float>((w * 4).toInt())
    var col = Int.MIN_VALUE
    var cMin = 0f
    var cMax = 0f
    var cx = 0f
    fun emit() {
        if (col == Int.MIN_VALUE) return
        out += cx; out += y(cMin)
        if (cMax != cMin) { out += cx; out += y(cMax) }
    }
    for (i in 0 until visible) {
        val px = x(t[i])
        val c = px.toInt()
        if (c != col) {
            emit()
            col = c; cx = px; cMin = v[i]; cMax = v[i]
        } else {
            cMin = min(cMin, v[i]); cMax = max(cMax, v[i])
        }
    }
    emit()
    return out.toFloatArray()
}

private fun linePath(pts: FloatArray, smooth: Boolean, extendTo: Float?): Path {
    val p = Path()
    val n = pts.size / 2
    if (n == 0) return p
    p.moveTo(pts[0], pts[1])
    if (smooth && n in 3..600) {
        for (i in 1 until n) {
            val px = pts[2 * (i - 1)]; val py = pts[2 * (i - 1) + 1]
            val cx = pts[2 * i]; val cy = pts[2 * i + 1]
            val mx = (px + cx) / 2f
            p.cubicTo(mx, py, mx, cy, cx, cy)
        }
    } else {
        for (i in 1 until n) p.lineTo(pts[2 * i], pts[2 * i + 1])
    }
    if (extendTo != null && extendTo > pts[2 * (n - 1)]) p.lineTo(extendTo, pts[2 * (n - 1) + 1])
    return p
}

private fun DrawScope.drawSeries(
    pts: FloatArray, color: Color, top: Float, bottom: Float, extendTo: Float?,
    stroke: Float, glow: Boolean, fillAlpha: Float,
) {
    val n = pts.size / 2
    if (n == 0) return
    val line = linePath(pts, smooth = true, extendTo = extendTo)
    val endX = max(extendTo ?: pts[2 * (n - 1)], pts[2 * (n - 1)])
    val fill = Path().apply {
        addPath(line)
        lineTo(endX, bottom)
        lineTo(pts[0], bottom)
        close()
    }
    drawPath(fill, Brush.verticalGradient(listOf(color.copy(alpha = fillAlpha), color.copy(alpha = 0f)), startY = top, endY = bottom))
    if (glow) drawPath(line, color.copy(alpha = 0.16f), style = Stroke(width = stroke * 3.2f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    drawPath(line, color, style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

/**
 * Full chart: gridlines, value labels on the right, time labels below, glowing line with a
 * gradient fill, a dot on the newest value, and press-and-hold to read any sample.
 *
 * @param live time labels relative to now ("-30s") instead of elapsed session time
 * @param holdToEnd extend the newest value to the right edge (values are only sent on change)
 */
@Composable
fun LineChart(
    series: Series,
    from: Long,
    to: Long,
    color: Color,
    units: String,
    live: Boolean,
    holdToEnd: Boolean,
    modifier: Modifier = Modifier,
    sessionStart: Long = from,
) {
    val palette = LocalPalette.current
    val measurer = rememberTextMeasurer()
    val axisStyle = MaterialTheme.typography.labelSmall.merge(Numeric).copy(color = palette.textFaint)
    val bubbleStyle = MaterialTheme.typography.labelLarge.merge(Numeric).copy(color = palette.text)
    var touch by remember { mutableStateOf<Offset?>(null) }

    Canvas(
        modifier.pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                touch = down.position
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) break
                    touch = change.position
                    change.consume()
                }
                touch = null
            }
        }
    ) {
        val gutterR = 46.dp.toPx()
        val gutterB = 20.dp.toPx()
        val top = 8.dp.toPx()
        val plotW = size.width - gutterR
        val plotH = size.height - gutterB - top
        if (plotW <= 8f || plotH <= 8f) return@Canvas
        val bottom = top + plotH

        // horizontal gridlines + value labels
        val r = series.range(to)
        val rows = if (plotH > 120.dp.toPx()) 4 else 2
        for (i in 0..rows) {
            val y = top + plotH * i / rows
            drawLine(palette.grid, Offset(0f, y), Offset(plotW, y), strokeWidth = 1.dp.toPx())
            if (r != null) {
                val value = r.hi - (r.hi - r.lo) * i / rows
                drawLabel(measurer, formatNumber(value), axisStyle, Offset(plotW + 8.dp.toPx(), y), alignCenterY = true)
            }
        }
        // time labels
        val cols = if (plotW > 420.dp.toPx()) 4 else 2
        for (i in 0..cols) {
            val x = plotW * i / cols
            val tt = from + (to - from) * i / cols
            val text = if (live) relativeLabel(to - tt) else formatDuration(tt - sessionStart)
            val m = measurer.measure(text, axisStyle)
            val lx = (x - m.size.width / 2f).coerceIn(0f, max(0f, plotW - m.size.width))
            drawText(m, topLeft = Offset(lx, bottom + 4.dp.toPx()))
        }

        if (r == null) {
            val m = measurer.measure("Waiting for data…", axisStyle)
            drawText(m, topLeft = Offset((plotW - m.size.width) / 2f, top + (plotH - m.size.height) / 2f))
            return@Canvas
        }

        val pts = series.points(from, to, 0f, top, plotW, plotH, r)
        val n = pts.size / 2
        clipRect(0f, 0f, plotW, bottom + 1f) {
            drawSeries(pts, color, top, bottom, if (holdToEnd) plotW else null, 2.4.dp.toPx(), glow = true, fillAlpha = if (palette.dark) 0.30f else 0.22f)
        }
        if (n > 0) {
            val lx = if (holdToEnd) plotW else pts[2 * (n - 1)]
            val ly = pts[2 * (n - 1) + 1]
            drawCircle(color.copy(alpha = 0.22f), radius = 8.dp.toPx(), center = Offset(lx, ly))
            drawCircle(color, radius = 3.6.dp.toPx(), center = Offset(lx, ly))
        }

        // press-and-hold crosshair
        val tp = touch
        if (tp != null && series.size > 0) {
            val tAt = from + ((tp.x.coerceIn(0f, plotW) / plotW) * (to - from)).toLong()
            val idx = series.nearest(tAt).coerceAtLeast(0)
            val st = series.t[idx].coerceIn(from, to)
            val sx = (st - from).toFloat() / (to - from).coerceAtLeast(1L) * plotW
            val sy = top + plotH - (series.v[idx] - r.lo) / (r.hi - r.lo).coerceAtLeast(1e-6f) * plotH
            drawLine(
                palette.textDim.copy(alpha = 0.7f), Offset(sx, top), Offset(sx, bottom),
                strokeWidth = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 5.dp.toPx())),
            )
            drawCircle(palette.surface, radius = 6.dp.toPx(), center = Offset(sx, sy))
            drawCircle(color, radius = 4.dp.toPx(), center = Offset(sx, sy))
            val whenText = if (live) relativeLabel(to - series.t[idx]) else formatDuration(series.t[idx] - sessionStart)
            val text = "${formatNumber(series.v[idx])} $units  ·  $whenText"
            val m = measurer.measure(text, bubbleStyle)
            val padH = 10.dp.toPx()
            val padV = 6.dp.toPx()
            val bw = m.size.width + padH * 2
            val bh = m.size.height + padV * 2
            val bx = (sx - bw / 2f).coerceIn(0f, max(0f, plotW - bw))
            val by = top
            drawRoundRect(palette.surfaceHigh, Offset(bx, by), Size(bw, bh), CornerRadius(10.dp.toPx()))
            drawRoundRect(color.copy(alpha = 0.6f), Offset(bx, by), Size(bw, bh), CornerRadius(10.dp.toPx()), style = Stroke(1.dp.toPx()))
            drawText(m, topLeft = Offset(bx + padH, by + padV))
        }
    }
}

/** Small trend line for list rows. */
@Composable
fun Sparkline(series: Series, from: Long, to: Long, color: Color, holdToEnd: Boolean, modifier: Modifier = Modifier) {
    val palette = LocalPalette.current
    Canvas(modifier) {
        val r = series.range(to) ?: return@Canvas
        val pad = 2.dp.toPx()
        val pts = series.points(from, to, 0f, pad, size.width, size.height - pad * 2, r)
        clipRect(0f, 0f, size.width, size.height) {
            drawSeries(pts, color, pad, size.height, if (holdToEnd) size.width else null, 1.8.dp.toPx(), glow = false, fillAlpha = if (palette.dark) 0.26f else 0.18f)
        }
    }
}

private fun DrawScope.drawLabel(m: TextMeasurer, text: String, style: TextStyle, at: Offset, alignCenterY: Boolean) {
    val r = m.measure(text, style)
    val y = if (alignCenterY) at.y - r.size.height / 2f else at.y
    drawText(r, topLeft = Offset(at.x, y.coerceIn(0f, max(0f, size.height - r.size.height))))
}

private fun relativeLabel(agoMs: Long): String {
    val s = (agoMs / 1000).coerceAtLeast(0)
    return when {
        s < 1 -> "now"
        s < 60 -> "-${s}s"
        s % 60 == 0L -> "-${s / 60}m"
        else -> "-%d:%02d".format(s / 60, s % 60)
    }
}
