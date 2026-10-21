package dev.colormemo.core.model

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlin.math.ceil

/** Fixed world coordinates keep strokes stable across screen sizes. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class Whiteboard(
    val strokes: List<WhiteboardStroke> = emptyList(),
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val images: List<WhiteboardImage> = emptyList(),
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val texts: List<WhiteboardText> = emptyList(),
) {
    val hasContent: Boolean get() = strokes.isNotEmpty() || images.isNotEmpty() || texts.isNotEmpty()

    /** Derived from existing layers, so backups and sync need no extra size field. */
    val canvasHeight: Float get() = heightForContent(maxOf(
        strokes.maxOfOrNull { stroke ->
            // Square marker caps can extend diagonally beyond the last point.
            (stroke.points.maxOfOrNull { it.y } ?: 0f) + stroke.width * 0.71f
        } ?: 0f,
        images.maxOfOrNull { it.y + it.height } ?: 0f,
        texts.maxOfOrNull { it.y + it.height } ?: 0f,
    ))

    companion object {
        const val CANVAS_WIDTH: Float = 1000f
        const val INITIAL_CANVAS_HEIGHT: Float = 1000f
        /** Maximum world extent; validation and stored coordinates retain this limit. */
        const val CANVAS_HEIGHT: Float = 4000f
        private const val HEIGHT_STEP: Float = 500f
        private const val BOTTOM_SPACE: Float = 200f

        fun heightForContent(bottom: Float): Float =
            (ceil((bottom + BOTTOM_SPACE) / HEIGHT_STEP) * HEIGHT_STEP)
                .coerceIn(INITIAL_CANVAS_HEIGHT, CANVAS_HEIGHT)

        const val MAX_CUTOUTS_PER_LAYER: Int = 64
        const val MAX_STROKES: Int = 500
        const val MAX_POINTS: Int = 6000
        const val MAX_IMAGES: Int = 20
        const val MAX_TEXTS: Int = 100
        const val MAX_TEXT_LENGTH: Int = 2000
        const val MIN_TEXT_SIZE: Float = 12f
        const val MAX_TEXT_SIZE: Float = 96f
        const val MIN_STROKE_WIDTH: Float = 1f
        const val MAX_STROKE_WIDTH: Float = 40f
    }
}

/** Text stays editable; x/y mark its top left and explicit newlines preserve its layout. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class WhiteboardText(
    val id: String,
    val text: String,
    val x: Float,
    val y: Float,
    val width: Float,
    val fontSize: Float = 32f,
    val color: Long = 0xFF202124L,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val cutouts: List<WhiteboardRect> = emptyList(),
) {
    val height: Float get() = (text.count { it == '\n' } + 1) * fontSize * LINE_HEIGHT

    companion object {
        const val LINE_HEIGHT = 1.25f
    }
}

/** The same content is displayed on Android and exported into XML text nodes. */
fun validWhiteboardTextContent(value: String): Boolean = value.isNotBlank() &&
    value.length <= Whiteboard.MAX_TEXT_LENGTH && value.codePoints().allMatch { code ->
        code == 0x0A || (!Character.isISOControl(code) &&
            (code in 0x20..0xD7FF || code in 0xE000..0xFFFD || code in 0x10000..0x10FFFF))
    }

/** Images share the stroke coordinate system and are painted below all ink. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class WhiteboardImage(
    val id: String,
    val attachmentId: String,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val cutouts: List<WhiteboardRect> = emptyList(),
)

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
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val cutouts: List<WhiteboardRect> = emptyList(),
)

@Serializable
data class WhiteboardPoint(val x: Float, val y: Float)

/** Rectangular holes belong to existing layers, so later drawing can fill the area again. */
@Serializable
data class WhiteboardRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    fun contains(other: WhiteboardRect): Boolean = left <= other.left && top <= other.top &&
        right >= other.right && bottom >= other.bottom
    fun contains(point: WhiteboardPoint): Boolean = point.x in left..right && point.y in top..bottom
    fun intersection(other: WhiteboardRect): WhiteboardRect? =
        WhiteboardRect(maxOf(left, other.left), maxOf(top, other.top), minOf(right, other.right), minOf(bottom, other.bottom))
            .takeIf { it.width > 0f && it.height > 0f }
}
