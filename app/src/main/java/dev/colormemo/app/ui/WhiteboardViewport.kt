package dev.colormemo.app.ui

import dev.colormemo.core.model.Whiteboard

/** Camera coordinates are independent of screen pixels, density and persisted strokes. */
internal data class WhiteboardViewport(
    val centerX: Float = Whiteboard.CANVAS_WIDTH / 2f,
    // Open on the original upper area, with the added space available below it.
    val centerY: Float = Whiteboard.CANVAS_WIDTH / 2f,
    val zoom: Float = DEFAULT_ZOOM,
) {
    fun constrained(width: Float, height: Float, canvasHeight: Float = Whiteboard.CANVAS_HEIGHT): WhiteboardViewport {
        require(canvasHeight.isFinite() && canvasHeight in Whiteboard.INITIAL_CANVAS_HEIGHT..Whiteboard.CANVAS_HEIGHT)
        val nextZoom = zoom.coerceIn(MIN_ZOOM, MAX_ZOOM)
        val scale = fitScale(width, height) * nextZoom
        return copy(
            centerX = constrainCenter(centerX, width / (2f * scale), Whiteboard.CANVAS_WIDTH),
            centerY = constrainCenter(centerY, height / (2f * scale), canvasHeight),
            zoom = nextZoom,
        )
    }

    fun transform(width: Float, height: Float, canvasHeight: Float = Whiteboard.CANVAS_HEIGHT): WhiteboardTransform {
        val camera = constrained(width, height, canvasHeight)
        val scale = fitScale(width, height) * camera.zoom
        return WhiteboardTransform(
            viewportWidth = width,
            viewportHeight = height,
            originX = canvasOrigin(width, camera.centerX, scale, Whiteboard.CANVAS_WIDTH),
            // Anchor short boards at the top: growing them must not move existing ink.
            originY = if (canvasHeight * scale <= height) 0f else canvasOrigin(height, camera.centerY, scale, canvasHeight),
            scale = scale,
            canvasHeight = canvasHeight,
        )
    }

    /**
     * [centroidX] and [centroidY] are the previous gesture centroid in screen pixels.
     * Its world point follows the centroid by [panX]/[panY] while zoom changes.
     * At a canvas edge, keeping the board inside the viewport takes precedence.
     */
    fun applyGesture(
        width: Float,
        height: Float,
        centroidX: Float,
        centroidY: Float,
        panX: Float = 0f,
        panY: Float = 0f,
        zoomChange: Float = 1f,
        canvasHeight: Float = Whiteboard.CANVAS_HEIGHT,
    ): WhiteboardViewport {
        require(zoomChange.isFinite() && zoomChange > 0f)
        val camera = constrained(width, height, canvasHeight)
        val anchor = camera.transform(width, height, canvasHeight).screenToWorld(centroidX, centroidY)
        val nextZoom = (camera.zoom * zoomChange).coerceIn(MIN_ZOOM, MAX_ZOOM)
        val nextScale = fitScale(width, height) * nextZoom
        return WhiteboardViewport(
            centerX = anchor.x - (centroidX + panX - width / 2f) / nextScale,
            centerY = anchor.y - (centroidY + panY - height / 2f) / nextScale,
            zoom = nextZoom,
        ).constrained(width, height, canvasHeight)
    }

    companion object {
        const val MIN_ZOOM = 1f
        const val MAX_ZOOM = 16f
        const val DEFAULT_ZOOM = 4f

        private fun fitScale(width: Float, height: Float): Float {
            require(width.isFinite() && height.isFinite() && width > 0f && height > 0f)
            // Use the maximum extent as a stable zoom reference, even while a board grows.
            return minOf(width / Whiteboard.CANVAS_WIDTH, height / Whiteboard.CANVAS_HEIGHT)
        }

        private fun constrainCenter(center: Float, halfVisibleWorld: Float, canvasExtent: Float): Float =
            if (halfVisibleWorld >= canvasExtent / 2f) {
                canvasExtent / 2f
            } else {
                center.coerceIn(halfVisibleWorld, canvasExtent - halfVisibleWorld)
            }

        private fun canvasOrigin(viewportSize: Float, center: Float, scale: Float, canvasExtent: Float): Float {
            val canvasSize = canvasExtent * scale
            return if (canvasSize <= viewportSize) {
                (viewportSize - canvasSize) / 2f
            } else {
                // Clamp in screen space too, so float rounding cannot leave a gap at an edge.
                (viewportSize / 2f - center * scale).coerceIn(viewportSize - canvasSize, 0f)
            }
        }
    }
}

internal data class WhiteboardViewportPoint(val x: Float, val y: Float)

internal data class WhiteboardTransform(
    val viewportWidth: Float,
    val viewportHeight: Float,
    val originX: Float,
    val originY: Float,
    val scale: Float,
    val canvasHeight: Float = Whiteboard.CANVAS_HEIGHT,
) {
    val canvasWidthPx: Float get() = Whiteboard.CANVAS_WIDTH * scale
    val canvasHeightPx: Float get() = canvasHeight * scale

    fun worldToScreen(x: Float, y: Float): WhiteboardViewportPoint =
        WhiteboardViewportPoint(originX + x * scale, originY + y * scale)

    /** Outside points stay outside; callers must hit-test before recording a stroke. */
    fun screenToWorld(x: Float, y: Float): WhiteboardViewportPoint =
        WhiteboardViewportPoint((x - originX) / scale, (y - originY) / scale)

    fun containsScreenPoint(x: Float, y: Float): Boolean =
        x in 0f..viewportWidth && y in 0f..viewportHeight &&
            x >= originX && x <= originX + canvasWidthPx &&
            y >= originY && y <= originY + canvasHeightPx
}
