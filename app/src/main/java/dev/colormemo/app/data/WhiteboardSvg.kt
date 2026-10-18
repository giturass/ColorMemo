package dev.colormemo.app.data

import dev.colormemo.core.model.Whiteboard

/** The same square coordinate space, opaque colors, and round strokes used by the canvas. */
internal fun whiteboardSvg(whiteboard: Whiteboard): String = buildString {
    append("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"1000\" height=\"1000\" viewBox=\"0 0 1000 1000\">\n")
    append("<title>白板</title>\n<rect width=\"1000\" height=\"1000\" fill=\"#ffffff\"/>\n")
    whiteboard.strokes.forEach { stroke ->
        val first = stroke.points.firstOrNull() ?: return@forEach
        val color = "#" + (stroke.color and 0xffffffL).toString(16).padStart(6, '0')
        if (stroke.points.size == 1) {
            // An explicit circle preserves taps in viewers that omit zero-length paths.
            append("<circle cx=\"").append(first.x).append("\" cy=\"").append(first.y)
                .append("\" r=\"").append(stroke.width / 2f).append("\" fill=\"").append(color).append("\"/>\n")
        } else {
            append("<path d=\"M ").append(first.x).append(' ').append(first.y)
            stroke.points.drop(1).forEach { point -> append(" L ").append(point.x).append(' ').append(point.y) }
            append("\" fill=\"none\" stroke=\"").append(color).append("\" stroke-width=\"").append(stroke.width)
                .append("\" stroke-linecap=\"round\" stroke-linejoin=\"round\"/>\n")
        }
    }
    append("</svg>\n")
}
