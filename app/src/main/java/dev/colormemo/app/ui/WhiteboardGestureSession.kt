package dev.colormemo.app.ui

import dev.colormemo.core.model.Whiteboard
import dev.colormemo.core.model.WhiteboardBrush
import dev.colormemo.core.model.WhiteboardPoint
import dev.colormemo.core.model.WhiteboardStroke

private const val GESTURE_POINT_SPACING = 2.5f
internal const val DEFAULT_ERASER_RADIUS = 28f
internal const val MIN_ERASER_RADIUS = 8f
internal const val MAX_ERASER_RADIUS = 100f

/**
 * Keeps the edits made by one pointer gesture reversible until all pointers are lifted.
 * Publish [working] as it changes; record [before] in undo history only at the end when
 * [hasChanges] is true. A second finger may turn an already visible edit into navigation.
 */
internal class WhiteboardGestureSession(
    val before: Whiteboard,
    private val erasing: Boolean,
    private val color: Long,
    private val width: Float,
    private val brush: WhiteboardBrush = WhiteboardBrush.FINELINER,
    private val shape: WhiteboardShape? = null,
    eraserRadius: Float = DEFAULT_ERASER_RADIUS,
) {
    private val eraserRadius = eraserRadius.coerceIn(MIN_ERASER_RADIUS, MAX_ERASER_RADIUS)
    var working: Whiteboard = before
        private set
    var limitReached: Boolean = false
        private set
    var isNavigating: Boolean = false
        private set
    val hasChanges: Boolean
        get() = working != before

    private var started = false
    private var startPoint: WhiteboardPoint? = null
    private var lastPoint: WhiteboardPoint? = null
    private val existingPointCount = before.strokes.sumOf { it.points.size }
    private var pointCount = existingPointCount
    private var strokeStarted = false

    fun begin(point: WhiteboardPoint): Whiteboard {
        if (started || isNavigating) return working
        started = true
        startPoint = point
        lastPoint = point
        if (erasing) {
            erase(point, point)
        } else if (working.strokes.size >= Whiteboard.MAX_STROKES || pointCount >= Whiteboard.MAX_POINTS) {
            // Keep the gesture alive: a second finger must still be able to navigate.
            limitReached = true
        } else if (shape == null) {
            working = working.copy(strokes = working.strokes + WhiteboardStroke(
                color = color,
                width = width,
                points = listOf(point),
                brush = brush,
            ))
            pointCount++
            strokeStarted = true
        }
        return working
    }

    fun update(point: WhiteboardPoint, finalPoint: Boolean = false): Whiteboard {
        if (isNavigating) return working
        val previous = lastPoint ?: return working
        if (erasing) {
            erase(previous, point)
            lastPoint = point
        } else if (shape != null) {
            val points = shape.points(startPoint ?: return working, point, width)
            previewStroke(points)
        } else if (strokeStarted && point != previous &&
            (finalPoint || point.isFarEnoughFrom(previous, GESTURE_POINT_SPACING))
        ) {
            if (pointCount >= Whiteboard.MAX_POINTS) {
                limitReached = true
            } else {
                val stroke = working.strokes.last()
                working = working.copy(
                    strokes = working.strokes.dropLast(1) + stroke.copy(points = stroke.points + point),
                )
                pointCount++
                lastPoint = point
            }
        }
        return working
    }

    /** Shapes and instrument drawings remain one reversible stroke throughout the gesture. */
    fun previewStroke(points: List<WhiteboardPoint>): Whiteboard {
        if (isNavigating) return working
        limitReached = points.size >= 2 &&
            (before.strokes.size >= Whiteboard.MAX_STROKES || existingPointCount + points.size > Whiteboard.MAX_POINTS)
        working = if (points.size < 2 || limitReached) before else before.copy(
            strokes = before.strokes + WhiteboardStroke(color, width, points, brush),
        )
        return working
    }

    private fun erase(start: WhiteboardPoint, end: WhiteboardPoint) {
        limitReached = false
        working = working.eraseBetween(start, end, eraserRadius) { limitReached = true }
    }

    /** Discard this gesture's provisional ink/erasing and ignore all later drawing points. */
    fun startNavigation(): Whiteboard {
        isNavigating = true
        working = before
        limitReached = false
        return working
    }
}
