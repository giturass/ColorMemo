package dev.colormemo.core.model

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

/** Strokes use a fixed square so drawings remain stable across screen sizes. */
@Serializable
data class Whiteboard(val strokes: List<WhiteboardStroke> = emptyList()) {
    companion object {
        const val CANVAS_SIZE: Float = 1000f
        const val MAX_STROKES: Int = 500
        const val MAX_POINTS: Int = 6000
        const val MIN_STROKE_WIDTH: Float = 1f
        const val MAX_STROKE_WIDTH: Float = 40f
    }
}

@Serializable
enum class WhiteboardBrush { FINELINER, WATERCOLOR, MARKER }

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class WhiteboardStroke(
    val color: Long = 0xFF202124,
    val width: Float = 6f,
    val points: List<WhiteboardPoint>,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val brush: WhiteboardBrush = WhiteboardBrush.FINELINER,
)

@Serializable
data class WhiteboardPoint(val x: Float, val y: Float)
