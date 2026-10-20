package dev.colormemo.app.ui

import dev.colormemo.core.model.Whiteboard
import dev.colormemo.core.model.WhiteboardBrush
import dev.colormemo.core.model.WhiteboardPoint
import dev.colormemo.core.model.WhiteboardStroke
import kotlin.math.sqrt

/** Split ink at the swept eraser boundary, including crossings between sparse samples. */
internal fun Whiteboard.eraseBetween(
    start: WhiteboardPoint,
    end: WhiteboardPoint,
    radius: Float,
    onLimitReached: () -> Unit = {},
): Whiteboard {
    val sweep = EraserSweep(start, end)
    val remaining = strokes.flatMap { it.eraseParts(sweep, radius) }
    if (remaining == strokes) return this
    // A local cut can add fragments/points. Never discard distant ink to fit the format.
    if (remaining.size > Whiteboard.MAX_STROKES || remaining.sumOf { it.points.size } > Whiteboard.MAX_POINTS) {
        onLimitReached()
        return this
    }
    return copy(strokes = remaining)
}

private fun WhiteboardStroke.eraseParts(sweep: EraserSweep, radius: Float): List<WhiteboardStroke> {
    if (points.isEmpty()) return listOf(this)
    // Include the ink footprint, including the corners of square marker tips.
    val reach = radius.toDouble() + width / 2.0 * if (brush == WhiteboardBrush.MARKER) sqrt(2.0) else 1.0
    if (points.size == 1) return if (sweep.interval(points[0], points[0], reach) == null) listOf(this) else emptyList()

    val fragments = mutableListOf<WhiteboardStroke>()
    var fragment = mutableListOf<WhiteboardPoint>()
    var changed = false
    fun append(point: WhiteboardPoint) {
        if (fragment.lastOrNull() != point) fragment.add(point)
    }
    fun finishFragment() {
        if (fragment.isNotEmpty()) fragments.add(copy(points = fragment.toList()))
        fragment = mutableListOf()
    }
    for (index in 1 until points.size) {
        val a = points[index - 1]
        val b = points[index]
        val cut = sweep.interval(a, b, reach)
        if (cut == null) {
            append(a)
            append(b)
        } else {
            changed = true
            if (cut.start > 0.0) {
                append(a)
                append(interpolate(a, b, cut.start))
            }
            finishFragment()
            if (cut.end < 1.0) {
                append(interpolate(a, b, cut.end))
                append(b)
            }
        }
    }
    if (!changed) return listOf(this)
    finishFragment()
    // A closed outline's original start is still connected when a different edge is cut.
    if (points.first() == points.last() && fragments.size > 1 &&
        fragments.first().points.first() == points.first() && fragments.last().points.last() == points.last()
    ) {
        fragments[0] = copy(points = fragments.last().points + fragments.first().points.drop(1))
        fragments.removeAt(fragments.lastIndex)
    }
    return fragments
}

private fun interpolate(a: WhiteboardPoint, b: WhiteboardPoint, fraction: Double) = WhiteboardPoint(
    (a.x + (b.x.toDouble() - a.x) * fraction).toFloat(),
    (a.y + (b.y.toDouble() - a.y) * fraction).toFloat(),
)

private data class ErasedInterval(val start: Double, val end: Double)

/** A capsule is the union of two end circles and the rectangle between them. */
private class EraserSweep(private val start: WhiteboardPoint, private val end: WhiteboardPoint) {
    private val dx = end.x.toDouble() - start.x
    private val dy = end.y.toDouble() - start.y
    private val lengthSquared = dx * dx + dy * dy
    private val length = sqrt(lengthSquared)

    fun interval(a: WhiteboardPoint, b: WhiteboardPoint, reach: Double): ErasedInterval? {
        if (maxOf(a.x, b.x) < minOf(start.x, end.x) - reach ||
            minOf(a.x, b.x) > maxOf(start.x, end.x) + reach ||
            maxOf(a.y, b.y) < minOf(start.y, end.y) - reach ||
            minOf(a.y, b.y) > maxOf(start.y, end.y) + reach
        ) return null
        var cut = circleInterval(a, b, start, reach)
        if (lengthSquared > 0.0) {
            cut = union(cut, circleInterval(a, b, end, reach))
            val ax = a.x.toDouble() - start.x
            val ay = a.y.toDouble() - start.y
            val abX = b.x.toDouble() - a.x
            val abY = b.y.toDouble() - a.y
            val along = linearInterval(ax * dx + ay * dy, abX * dx + abY * dy, 0.0, lengthSquared)
            val across = linearInterval(ax * dy - ay * dx, abX * dy - abY * dx, -reach * length, reach * length)
            if (along != null && across != null) {
                val from = maxOf(along.start, across.start)
                val to = minOf(along.end, across.end)
                if (from <= to) cut = union(cut, ErasedInterval(from, to))
            }
        }
        // Ignore tangency and sub-pixel float noise instead of creating tiny fragments.
        return cut?.takeIf { it.end - it.start > 0.000001 }
    }
}

private fun circleInterval(a: WhiteboardPoint, b: WhiteboardPoint, center: WhiteboardPoint, radius: Double): ErasedInterval? {
    val dx = b.x.toDouble() - a.x
    val dy = b.y.toDouble() - a.y
    val ax = a.x.toDouble() - center.x
    val ay = a.y.toDouble() - center.y
    val lengthSquared = dx * dx + dy * dy
    val outside = ax * ax + ay * ay - radius * radius
    if (lengthSquared == 0.0) return if (outside <= 0.0) ErasedInterval(0.0, 1.0) else null
    val projection = ax * dx + ay * dy
    val discriminant = projection * projection - lengthSquared * outside
    if (discriminant < 0.0) return null
    val root = sqrt(discriminant)
    val from = maxOf(0.0, (-projection - root) / lengthSquared)
    val to = minOf(1.0, (-projection + root) / lengthSquared)
    return if (from <= to) ErasedInterval(from, to) else null
}

private fun linearInterval(origin: Double, delta: Double, low: Double, high: Double): ErasedInterval? {
    if (delta == 0.0) return if (origin in low..high) ErasedInterval(0.0, 1.0) else null
    val first = (low - origin) / delta
    val second = (high - origin) / delta
    val from = maxOf(0.0, minOf(first, second))
    val to = minOf(1.0, maxOf(first, second))
    return if (from <= to) ErasedInterval(from, to) else null
}

private fun union(a: ErasedInterval?, b: ErasedInterval?): ErasedInterval? = when {
    a == null -> b
    b == null -> a
    else -> ErasedInterval(minOf(a.start, b.start), maxOf(a.end, b.end))
}
