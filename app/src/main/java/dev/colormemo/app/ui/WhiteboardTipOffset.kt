package dev.colormemo.app.ui

import dev.colormemo.core.model.WhiteboardPoint

internal const val WHITEBOARD_TIP_OFFSET_DP = 48f
internal const val WHITEBOARD_TIP_SNAP_RADIUS_DP = 12f

internal data class WhiteboardTipPreview(
    val pointer: WhiteboardViewportPoint,
    val tip: WhiteboardPoint,
)

/** One gesture uses the same screen-space offset and viewport transform, including its final up. */
internal class WhiteboardDrawingInput(
    private val transform: WhiteboardTransform,
    private val tipOffsetPx: Float = 0f,
) {
    private fun screenPosition(x: Float, y: Float): WhiteboardViewportPoint =
        WhiteboardViewportPoint(x, if (tipOffsetPx > 0f) {
            // Shorten the offset at the visible top; never flip sides during a stroke.
            maxOf(y - tipOffsetPx, 0f, transform.originY)
        } else y)

    fun pointAt(x: Float, y: Float): WhiteboardPoint {
        val screen = screenPosition(x, y)
        val point = transform.screenToWorld(screen.x, screen.y)
        return whiteboardPoint(point.x, point.y)
    }

    fun contains(x: Float, y: Float, canvasHeight: Float = transform.canvasHeight): Boolean {
        // A finger outside the viewport must stop the stroke even if its offset tip is inside.
        if (x !in 0f..transform.viewportWidth || y !in 0f..transform.viewportHeight) return false
        val screen = screenPosition(x, y)
        return transform.copy(canvasHeight = canvasHeight).containsScreenPoint(screen.x, screen.y)
    }
}
