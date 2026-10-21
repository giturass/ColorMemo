package dev.colormemo.app.ui

import dev.colormemo.core.model.Whiteboard
import dev.colormemo.core.model.WhiteboardPoint
import dev.colormemo.core.model.WhiteboardRect

private val boardBounds = WhiteboardRect(0f, 0f, Whiteboard.CANVAS_WIDTH, Whiteboard.CANVAS_HEIGHT)

/** Preview only: a second pointer or cancellation restores the previous selection. */
internal class WhiteboardSelectionDrag(
    private val previous: WhiteboardRect?,
    private val start: WhiteboardPoint,
    private val touchSlop: Float,
) {
    var selection: WhiteboardRect? = previous
        private set
    private var navigating = false

    fun update(end: WhiteboardPoint): WhiteboardRect? {
        if (!navigating) {
            selection = if (start.isFarEnoughFrom(end, touchSlop))
                WhiteboardRect(minOf(start.x, end.x), minOf(start.y, end.y), maxOf(start.x, end.x), maxOf(start.y, end.y))
                    .intersection(boardBounds)?.takeIf { it.width >= 1f && it.height >= 1f }
            else previous
        }
        return selection
    }

    fun cancel(): WhiteboardRect? {
        navigating = true
        selection = previous
        return selection
    }
}

/** Cut every existing layer; clipping keeps thick ink, images and glyph edges exact. */
internal fun Whiteboard.cutOut(area: WhiteboardRect): Whiteboard? {
    val selection = area.intersection(boardBounds) ?: return this
    var exceeded = false
    fun remaining(bounds: WhiteboardRect, holes: List<WhiteboardRect>): List<WhiteboardRect>? {
        val visibleBounds = bounds.intersection(boardBounds) ?: return holes
        val cut = selection.intersection(visibleBounds) ?: return holes
        if (holes.any { it.contains(cut) }) return holes
        val next = holes.filterNot { cut.contains(it) } + cut
        // Removing the last visible pieces removes the object too, including its attachment reference.
        var pieces = listOf(visibleBounds)
        next.forEach { hole -> pieces = pieces.flatMap { it.without(hole) } }
        if (pieces.isEmpty()) return null
        if (next.size > Whiteboard.MAX_CUTOUTS_PER_LAYER) exceeded = true
        return next
    }
    val next = copy(
        strokes = strokes.mapNotNull { stroke ->
            if (stroke.points.isEmpty()) return@mapNotNull stroke
            val reach = stroke.width * 0.707107f // Covers rotated square marker caps too.
            val bounds = WhiteboardRect(stroke.points.minOf { it.x } - reach, stroke.points.minOf { it.y } - reach,
                stroke.points.maxOf { it.x } + reach, stroke.points.maxOf { it.y } + reach)
            remaining(bounds, stroke.cutouts)?.let { stroke.copy(cutouts = it) }
        },
        images = images.mapNotNull { image ->
            remaining(WhiteboardRect(image.x, image.y, image.x + image.width, image.y + image.height), image.cutouts)
                ?.let { image.copy(cutouts = it) }
        },
        texts = texts.mapNotNull { text ->
            remaining(WhiteboardRect(text.x, text.y, text.x + text.width, text.y + text.height), text.cutouts)
                ?.let { text.copy(cutouts = it) }
        },
    )
    return next.takeUnless { exceeded }
}

private fun WhiteboardRect.without(hole: WhiteboardRect): List<WhiteboardRect> {
    val cut = intersection(hole) ?: return listOf(this)
    return listOf(
        WhiteboardRect(left, top, right, cut.top), WhiteboardRect(left, cut.bottom, right, bottom),
        WhiteboardRect(left, cut.top, cut.left, cut.bottom), WhiteboardRect(cut.right, cut.top, right, cut.bottom),
    ).filter { it.width > 0f && it.height > 0f }
}
