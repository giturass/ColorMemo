package dev.colormemo.app.ui

import dev.colormemo.core.model.Whiteboard
import dev.colormemo.core.model.WhiteboardPoint
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

internal enum class WhiteboardShape(val displayName: String, val isArrow: Boolean = false) {
    LINE("直线"),
    ARROW("箭头", true),
    CURVED_ARROW("曲箭头", true),
    DOUBLE_ARROW("双箭头", true),
    RECTANGLE("矩形"),
    SQUARE("正方形"),
    CIRCLE("圆形"),
    ELLIPSE("椭圆"),
    TRIANGLE("三角形"),
    TRAPEZOID("梯形"),
    PARALLELOGRAM("平行四边形"),
    RHOMBUS("菱形"),
    PENTAGON("五边形"),
    HEXAGON("六边形"),
    CYLINDER("圆柱"),
    CONE("圆锥"),
    PYRAMID("四棱锥"),
    TRIANGULAR_PYRAMID("三棱锥"),
    CUBE("立方体"),
    SPHERE("球体"),
    ANGLE("角"),
    COORDINATE_AXES("坐标系");

    val requiresSecondEdge: Boolean get() = this == TRIANGLE || this == ANGLE
}

/** Templates become one ordinary stroke, shared by editing, persistence and SVG export. */
internal fun WhiteboardShape.points(
    start: WhiteboardPoint,
    end: WhiteboardPoint,
    strokeWidth: Float,
    third: WhiteboardPoint? = null,
): List<WhiteboardPoint> {
    val from = whiteboardPoint(start.x, start.y)
    val to = whiteboardPoint(end.x, end.y)
    if (this == WhiteboardShape.TRIANGLE) return third?.let { whiteboardTrianglePoints(from, to, it) }.orEmpty()
    if (this == WhiteboardShape.ANGLE) return third?.let { whiteboardAnglePoints(from, to, it) }.orEmpty()
    if (from == to) return emptyList()
    val dx = to.x - from.x
    val dy = to.y - from.y
    if (this == WhiteboardShape.LINE) return listOf(from, to)
    if (isArrow) {
        val length = sqrt(dx * dx + dy * dy)
        val head = minOf(length * 0.4f, maxOf(strokeWidth * 3f, minOf(length * 0.2f, 80f)))
        if (this == WhiteboardShape.CURVED_ARROW) {
            val control = whiteboardPoint((from.x + to.x) / 2f + dy * 0.4f, (from.y + to.y) / 2f - dx * 0.4f)
            val curve = List(49) { index ->
                val t = index / 48f
                val u = 1f - t
                whiteboardPoint(u * u * from.x + 2f * u * t * control.x + t * t * to.x,
                    u * u * from.y + 2f * u * t * control.y + t * t * to.y)
            }
            return curve + arrowHead(to, to.x - control.x, to.y - control.y, head)
        }
        val startHead = if (this == WhiteboardShape.DOUBLE_ARROW) arrowHead(from, -dx, -dy, head) else emptyList()
        return startHead + listOf(from, to) + arrowHead(to, dx, dy, head)
    }
    if (dx == 0f || dy == 0f) return emptyList()
    // Equal-sided templates stay anchored at the initial touch in every drag direction.
    val corner = if (this in listOf(WhiteboardShape.CIRCLE, WhiteboardShape.SQUARE, WhiteboardShape.CUBE, WhiteboardShape.SPHERE)) {
        val side = minOf(abs(dx), abs(dy))
        WhiteboardPoint(from.x + if (dx > 0f) side else -side, from.y + if (dy > 0f) side else -side)
    } else to
    val left = minOf(from.x, corner.x)
    val right = maxOf(from.x, corner.x)
    val top = minOf(from.y, corner.y)
    val bottom = maxOf(from.y, corner.y)
    val centerX = (left + right) / 2f
    val centerY = (top + bottom) / 2f
    val width = right - left
    val height = bottom - top
    if (this == WhiteboardShape.CUBE) {
        val depth = width * 0.28f
        val frontTopLeft = whiteboardPoint(left, top + depth)
        val frontTopRight = whiteboardPoint(right - depth, top + depth)
        val frontBottomRight = whiteboardPoint(right - depth, bottom)
        val backTopRight = whiteboardPoint(right, top)
        return listOf(frontTopLeft, whiteboardPoint(left + depth, top), backTopRight,
            whiteboardPoint(right, bottom - depth), frontBottomRight, WhiteboardPoint(left, bottom),
            frontTopLeft, frontTopRight, backTopRight, frontTopRight, frontBottomRight)
    }
    if (this == WhiteboardShape.SPHERE) {
        val radius = width / 2f
        // Reach each meridian along the surface rather than adding an interior connector.
        return ellipsePoints(centerX, centerY, radius, radius, -PI / 2, 2 * PI, 64) +
            ellipsePoints(centerX, centerY, radius * 0.32f, radius, -PI / 2, 2 * PI, 48).drop(1) +
            ellipsePoints(centerX, centerY, radius, radius, -PI / 2, -PI / 2, 16).drop(1) +
            ellipsePoints(centerX, centerY, radius, radius * 0.25f, PI, 2 * PI, 48).drop(1)
    }
    if (this == WhiteboardShape.CYLINDER) {
        val rimRadius = minOf(height * 0.16f, width * 0.2f)
        // One continuous path: top rim, left side, visible lower rim, right side.
        return ellipsePoints(centerX, top + rimRadius, width / 2f, rimRadius, PI, 2 * PI, 48) +
            ellipsePoints(centerX, bottom - rimRadius, width / 2f, rimRadius, PI, -PI, 24) +
            whiteboardPoint(right, top + rimRadius)
    }
    if (this == WhiteboardShape.CONE) {
        val rimRadius = minOf(height * 0.16f, width * 0.2f)
        val baseY = bottom - rimRadius
        return listOf(whiteboardPoint(left, baseY), whiteboardPoint(centerX, top)) +
            ellipsePoints(centerX, baseY, width / 2f, rimRadius, 0.0, 2 * PI, 48)
    }
    if (this == WhiteboardShape.TRIANGULAR_PYRAMID) {
        val apex = whiteboardPoint(centerX, top)
        val leftBase = whiteboardPoint(left, top + height * 0.7f)
        val frontBase = whiteboardPoint(left + width * 0.6f, bottom)
        val rightBase = whiteboardPoint(right, top + height * 0.65f)
        return listOf(apex, leftBase, frontBase, rightBase, leftBase, apex, rightBase, apex, frontBase)
    }
    if (this == WhiteboardShape.PYRAMID) {
        val apex = whiteboardPoint(centerX, top)
        val baseLeft = whiteboardPoint(left, top + height * 0.75f)
        val baseFront = whiteboardPoint(left + width * 0.4f, bottom)
        val baseRight = whiteboardPoint(right, top + height * 0.8f)
        val baseBack = whiteboardPoint(left + width * 0.6f, top + height * 0.55f)
        return listOf(baseLeft, baseBack, baseRight, baseFront, baseLeft,
            apex, baseBack, baseRight, apex, baseFront)
    }
    if (this == WhiteboardShape.COORDINATE_AXES) {
        val origin = whiteboardPoint(centerX, centerY)
        val xTip = whiteboardPoint(right, centerY)
        val yStart = whiteboardPoint(centerX, bottom)
        val tick = minOf(width, height) * 0.025f
        return coordinateAxis(whiteboardPoint(left, centerY), xTip, tick, strokeWidth) +
            listOf(xTip, origin, yStart) +
            coordinateAxis(yStart, whiteboardPoint(centerX, top), tick, strokeWidth).drop(1)
    }
    val outline = when (this) {
        WhiteboardShape.RECTANGLE, WhiteboardShape.SQUARE -> listOf(
            WhiteboardPoint(left, top), WhiteboardPoint(right, top),
            WhiteboardPoint(right, bottom), WhiteboardPoint(left, bottom),
        )
        WhiteboardShape.CIRCLE, WhiteboardShape.ELLIPSE ->
            ellipsePoints(centerX, centerY, width / 2f, height / 2f, 0.0, 2 * PI, 96).dropLast(1)
        WhiteboardShape.PARALLELOGRAM -> listOf(
            whiteboardPoint(left + width * 0.25f, top), whiteboardPoint(right, top),
            whiteboardPoint(right - width * 0.25f, bottom), whiteboardPoint(left, bottom),
        )
        WhiteboardShape.TRAPEZOID -> listOf(
            whiteboardPoint(left + width * 0.25f, top), whiteboardPoint(right - width * 0.25f, top),
            WhiteboardPoint(right, bottom), WhiteboardPoint(left, bottom),
        )
        WhiteboardShape.RHOMBUS -> listOf(
            WhiteboardPoint(centerX, top), WhiteboardPoint(right, centerY),
            WhiteboardPoint(centerX, bottom), WhiteboardPoint(left, centerY),
        )
        WhiteboardShape.PENTAGON -> {
            // A regular pentagon's outline, scaled to the chosen drag bounds.
            val shoulder = height * (3f - sqrt(5f)) / 2f
            val inset = width * (3f - sqrt(5f)) / 4f
            listOf(WhiteboardPoint(centerX, top), whiteboardPoint(right, top + shoulder),
                whiteboardPoint(right - inset, bottom), whiteboardPoint(left + inset, bottom),
                whiteboardPoint(left, top + shoulder))
        }
        WhiteboardShape.HEXAGON -> listOf(
            whiteboardPoint(left + width * 0.25f, top), whiteboardPoint(right - width * 0.25f, top),
            whiteboardPoint(right, centerY), whiteboardPoint(right - width * 0.25f, bottom),
            whiteboardPoint(left + width * 0.25f, bottom), whiteboardPoint(left, centerY),
        )
        else -> error("Open and composite shapes handled above")
    }
    return outline + outline.first()
}

