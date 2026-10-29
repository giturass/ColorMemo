package dev.colormemo.app.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/** This overlay is drawn only by the editor and never becomes part of the saved artwork. */
internal fun DrawScope.drawWhiteboardTipPreview(preview: WhiteboardTipPreview, transform: WhiteboardTransform) {
    val screen = transform.worldToScreen(preview.tip.x, preview.tip.y)
    val tip = Offset(screen.x, screen.y)
    val pointer = Offset(preview.pointer.x, preview.pointer.y)
    val blue = Color(0xFF1967D2)
    val distance = (pointer - tip).getDistance()
    val gap = 14.dp.toPx()
    if (distance > gap * 2f) {
        val direction = (pointer - tip) / distance
        drawLine(blue.copy(alpha = 0.45f), tip + direction * gap, pointer - direction * gap,
            1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx())))
        drawCircle(blue.copy(alpha = 0.45f), 7.dp.toPx(), pointer, style = Stroke(1.dp.toPx()))
    }
    // Leave the center open so the actual endpoint remains visible against existing ink.
    for (direction in listOf(Offset(-1f, 0f), Offset(1f, 0f), Offset(0f, -1f), Offset(0f, 1f))) {
        val start = tip + direction * 5.dp.toPx()
        val end = tip + direction * 10.dp.toPx()
        drawLine(Color.White, start, end, 3.dp.toPx())
        drawLine(blue, start, end, 1.dp.toPx())
    }
}
