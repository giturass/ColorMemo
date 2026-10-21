package dev.colormemo.app.data

import dev.colormemo.core.model.Whiteboard
import dev.colormemo.core.model.WhiteboardBrush
import dev.colormemo.core.model.WhiteboardText
import dev.colormemo.core.model.WhiteboardRect

/** Matches the canvas geometry, layered translucent ink and marker caps. */
internal fun whiteboardSvg(whiteboard: Whiteboard, imageDataUrls: Map<String, String> = emptyMap()): String = buildString {
    val canvasWidth = Whiteboard.CANVAS_WIDTH.toInt()
    val canvasHeight = Whiteboard.CANVAS_HEIGHT.toInt()
    append("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"$canvasWidth\" height=\"$canvasHeight\" viewBox=\"0 0 $canvasWidth $canvasHeight\">\n")
    append("<title>白板</title>\n<rect width=\"$canvasWidth\" height=\"$canvasHeight\" fill=\"#ffffff\"/>\n")
    var maskIndex = 0
    fun masked(areas: List<WhiteboardRect>, draw: () -> Unit) {
        if (areas.isEmpty()) { draw(); return }
        val id = "cut-${maskIndex++}"
        append("<defs><mask id=\"$id\" maskUnits=\"userSpaceOnUse\" x=\"0\" y=\"0\" width=\"$canvasWidth\" height=\"$canvasHeight\" style=\"mask-type:luminance\">")
        append("<rect width=\"$canvasWidth\" height=\"$canvasHeight\" fill=\"white\"/>")
        areas.forEach { area ->
            append("<rect x=\"${area.left}\" y=\"${area.top}\" width=\"${area.width}\" height=\"${area.height}\" fill=\"black\"/>")
        }
        append("</mask></defs><g mask=\"url(#$id)\">\n")
        draw()
        append("</g>\n")
    }
    whiteboard.images.forEach { image ->
        masked(image.cutouts) {
            val dataUrl = requireNotNull(imageDataUrls[image.attachmentId]) { "白板图片缺失，无法导出。" }
            append("<image x=\"").append(image.x).append("\" y=\"").append(image.y)
                .append("\" width=\"").append(image.width).append("\" height=\"").append(image.height)
                .append("\" preserveAspectRatio=\"none\" href=\"").append(dataUrl).append("\"/>\n")
        }
    }
    whiteboard.strokes.forEach { stroke ->
        val first = stroke.points.firstOrNull() ?: return@forEach
        masked(stroke.cutouts) {
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
    }
    whiteboard.texts.forEach { text ->
        masked(text.cutouts) {
            val color = "#" + (text.color and 0xffffffL).toString(16).padStart(6, '0')
            append("<text font-family=\"sans-serif\" font-size=\"").append(text.fontSize)
                .append("\" fill=\"").append(color).append("\" xml:space=\"preserve\">")
            text.text.split('\n').forEachIndexed { index, line ->
                append("<tspan x=\"").append(text.x).append("\" y=\"")
                    .append(text.y + text.fontSize + index * text.fontSize * WhiteboardText.LINE_HEIGHT).append("\">")
                    .append(line.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"))
                    .append("</tspan>")
            }
            append("</text>\n")
        }
    }
    append("</svg>\n")
}
