package dev.colormemo.app.ui

import dev.colormemo.core.model.Whiteboard
import dev.colormemo.core.model.WhiteboardPoint
import dev.colormemo.core.model.WhiteboardStroke

private const val GESTURE_POINT_SPACING = 2.5f
private const val GESTURE_ERASER_RADIUS = 28f

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
) {
    var working: Whiteboard = before
        private set
    var limitReached: Boolean = false
        private set
    var isNavigating: Boolean = false
        private set
    val hasChanges: Boolean
        get() = working != before

    private var started = false
    private var lastPoint: WhiteboardPoint? = null
    private var pointCount = before.strokes.sumOf { it.points.size }
    private var strokeStarted = false

    fun begin(point: WhiteboardPoint): Whiteboard {
        if (started || isNavigating) return working
        started = true
        lastPoint = point
        if (erasing) {
            working = working.eraseBetween(point, point, GESTURE_ERASER_RADIUS)
        } else if (working.strokes.size >= Whiteboard.MAX_STROKES || pointCount >= Whiteboard.MAX_POINTS) {
            // Keep the gesture alive: a second finger must still be able to navigate.
            limitReached = true
        } else {
            working = working.copy(strokes = working.strokes + WhiteboardStroke(
                color = color,
                width = width,
                points = listOf(point),
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
            working = working.eraseBetween(previous, point, GESTURE_ERASER_RADIUS)
            lastPoint = point
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

    /** Discard this gesture's provisional ink/erasing and ignore all later drawing points. */
    fun startNavigation(): Whiteboard {
        isNavigating = true
        working = before
        limitReached = false
        return working
    }
}
