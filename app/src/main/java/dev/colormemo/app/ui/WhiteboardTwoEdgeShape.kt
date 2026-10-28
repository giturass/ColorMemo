package dev.colormemo.app.ui

import dev.colormemo.core.model.WhiteboardPoint
import kotlin.math.abs
import kotlin.math.sqrt

internal const val MIN_SHAPE_EDGE = 2.5f

/** The first edge stays provisional until the second segment completes the shape. */
internal data class WhiteboardTwoEdgeBase(val first: WhiteboardPoint, val second: WhiteboardPoint) {
    val points: List<WhiteboardPoint> get() = listOf(first, second)
}

internal data class WhiteboardTwoEdgeResult(
    val base: WhiteboardTwoEdgeBase?,
    val drawingPoints: List<WhiteboardPoint> = emptyList(),
)

internal class WhiteboardTwoEdgeDrag(
    private val shape: WhiteboardShape,
    private val before: WhiteboardTwoEdgeBase?,
    private val down: WhiteboardPoint,
    touchRadius: Float,
) {
    private val anchor = if (before == null) down else {
        before.points.minBy { distanceSquared(it, down) }
            .takeIf { distanceSquared(it, down) <= touchRadius * touchRadius }
    }
    private val opposite = before?.let { if (anchor == it.first) it.second else it.first }
    private var tip = anchor
    private var navigating = false
    val drawingTip: WhiteboardPoint? get() = tip.takeUnless { navigating }
    var previewPoints: List<WhiteboardPoint> = before?.points.orEmpty()
        private set

    fun update(point: WhiteboardPoint): List<WhiteboardPoint> {
        val start = anchor ?: return previewPoints
        if (navigating) return previewPoints
        // Keep the chosen endpoint fixed even when the finger grabs beside it.
        val end = whiteboardPoint(start.x + point.x - down.x, start.y + point.y - down.y)
        tip = end
        previewPoints = if (before == null) {
            if (start == end) emptyList() else listOf(start, end)
        } else if (start == end) before.points else listOf(opposite!!, start, end)
        return previewPoints
    }

    fun finish(): WhiteboardTwoEdgeResult {
        val start = anchor ?: return WhiteboardTwoEdgeResult(before)
        val end = tip ?: return WhiteboardTwoEdgeResult(before)
        if (navigating || !start.isFarEnoughFrom(end, MIN_SHAPE_EDGE)) return WhiteboardTwoEdgeResult(before)
        if (before == null) return WhiteboardTwoEdgeResult(WhiteboardTwoEdgeBase(start, end))
        val points = when (shape) {
            WhiteboardShape.TRIANGLE -> whiteboardTrianglePoints(opposite!!, start, end)
            WhiteboardShape.ANGLE -> whiteboardAnglePoints(opposite!!, start, end)
            else -> error("This shape does not use two edges")
        }
        return if (points.isEmpty()) WhiteboardTwoEdgeResult(before) else WhiteboardTwoEdgeResult(null, points)
    }

    fun startNavigation(): List<WhiteboardPoint> {
        navigating = true
        previewPoints = before?.points.orEmpty()
        return previewPoints
    }
}

internal fun whiteboardTrianglePoints(
    first: WhiteboardPoint,
    second: WhiteboardPoint,
    third: WhiteboardPoint,
): List<WhiteboardPoint> {
    val a = whiteboardPoint(first.x, first.y)
    val b = whiteboardPoint(second.x, second.y)
    val c = whiteboardPoint(third.x, third.y)
    val dx = b.x - a.x
    val dy = b.y - a.y
    val area = abs(dx * (c.y - a.y) - dy * (c.x - a.x))
    if (!a.isFarEnoughFrom(b, MIN_SHAPE_EDGE) || area < sqrt(dx * dx + dy * dy) * MIN_SHAPE_EDGE) return emptyList()
    return listOf(a, b, c, a)
}

private fun distanceSquared(a: WhiteboardPoint, b: WhiteboardPoint): Float =
    (a.x - b.x) * (a.x - b.x) + (a.y - b.y) * (a.y - b.y)
