package dev.colormemo.app.ui

import dev.colormemo.core.model.Whiteboard
import dev.colormemo.core.model.WhiteboardBrush
import dev.colormemo.core.model.WhiteboardPoint
import dev.colormemo.core.model.WhiteboardStroke
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin

internal const val MIN_INSTRUMENT_RADIUS = 24f

internal enum class WhiteboardInstrument(val displayName: String) {
    COMPASS("圆规"), PROTRACTOR("量角器"),
}

internal enum class InstrumentHandle { CENTER, PEN, BASELINE, MEASUREMENT }

/** Temporary guides use world coordinates but never become part of a saved board. */
internal data class WhiteboardInstrumentState(
    val centerX: Float = 500f,
    val centerY: Float = 500f,
    val radius: Float = 180f,
    val baselineDegrees: Float = 0f,
    val angleDegrees: Float = 60f,
) {
    val center: WhiteboardPoint get() = WhiteboardPoint(centerX, centerY)
    val maximumRadius: Float get() = minOf(centerX, Whiteboard.CANVAS_WIDTH - centerX,
        centerY, Whiteboard.CANVAS_HEIGHT - centerY)

    fun constrained(): WhiteboardInstrumentState {
        val size = radius.coerceIn(MIN_INSTRUMENT_RADIUS, Whiteboard.CANVAS_WIDTH / 2f)
        return copy(centerX = centerX.coerceIn(size, Whiteboard.CANVAS_WIDTH - size),
            centerY = centerY.coerceIn(size, Whiteboard.CANVAS_HEIGHT - size), radius = size,
            baselineDegrees = normalizedDegrees(baselineDegrees), angleDegrees = angleDegrees.coerceIn(0f, 180f))
    }

    fun resized(radius: Float): WhiteboardInstrumentState = copy(radius = radius.coerceIn(MIN_INSTRUMENT_RADIUS, maximumRadius))

    fun pointAt(degrees: Float, distance: Float = radius): WhiteboardPoint {
        val angle = degrees * PI / 180.0
        return WhiteboardPoint(centerX + cos(angle).toFloat() * distance, centerY - sin(angle).toFloat() * distance)
    }

    fun handlePoint(handle: InstrumentHandle): WhiteboardPoint = when (handle) {
        InstrumentHandle.CENTER -> center
        InstrumentHandle.PEN -> pointAt(baselineDegrees)
        InstrumentHandle.BASELINE -> pointAt(baselineDegrees - 90f, radius * 0.4f)
        InstrumentHandle.MEASUREMENT -> pointAt(baselineDegrees + angleDegrees)
    }

    fun drawingPoints(instrument: WhiteboardInstrument): List<WhiteboardPoint> = when (instrument) {
        WhiteboardInstrument.COMPASS -> arcPoints(0f, 360f, radius, 96)
        WhiteboardInstrument.PROTRACTOR -> {
            val angleRadius = radius * 0.24f
            listOf(handlePoint(InstrumentHandle.MEASUREMENT), center, pointAt(baselineDegrees)) +
                arcPoints(baselineDegrees, angleDegrees, angleRadius, 32)
        }
    }.map { whiteboardPoint(it.x, it.y) }

}

private fun WhiteboardInstrumentState.arcPoints(start: Float, sweep: Float, distance: Float, segments: Int): List<WhiteboardPoint> =
    List(segments + 1) { pointAt(start + sweep * it / segments, distance) }

internal fun Whiteboard.withInstrumentDrawing(
    instrument: WhiteboardInstrument, state: WhiteboardInstrumentState,
    color: Long, width: Float, brush: WhiteboardBrush,
): Whiteboard? {
    val points = state.drawingPoints(instrument)
    if (strokes.size >= Whiteboard.MAX_STROKES || strokes.sumOf { it.points.size } + points.size > Whiteboard.MAX_POINTS) return null
    return copy(strokes = strokes + WhiteboardStroke(color, width, points, brush))
}

