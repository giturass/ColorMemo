package dev.colormemo.app.ui

import dev.colormemo.core.model.WhiteboardPoint

internal enum class WhiteboardMagnifierSide { ABOVE, LEFT, RIGHT, BELOW }

internal data class WhiteboardMagnifierPlacement(
    val centerX: Float,
    val centerY: Float,
    val width: Float,
    val height: Float,
    val side: WhiteboardMagnifierSide,
)

internal data class WhiteboardMagnifier(
    val source: WhiteboardPoint,
    val placement: WhiteboardMagnifierPlacement,
)

/** Screen pixels keep the lens and the clearance around the finger constant at every zoom. */
internal fun whiteboardMagnifierPlacement(
    viewportWidth: Float,
    viewportHeight: Float,
    pointerX: Float,
    pointerY: Float,
    density: Float,
    previous: WhiteboardMagnifierPlacement? = null,
): WhiteboardMagnifierPlacement? {
    if (!density.isFinite() || density <= 0f || !viewportWidth.isFinite() || !viewportHeight.isFinite() ||
        !pointerX.isFinite() || !pointerY.isFinite() || pointerX !in 0f..viewportWidth || pointerY !in 0f..viewportHeight
    ) return null
    val margin = 8f * density
    val clearance = 36f * density
    val width = minOf(112f * density, viewportWidth - 2f * margin)
    val height = minOf(84f * density, viewportHeight - 2f * margin)
    if (width < 72f * density || height < 56f * density) return null
    val centeredX = pointerX.coerceIn(margin + width / 2f, viewportWidth - margin - width / 2f)
    val centeredY = pointerY.coerceIn(margin + height / 2f, viewportHeight - margin - height / 2f)
    fun candidate(side: WhiteboardMagnifierSide): WhiteboardMagnifierPlacement? {
        val x = when (side) {
            WhiteboardMagnifierSide.LEFT -> pointerX - clearance - width / 2f
            WhiteboardMagnifierSide.RIGHT -> pointerX + clearance + width / 2f
            else -> centeredX
        }
        val y = when (side) {
            WhiteboardMagnifierSide.ABOVE -> pointerY - clearance - height / 2f
            WhiteboardMagnifierSide.BELOW -> pointerY + clearance + height / 2f
            else -> centeredY
        }
        return if (x - width / 2f >= margin && x + width / 2f <= viewportWidth - margin &&
            y - height / 2f >= margin && y + height / 2f <= viewportHeight - margin
        ) WhiteboardMagnifierPlacement(x, y, width, height, side) else null
    }
    val above = candidate(WhiteboardMagnifierSide.ABOVE)
    // Require extra space before returning above, so small movements near the top
    // do not repeatedly flip the lens between the top and a side.
    if (above != null && (previous == null || previous.side == WhiteboardMagnifierSide.ABOVE ||
            above.centerY - height / 2f >= margin + 12f * density)) return above
    previous?.let { candidate(it.side)?.let { placement -> return placement } }
    val sides = if (pointerX <= viewportWidth / 2f)
        listOf(WhiteboardMagnifierSide.RIGHT, WhiteboardMagnifierSide.LEFT)
    else listOf(WhiteboardMagnifierSide.LEFT, WhiteboardMagnifierSide.RIGHT)
    return above ?: sides.firstNotNullOfOrNull(::candidate) ?: candidate(WhiteboardMagnifierSide.BELOW)
}