/** Two chosen segments meet at the vertex; the marker follows their smaller angle. */
internal fun whiteboardAnglePoints(
    first: WhiteboardPoint,
    vertex: WhiteboardPoint,
    second: WhiteboardPoint,
): List<WhiteboardPoint> {
    val a = whiteboardPoint(first.x, first.y)
    val v = whiteboardPoint(vertex.x, vertex.y)
    val b = whiteboardPoint(second.x, second.y)
    if (!a.isFarEnoughFrom(v, MIN_SHAPE_EDGE) || !b.isFarEnoughFrom(v, MIN_SHAPE_EDGE)) return emptyList()
    val firstDx = (a.x - v.x).toDouble()
    val firstDy = (a.y - v.y).toDouble()
    val secondDx = (b.x - v.x).toDouble()
    val secondDy = (b.y - v.y).toDouble()
    val firstAngle = atan2(firstDy, firstDx)
    val secondAngle = atan2(secondDy, secondDx)
    val sweep = atan2(sin(firstAngle - secondAngle), cos(firstAngle - secondAngle))
    val edges = listOf(a, v, b)
    if (abs(sweep) < 0.00001) return edges
    var radius = (minOf(sqrt(firstDx * firstDx + firstDy * firstDy),
        sqrt(secondDx * secondDx + secondDy * secondDy)) * 0.22).toFloat()
    val segments = ceil(abs(sweep) / PI * 32).toInt().coerceAtLeast(1)
    val directions = List(segments + 1) { index ->
        val angle = secondAngle + sweep * index / segments
        WhiteboardPoint(cos(angle).toFloat(), sin(angle).toFloat())
    }
    // Shrink the marker near an edge instead of flattening it against the board.
    directions.forEach { direction ->
        if (direction.x > 0.00001f) radius = minOf(radius, (Whiteboard.CANVAS_WIDTH - v.x) / direction.x)
        if (direction.x < -0.00001f) radius = minOf(radius, -v.x / direction.x)
        if (direction.y > 0.00001f) radius = minOf(radius, (Whiteboard.CANVAS_HEIGHT - v.y) / direction.y)
        if (direction.y < -0.00001f) radius = minOf(radius, -v.y / direction.y)
    }
    if (radius < 0.5f) return edges
    // Return along the second arm to reach the marker without an interior diagonal.
    return edges + directions.map { whiteboardPoint(v.x + it.x * radius, v.y + it.y * radius) }
}