/** A second finger restores the instrument before handing the gesture to navigation. */
internal class WhiteboardInstrumentDrag(
    val before: WhiteboardInstrumentState,
    instrument: WhiteboardInstrument,
    start: WhiteboardPoint,
    touchRadius: Float,
) {
    private val handles = if (instrument == WhiteboardInstrument.COMPASS) {
        listOf(InstrumentHandle.CENTER, InstrumentHandle.PEN)
    } else listOf(InstrumentHandle.CENTER, InstrumentHandle.MEASUREMENT, InstrumentHandle.BASELINE)
    private val hit = handles.minBy { distanceSquared(before.handlePoint(it), start) }
        .takeIf { distanceSquared(before.handlePoint(it), start) <= touchRadius * touchRadius }
    private val handle = hit ?: InstrumentHandle.CENTER
    private val grabOffset = if (hit != null || distanceSquared(before.center, start) <= before.radius * before.radius) {
        WhiteboardPoint(before.centerX - start.x, before.centerY - start.y)
    } else WhiteboardPoint(0f, 0f)
    private var navigating = false
    private var pointerDegrees = degreesAt(start)
    private var penDegrees = before.baselineDegrees
    private var tracedPoints = emptyList<WhiteboardPoint>()
    val drawingPoints: List<WhiteboardPoint> get() = tracedPoints

    fun update(point: WhiteboardPoint): WhiteboardInstrumentState {
        if (navigating) return before
        val degrees = degreesAt(point)
        return when (handle) {
            InstrumentHandle.CENTER -> before.copy(centerX = point.x + grabOffset.x, centerY = point.y + grabOffset.y).constrained()
            InstrumentHandle.PEN -> {
                // The needle and opening stay fixed while the pencil rotates. Ignore
                // touches at the needle, where the pointer's direction is undefined.
                if (distanceSquared(before.center, point) >= MIN_INSTRUMENT_RADIUS * MIN_INSTRUMENT_RADIUS / 4f) {
                    val sweep = normalizedDegrees(degrees - pointerDegrees + 180f) - 180f
                    pointerDegrees = degrees
                    if (abs(sweep) > 0.001f) {
                        val segments = ceil(abs(sweep) / 3f).toInt()
                        val arc = before.arcPoints(penDegrees, sweep, before.radius, segments)
                            .map { whiteboardPoint(it.x, it.y) }
                        // Keep one excess point so the preview can reject an arc that
                        // exceeds capacity, without growing an unbounded gesture buffer.
                        tracedPoints = buildList {
                            addAll(tracedPoints)
                            for (next in arc) {
                                if (size > Whiteboard.MAX_POINTS) break
                                if (lastOrNull() != next) add(next)
                            }
                        }
                        penDegrees = normalizedDegrees(penDegrees + sweep)
                    }
                }
                before.copy(baselineDegrees = penDegrees)
            }
            InstrumentHandle.BASELINE -> before.copy(baselineDegrees = normalizedDegrees(degrees + 90f))
            InstrumentHandle.MEASUREMENT -> {
                val delta = normalizedDegrees(degrees - before.baselineDegrees)
                before.copy(angleDegrees = if (delta <= 180f) delta else if (delta > 270f) 0f else 180f)
            }
        }
    }

    fun startNavigation(): WhiteboardInstrumentState {
        navigating = true
        tracedPoints = emptyList()
        return before
    }

    private fun degreesAt(point: WhiteboardPoint): Float = normalizedDegrees(
        (atan2((before.centerY - point.y).toDouble(), (point.x - before.centerX).toDouble()) * 180 / PI).toFloat(),
    )
}

private fun normalizedDegrees(value: Float): Float = (value % 360f + 360f) % 360f

private fun distanceSquared(a: WhiteboardPoint, b: WhiteboardPoint): Float {
    val dx = a.x - b.x
    val dy = a.y - b.y
    return dx * dx + dy * dy
}
