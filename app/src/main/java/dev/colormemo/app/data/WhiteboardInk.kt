package dev.colormemo.app.data

import dev.colormemo.core.model.WhiteboardBrush

/** Shared by the live canvas, card previews and SVG exports. */
internal data class InkLayer(val widthScale: Float, val opacity: Float)

private val finelinerInk = listOf(InkLayer(1f, 1f))
private val watercolorInk = listOf(InkLayer(1f, 0.12f), InkLayer(0.8f, 0.14f), InkLayer(0.55f, 0.18f))
private val markerInk = listOf(InkLayer(1f, 0.7f))

internal val WhiteboardBrush.inkLayers: List<InkLayer>
    get() = when (this) {
        WhiteboardBrush.FINELINER -> finelinerInk
        WhiteboardBrush.WATERCOLOR -> watercolorInk
        WhiteboardBrush.MARKER -> markerInk
    }
