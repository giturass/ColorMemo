package dev.colormemo.app.ui

import dev.colormemo.core.model.Whiteboard
import dev.colormemo.core.model.WhiteboardPoint
import dev.colormemo.core.model.WhiteboardText

internal fun WhiteboardText.movedTo(x: Float, y: Float): WhiteboardText = copy(
    x = x.coerceIn(0f, Whiteboard.CANVAS_WIDTH - width),
    y = y.coerceIn(0f, Whiteboard.CANVAS_HEIGHT - height),
)

internal fun Whiteboard.textAt(point: WhiteboardPoint, padding: Float = 0f): WhiteboardText? = texts.asReversed().firstOrNull {
    point.x in (it.x - padding)..(it.x + it.width + padding) && point.y in (it.y - padding)..(it.y + it.height + padding)
}

internal class WhiteboardTextDrag(
    val before: Whiteboard,
    private val text: WhiteboardText,
    private val start: WhiteboardPoint,
    private val touchSlop: Float,
) {
    var working: Whiteboard = before
        private set
    var isNavigating = false
        private set
    private var moving = false
    val hasMoved: Boolean get() = moving
    val hasChanges: Boolean get() = working != before

    fun update(point: WhiteboardPoint): Whiteboard {
        if (isNavigating) return working
        val dx = point.x - start.x
        val dy = point.y - start.y
        if (!moving && dx * dx + dy * dy < touchSlop * touchSlop) return working
        moving = true
        val moved = text.movedTo(text.x + dx, text.y + dy)
        working = before.copy(texts = before.texts.map { if (it.id == text.id) moved else it })
        return working
    }

    fun startNavigation(): Whiteboard {
        isNavigating = true
        working = before
        return working
    }
}