private fun arrowHead(tip: WhiteboardPoint, dx: Float, dy: Float, size: Float): List<WhiteboardPoint> {
    val length = sqrt(dx * dx + dy * dy)
    if (length == 0f) return listOf(tip)
    val alongX = dx / length * size
    val alongY = dy / length * size
    return listOf(
        whiteboardPoint(tip.x - alongX - alongY * 0.55f, tip.y - alongY + alongX * 0.55f),
        tip,
        whiteboardPoint(tip.x - alongX + alongY * 0.55f, tip.y - alongY - alongX * 0.55f),
    )
}

private fun ellipsePoints(
    centerX: Float, centerY: Float, radiusX: Float, radiusY: Float,
    startAngle: Double, sweep: Double, segments: Int,
): List<WhiteboardPoint> = List(segments + 1) { index ->
    val angle = startAngle + sweep * index / segments
    whiteboardPoint(centerX + radiusX * cos(angle).toFloat(), centerY + radiusY * sin(angle).toFloat())
}

private fun coordinateAxis(from: WhiteboardPoint, to: WhiteboardPoint, tick: Float, strokeWidth: Float): List<WhiteboardPoint> {
    val dx = to.x - from.x
    val dy = to.y - from.y
    val length = sqrt(dx * dx + dy * dy)
    val tickX = -dy / length * tick
    val tickY = dx / length * tick
    return buildList {
        add(from)
        for (index in 1..5) {
            if (index == 3) continue // The axes intersect at the origin; no tick is needed there.
            val x = from.x + dx * index / 6f
            val y = from.y + dy * index / 6f
            val center = whiteboardPoint(x, y)
            add(center)
            add(whiteboardPoint(x + tickX, y + tickY))
            add(center)
            add(whiteboardPoint(x - tickX, y - tickY))
            add(center)
        }
        add(to)
        addAll(arrowHead(to, dx, dy, minOf(length * 0.16f, maxOf(strokeWidth * 3f, length * 0.1f))))
    }
}
