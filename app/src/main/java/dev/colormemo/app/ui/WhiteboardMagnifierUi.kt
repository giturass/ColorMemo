package dev.colormemo.app.ui

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.unit.dp

/** Replay the ink layer without tool handles, which would obscure the enlarged endpoint. */
internal fun DrawScope.drawWhiteboardMagnifier(
    layer: GraphicsLayer,
    magnifier: WhiteboardMagnifier,
    transform: WhiteboardTransform,
) {
    val placement = magnifier.placement
    val center = Offset(placement.centerX, placement.centerY)
    val extent = Size(placement.width, placement.height)
    val corner = center - Offset(extent.width / 2f, extent.height / 2f)
    val radius = CornerRadius(12.dp.toPx())
    val outline = Path().apply {
        addRoundRect(RoundRect(corner.x, corner.y, corner.x + extent.width, corner.y + extent.height, radius))
    }
    drawRoundRect(Color.Black.copy(alpha = 0.18f), corner + Offset(0f, 3.dp.toPx()), extent, radius)
    clipPath(outline) {
        drawRect(Color(0xFFE0E3E7), corner, extent)
        val source = transform.worldToScreen(magnifier.source.x, magnifier.source.y)
        val zoom = 2f
        withTransform({
            translate(center.x - source.x * zoom, center.y - source.y * zoom)
            scale(zoom, zoom, Offset.Zero)
        }) { drawLayer(layer) }
        // Leave the center open: a solid cursor would cover precisely the ink
        // that the finger magnifier is meant to reveal.
        for (direction in listOf(Offset(-1f, 0f), Offset(1f, 0f), Offset(0f, -1f), Offset(0f, 1f))) {
            val start = center + direction * 5.dp.toPx()
            val end = center + direction * 10.dp.toPx()
            drawLine(Color.White, start, end, 3.dp.toPx())
            drawLine(Color(0xFF1967D2), start, end, 1.dp.toPx())
        }
    }
    drawPath(outline, Color.White, style = Stroke(3.dp.toPx()))
    drawPath(outline, Color(0xFF5F6368), style = Stroke(1.dp.toPx()))
}
