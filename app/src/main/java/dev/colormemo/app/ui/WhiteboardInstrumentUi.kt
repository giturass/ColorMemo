package dev.colormemo.app.ui

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlin.math.roundToInt

@Composable
internal fun WhiteboardInstrumentControls(
    instrument: WhiteboardInstrument,
    state: WhiteboardInstrumentState,
    enabled: Boolean,
    onChange: (WhiteboardInstrumentState) -> Unit,
    onDraw: () -> Unit,
    onClose: () -> Unit,
) {
    Column(Modifier.padding(horizontal = 12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (instrument == WhiteboardInstrument.COMPASS) "圆规 · 半径 ${state.radius.roundToInt()}"
                else "量角器 · ${String.format(Locale.ROOT, "%.1f°", state.angleDegrees)}",
                Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
            TextButton(onClick = onDraw, enabled = enabled) {
                Text(if (instrument == WhiteboardInstrument.COMPASS) "画圆" else "绘制角")
            }
            TextButton(onClick = onClose, enabled = enabled) { Text("收起") }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (instrument == WhiteboardInstrument.COMPASS) "半径" else "大小", style = MaterialTheme.typography.bodySmall)
            Slider(value = state.radius, onValueChange = { onChange(state.resized(it)) },
                valueRange = MIN_INSTRUMENT_RADIUS..state.maximumRadius,
                enabled = enabled && state.maximumRadius > MIN_INSTRUMENT_RADIUS,
                modifier = Modifier.weight(1f).semantics { contentDescription = "${instrument.displayName}半径" })
        }
        if (instrument == WhiteboardInstrument.PROTRACTOR) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("角度", style = MaterialTheme.typography.bodySmall)
                Slider(value = state.angleDegrees, onValueChange = { onChange(state.copy(angleDegrees = it)) },
                    valueRange = 0f..180f, enabled = enabled,
                    modifier = Modifier.weight(1f).semantics { contentDescription = "量角器测量角度" })
            }
        }
        Text(if (instrument == WhiteboardInstrument.COMPASS) "拖动圆心移动，拖动笔尖调节半径，点击「画圆」完成。"
            else "拖动中心移动，下方圆点旋转基线，圆弧上的圆点调整测量边。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun WhiteboardInstrumentIcon(instrument: WhiteboardInstrument) {
    val tint = LocalContentColor.current
    Canvas(Modifier.size(24.dp)) {
        fun point(x: Float, y: Float) = Offset(size.width * x, size.height * y)
        val width = 1.8.dp.toPx()
        if (instrument == WhiteboardInstrument.COMPASS) {
            drawLine(tint, point(0.5f, 0.2f), point(0.2f, 0.87f), width, StrokeCap.Round)
            drawLine(tint, point(0.5f, 0.2f), point(0.8f, 0.87f), width, StrokeCap.Round)
            drawLine(tint, point(0.32f, 0.6f), point(0.68f, 0.6f), width, StrokeCap.Round)
            drawCircle(tint, size.width * 0.08f, point(0.5f, 0.18f), style = Stroke(width))
        } else {
            val path = Path().apply {
                moveTo(size.width * 0.1f, size.height * 0.8f)
                cubicTo(size.width * 0.1f, -size.height * 0.02f, size.width * 0.9f, -size.height * 0.02f,
                    size.width * 0.9f, size.height * 0.8f)
                close()
            }
            drawPath(path, tint, style = Stroke(width))
            drawLine(tint, point(0.5f, 0.2f), point(0.5f, 0.37f), width)
            drawLine(tint, point(0.23f, 0.4f), point(0.34f, 0.52f), width)
            drawLine(tint, point(0.77f, 0.4f), point(0.66f, 0.52f), width)
        }
    }
}

internal fun DrawScope.drawWhiteboardInstrument(
    instrument: WhiteboardInstrument,
    state: WhiteboardInstrumentState,
    transform: WhiteboardTransform,
) {
    fun screen(point: dev.colormemo.core.model.WhiteboardPoint) =
        Offset(transform.originX + point.x * transform.scale, transform.originY + point.y * transform.scale)
    fun radial(degrees: Float, distance: Float = state.radius) = screen(state.pointAt(degrees, distance))
    val center = screen(state.center)
    val radius = state.radius * transform.scale
    val blue = Color(0xFF1967D2)
    val grey = Color(0xFF5F6368)
    val width = 1.5.dp.toPx()
    val dashed = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx()))
    fun handle(which: InstrumentHandle, color: Color = blue) {
        val position = screen(state.handlePoint(which))
        drawCircle(Color.White, 8.dp.toPx(), position)
        drawCircle(color, 8.dp.toPx(), position, style = Stroke(2.dp.toPx()))
        drawCircle(color, 2.dp.toPx(), position)
    }
    if (instrument == WhiteboardInstrument.COMPASS) {
        drawCircle(blue.copy(alpha = 0.7f), radius, center, style = Stroke(width, pathEffect = dashed))
        drawLine(grey, center, screen(state.handlePoint(InstrumentHandle.RADIUS)), width)
        handle(InstrumentHandle.CENTER)
        handle(InstrumentHandle.RADIUS)
        return
    }
    val arc = Path().apply {
        val first = radial(state.baselineDegrees)
        moveTo(first.x, first.y)
        for (angle in 1..180 step 2) {
            val point = radial(state.baselineDegrees + angle)
            lineTo(point.x, point.y)
        }
        val last = radial(state.baselineDegrees + 180f)
        lineTo(last.x, last.y)
    }
    val body = Path().apply { addPath(arc); close() }
    drawPath(body, Color(0xFFFFCC80).copy(alpha = 0.23f))
    drawPath(body, grey.copy(alpha = 0.8f), style = Stroke(width))
    for (angle in 0..180 step 5) {
        val fraction = if (angle % 30 == 0) 0.12f else if (angle % 10 == 0) 0.08f else 0.04f
        drawLine(grey, radial(state.baselineDegrees + angle), radial(state.baselineDegrees + angle, state.radius * (1f - fraction)), width)
    }
    if (radius >= 56.dp.toPx()) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.DKGRAY
            textAlign = Paint.Align.CENTER
            textSize = minOf(12.dp.toPx(), radius * 0.11f)
        }
        drawIntoCanvas { canvas ->
            for (angle in 0..180 step 30) {
                val outer = radial(state.baselineDegrees + angle, state.radius * 0.78f)
                val inner = radial(state.baselineDegrees + angle, state.radius * 0.61f)
                canvas.nativeCanvas.drawText(angle.toString(), outer.x, outer.y + paint.textSize / 3f, paint)
                canvas.nativeCanvas.drawText((180 - angle).toString(), inner.x, inner.y + paint.textSize / 3f, paint)
            }
        }
    }
    drawLine(blue, center, screen(state.handlePoint(InstrumentHandle.MEASUREMENT)), 2.dp.toPx())
    drawLine(grey, center, screen(state.handlePoint(InstrumentHandle.BASELINE)), width, pathEffect = dashed)
    handle(InstrumentHandle.BASELINE, grey)
    handle(InstrumentHandle.MEASUREMENT)
    handle(InstrumentHandle.CENTER)
}
