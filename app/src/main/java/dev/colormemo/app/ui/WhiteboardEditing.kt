package dev.colormemo.app.ui

import dev.colormemo.core.model.Whiteboard
import dev.colormemo.core.model.WhiteboardPoint
import dev.colormemo.core.model.WhiteboardStroke
import kotlin.math.abs
import kotlin.math.round

internal fun whiteboardPoint(x: Float, y: Float): WhiteboardPoint = WhiteboardPoint(
    x = round(x.coerceIn(0f, Whiteboard.CANVAS_SIZE) * 2f) / 2f,
    y = round(y.coerceIn(0f, Whiteboard.CANVAS_SIZE) * 2f) / 2f,
)

internal fun WhiteboardPoint.isFarEnoughFrom(other: WhiteboardPoint, distance: Float): Boolean {
    val dx = x - other.x
    val dy = y - other.y
    return dx * dx + dy * dy >= distance * distance
}

/** Hit-test the whole eraser movement, so a fast swipe cannot skip a thin stroke. */
internal fun Whiteboard.eraseBetween(
    start: WhiteboardPoint,
    end: WhiteboardPoint,
    radius: Float,
): Whiteboard {
    val remaining = strokes.filterNot { it.intersectsEraser(start, end, radius) }
    return if (remaining.size == strokes.size) this else copy(strokes = remaining)
}

private fun WhiteboardStroke.intersectsEraser(
    start: WhiteboardPoint,
    end: WhiteboardPoint,
    radius: Float,
): Boolean {
    if (points.isEmpty()) return false
    val reach = radius + width / 2f
    val distanceSquared = reach * reach
    if (points.size == 1) return pointSegmentDistanceSquared(points[0], start, end) <= distanceSquared
    for (index in 1 until points.size) {
        if (segmentDistanceSquared(points[index - 1], points[index], start, end) <= distanceSquared) return true
    }
    return false
}

private fun segmentDistanceSquared(
    a: WhiteboardPoint,
    b: WhiteboardPoint,
    c: WhiteboardPoint,
    d: WhiteboardPoint,
): Float {
    val abX = b.x - a.x
    val abY = b.y - a.y
    val cdX = d.x - c.x
    val cdY = d.y - c.y
    val cross = abX * cdY - abY * cdX
    if (abs(cross) > 0.0001f) {
        val acX = c.x - a.x
        val acY = c.y - a.y
        val alongAb = (acX * cdY - acY * cdX) / cross
        val alongCd = (acX * abY - acY * abX) / cross
        if (alongAb in 0f..1f && alongCd in 0f..1f) return 0f
    }
    return minOf(
        pointSegmentDistanceSquared(a, c, d),
        pointSegmentDistanceSquared(b, c, d),
        pointSegmentDistanceSquared(c, a, b),
        pointSegmentDistanceSquared(d, a, b),
    )
}

private fun pointSegmentDistanceSquared(point: WhiteboardPoint, a: WhiteboardPoint, b: WhiteboardPoint): Float {
    val dx = b.x - a.x
    val dy = b.y - a.y
    val lengthSquared = dx * dx + dy * dy
    val fraction = if (lengthSquared == 0f) 0f else
        (((point.x - a.x) * dx + (point.y - a.y) * dy) / lengthSquared).coerceIn(0f, 1f)
    val deltaX = point.x - (a.x + fraction * dx)
    val deltaY = point.y - (a.y + fraction * dy)
    return deltaX * deltaX + deltaY * deltaY
}
