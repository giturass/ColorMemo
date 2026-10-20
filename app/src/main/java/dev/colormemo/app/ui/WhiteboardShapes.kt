package dev.colormemo.app.ui

import dev.colormemo.core.model.WhiteboardPoint
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

internal enum class WhiteboardShape(val displayName: String) {
    LINE("直线"),
    ARROW("箭头"),
    RECTANGLE("矩形"),
    CIRCLE("圆形"),
    ELLIPSE("椭圆"),
    TRIANGLE("三角形"),
    DIAMOND("菱形"),
}

/** Templates become one ordinary stroke, shared by editing, persistence and SVG export. */
internal fun WhiteboardShape.points(
    start: WhiteboardPoint,
    end: WhiteboardPoint,
    strokeWidth: Float,
): List<WhiteboardPoint> {
    val from = whiteboardPoint(start.x, start.y)
    val to = whiteboardPoint(end.x, end.y)
    if (from == to) return emptyList()
    val dx = to.x - from.x
    val dy = to.y - from.y
    if (this == WhiteboardShape.LINE) return listOf(from, to)
    if (this == WhiteboardShape.ARROW) {
        val length = sqrt(dx * dx + dy * dy)
        val head = minOf(length * 0.4f, maxOf(strokeWidth * 3f, minOf(length * 0.2f, 80f)))
        val alongX = dx / length * head
        val alongY = dy / length * head
        return listOf(
            from, to,
            whiteboardPoint(to.x - alongX - alongY * 0.55f, to.y - alongY + alongX * 0.55f),
            to,
            whiteboardPoint(to.x - alongX + alongY * 0.55f, to.y - alongY - alongX * 0.55f),
        )
    }
    if (dx == 0f || dy == 0f) return emptyList()
    // A circle fits a square anchored at the initial touch, in any drag direction.
    val corner = if (this == WhiteboardShape.CIRCLE) {
        val side = minOf(abs(dx), abs(dy))
        WhiteboardPoint(from.x + if (dx > 0f) side else -side, from.y + if (dy > 0f) side else -side)
    } else to
    val left = minOf(from.x, corner.x)
    val right = maxOf(from.x, corner.x)
    val top = minOf(from.y, corner.y)
    val bottom = maxOf(from.y, corner.y)
    val centerX = (left + right) / 2f
    val centerY = (top + bottom) / 2f
    val outline = when (this) {
        WhiteboardShape.RECTANGLE -> listOf(
            WhiteboardPoint(left, top), WhiteboardPoint(right, top),
            WhiteboardPoint(right, bottom), WhiteboardPoint(left, bottom),
        )
        WhiteboardShape.CIRCLE, WhiteboardShape.ELLIPSE -> List(96) { index ->
            val angle = 2.0 * PI * index / 96
            whiteboardPoint(
                centerX + (right - left) / 2f * cos(angle).toFloat(),
                centerY + (bottom - top) / 2f * sin(angle).toFloat(),
            )
        }
        WhiteboardShape.TRIANGLE -> listOf(
            whiteboardPoint(centerX, top), WhiteboardPoint(right, bottom), WhiteboardPoint(left, bottom),
        )
        WhiteboardShape.DIAMOND -> listOf(
            whiteboardPoint(centerX, top), whiteboardPoint(right, centerY),
            whiteboardPoint(centerX, bottom), whiteboardPoint(left, centerY),
        )
        WhiteboardShape.LINE, WhiteboardShape.ARROW -> error("Open shapes handled above")
    }
    return outline + outline.first()
}
