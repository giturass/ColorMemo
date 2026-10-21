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
import androidx.compose.ui.geometry.Size
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
    val isRuler = instrument == WhiteboardInstrument.RULER
    Column(Modifier.padding(horizontal = 12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(when (instrument) {
                WhiteboardInstrument.COMPASS -> "圆规 · 半径 ${state.radius.roundToInt()}"
                WhiteboardInstrument.PROTRACTOR -> "量角器 · ${String.format(Locale.ROOT, "%.1f°", state.angleDegrees)}"
                WhiteboardInstrument.RULER -> "直尺 · 长度 ${state.rulerLength.roundToInt()} · ${String.format(Locale.ROOT, "%.1f°", state.baselineDegrees)}"
            },
                Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
            TextButton(onClick = onDraw, enabled = enabled) {
                Text(when (instrument) {
                    WhiteboardInstrument.COMPASS -> "画圆"
                    WhiteboardInstrument.PROTRACTOR -> "绘制角"
                    WhiteboardInstrument.RULER -> "画直线"
                })
            }
            TextButton(onClick = onClose, enabled = enabled) { Text("收起") }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val sizeLabel = when (instrument) {
                WhiteboardInstrument.COMPASS -> "半径"
                WhiteboardInstrument.PROTRACTOR -> "大小"
                WhiteboardInstrument.RULER -> "长度"
            }
            val sizeFactor = if (isRuler) RULER_LENGTH_FACTOR else 1f
            Text(sizeLabel, style = MaterialTheme.typography.bodySmall)
            Slider(value = state.radius * sizeFactor, onValueChange = { onChange(state.resized(it / sizeFactor)) },
                valueRange = (MIN_INSTRUMENT_RADIUS * sizeFactor)..(state.maximumRadius * sizeFactor),
                enabled = enabled && state.maximumRadius > MIN_INSTRUMENT_RADIUS,
                modifier = Modifier.weight(1f).semantics { contentDescription = "${instrument.displayName}$sizeLabel" })
        }
        if (instrument != WhiteboardInstrument.PROTRACTOR) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (isRuler) "方向" else "起笔方向", style = MaterialTheme.typography.bodySmall)
                Slider(value = state.baselineDegrees, onValueChange = { onChange(state.copy(baselineDegrees = it)) },
                    valueRange = 0f..360f, enabled = enabled,
                    modifier = Modifier.weight(1f).semantics { contentDescription = if (isRuler) "直尺方向" else "圆规起笔方向" })
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("角度", style = MaterialTheme.typography.bodySmall)
                Slider(value = state.angleDegrees, onValueChange = { onChange(state.copy(angleDegrees = it)) },
                    valueRange = 0f..180f, enabled = enabled,
                    modifier = Modifier.weight(1f).semantics { contentDescription = "量角器测量角度" })
            }
        }
        Text(when (instrument) {
            WhiteboardInstrument.COMPASS -> "拖动圆心移动，滑块调半径和起笔方向；拖动笔尖旋转画弧，松手结束，也可点击「画圆」。"
            WhiteboardInstrument.PROTRACTOR -> "拖动中心移动，下方圆点旋转基线，圆弧上的圆点调整测量边。"
            WhiteboardInstrument.RULER -> "拖动尺身移动，右侧圆点旋转，沿蓝色刻度边拖动画直线；也可点击「画直线」绘制整条尺边。刻度为画布单位。"
        },
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
        } else if (instrument == WhiteboardInstrument.RULER) {
            drawRect(tint, point(0.08f, 0.25f), Size(size.width * 0.84f, size.height * 0.5f),
                style = Stroke(width))
            for (index in 1..5) {
                val x = 0.08f + index * 0.14f
                drawLine(tint, point(x, 0.25f), point(x, if (index % 2 == 0) 0.52f else 0.42f), width)
            }
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
        val handleRadius = if (instrument == WhiteboardInstrument.RULER)
            minOf(8.dp.toPx(), state.rulerHalfWidth * transform.scale * 0.65f) else 8.dp.toPx()
        drawCircle(Color.White, handleRadius, position)
        drawCircle(color, handleRadius, position, style = Stroke(minOf(2.dp.toPx(), handleRadius * 0.3f)))
        drawCircle(color, minOf(2.dp.toPx(), handleRadius * 0.25f), position)
    }
    if (instrument == WhiteboardInstrument.COMPASS) {
        drawCircle(blue.copy(alpha = 0.7f), radius, center, style = Stroke(width, pathEffect = dashed))
        drawLine(grey, center, screen(state.handlePoint(InstrumentHandle.PEN)), width)
        handle(InstrumentHandle.CENTER)
        handle(InstrumentHandle.PEN)
        return
    }
    if (instrument == WhiteboardInstrument.RULER) {
        fun ruler(distance: Float, depth: Float = 0f) = screen(state.rulerPoint(distance, depth))
        val length = state.rulerLength
        val halfWidth = state.rulerHalfWidth
        val corners = listOf(ruler(0f), ruler(length), ruler(length, halfWidth * 2f), ruler(0f, halfWidth * 2f))
        val body = Path().apply {
            moveTo(corners.first().x, corners.first().y)
            corners.drop(1).forEach { lineTo(it.x, it.y) }
            close()
        }
        drawPath(body, Color(0xFF90CAF9).copy(alpha = 0.23f))
        for (index in 1..3) {
            drawLine(grey.copy(alpha = 0.8f), corners[index], corners[(index + 1) % 4], width)
        }
        // Leave the ink visible while drawing directly along the marked edge.
        drawLine(blue.copy(alpha = 0.65f), ruler(0f), ruler(length), width, pathEffect = dashed)
        // Tick spacing is always in board units; only density changes with zoom.
        val step = listOf(10, 20, 50, 100, 200, 500, 1000).firstOrNull { it * transform.scale >= 6.dp.toPx() } ?: 1000
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.DKGRAY
            textAlign = Paint.Align.CENTER
            textSize = minOf(12.dp.toPx(), halfWidth * transform.scale * 0.6f)
        }
        for (distance in 0..length.toInt() step step) {
            val major = distance % (step * 5) == 0
            drawLine(grey, ruler(distance.toFloat()), ruler(distance.toFloat(), halfWidth * if (major) 0.55f else 0.3f), width)
        }
        if (paint.textSize >= 8.dp.toPx()) {
            drawIntoCanvas { canvas ->
                for (distance in 0..length.toInt() step step * 5) {
                    val point = ruler(distance.toFloat(), halfWidth * 1.65f)
                    val rotation = -state.baselineDegrees + if (state.baselineDegrees > 90f && state.baselineDegrees < 270f) 180f else 0f
                    canvas.nativeCanvas.save()
                    canvas.nativeCanvas.rotate(rotation, point.x, point.y)
                    canvas.nativeCanvas.drawText(distance.toString(), point.x, point.y + paint.textSize / 3f, paint)
                    canvas.nativeCanvas.restore()
                }
            }
        }
        handle(InstrumentHandle.RULER_ROTATION, grey)
        handle(InstrumentHandle.CENTER)
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
