package dev.colormemo.app.ui

import dev.colormemo.core.model.Whiteboard
import dev.colormemo.core.model.WhiteboardPoint
import dev.colormemo.core.model.WhiteboardImage
import kotlin.math.round

/** Fit a new image inside the visible board area without changing its aspect ratio. */
internal fun WhiteboardImage.placedIn(transform: WhiteboardTransform): WhiteboardImage {
    require(width.isFinite() && height.isFinite() && width > 0f && height > 0f)
    val topLeft = transform.screenToWorld(0f, 0f)
    val bottomRight = transform.screenToWorld(transform.viewportWidth, transform.viewportHeight)
    val left = topLeft.x.coerceIn(0f, Whiteboard.CANVAS_WIDTH)
    val top = topLeft.y.coerceIn(0f, Whiteboard.CANVAS_HEIGHT)
    val right = bottomRight.x.coerceIn(0f, Whiteboard.CANVAS_WIDTH)
    val bottom = bottomRight.y.coerceIn(0f, Whiteboard.CANVAS_HEIGHT)
    val scale = minOf((right - left) * 0.8f / width, (bottom - top) * 0.8f / height)
    val fittedWidth = width * scale
    val fittedHeight = height * scale
    return copy(x = (left + right - fittedWidth) / 2f, y = (top + bottom - fittedHeight) / 2f,
        width = fittedWidth, height = fittedHeight)
}

internal fun whiteboardPoint(x: Float, y: Float): WhiteboardPoint = WhiteboardPoint(
    x = round(x.coerceIn(0f, Whiteboard.CANVAS_WIDTH) * 2f) / 2f,
    y = round(y.coerceIn(0f, Whiteboard.CANVAS_HEIGHT) * 2f) / 2f,
)

internal fun WhiteboardPoint.isFarEnoughFrom(other: WhiteboardPoint, distance: Float): Boolean {
    val dx = x - other.x
    val dy = y - other.y
    return dx * dx + dy * dy >= distance * distance
}
