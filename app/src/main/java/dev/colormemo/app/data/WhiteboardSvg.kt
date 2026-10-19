package dev.colormemo.app.data

import dev.colormemo.core.model.Whiteboard
import dev.colormemo.core.model.WhiteboardBrush

/** Matches the canvas geometry, layered translucent ink and marker caps. */
internal fun whiteboardSvg(whiteboard: Whiteboard): String = buildString {
    val canvasWidth = Whiteboard.CANVAS_WIDTH.toInt()
    val canvasHeight = Whiteboard.CANVAS_HEIGHT.toInt()
    append("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"$canvasWidth\" height=\"$canvasHeight\" viewBox=\"0 0 $canvasWidth $canvasHeight\">\n")
    append("<title>白板</title>\n<rect width=\"$canvasWidth\" height=\"$canvasHeight\" fill=\"#ffffff\"/>\n")
    whiteboard.strokes.forEach { stroke ->
        val first = stroke.points.firstOrNull() ?: return@forEach
        val color = "#" + (stroke.color and 0xffffffL).toString(16).padStart(6, '0')
        val marker = stroke.brush == WhiteboardBrush.MARKER
        stroke.brush.inkLayers.forEach { layer ->
            val width = stroke.width * layer.widthScale
            if (stroke.points.all { it == first }) {
                if (marker) {
                    append("<rect x=\"").append(first.x - width / 2f).append("\" y=\"").append(first.y - width / 2f)
                        .append("\" width=\"").append(width).append("\" height=\"").append(width)
                } else {
                    append("<circle cx=\"").append(first.x).append("\" cy=\"").append(first.y)
                        .append("\" r=\"").append(width / 2f)
                }
                append("\" fill=\"").append(color).append("\" fill-opacity=\"").append(layer.opacity).append("\"/>\n")
            } else {
                append("<path d=\"M ").append(first.x).append(' ').append(first.y)
                stroke.points.drop(1).forEach { point -> append(" L ").append(point.x).append(' ').append(point.y) }
                append("\" fill=\"none\" stroke=\"").append(color).append("\" stroke-width=\"").append(width)
                    .append("\" stroke-opacity=\"").append(layer.opacity)
                    .append("\" stroke-linecap=\"").append(if (marker) "square" else "round")
                    .append("\" stroke-linejoin=\"").append(if (marker) "bevel" else "round").append("\"/>\n")
            }
        }
    }
    append("</svg>\n")
}
