package dev.colormemo.core.model

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class WhiteboardSizeTest {
    @Test
    fun startsSquareAndAddsRoomWhenInkApproachesTheBottom() {
        assertEquals(1000f, Whiteboard().canvasHeight)
        assertEquals(1000f, inkAt(500f).canvasHeight)
        assertEquals(1000f, Whiteboard.heightForContent(800f))
        assertEquals(1500f, Whiteboard.heightForContent(800.5f))
        assertEquals(1500f, inkAt(990f).canvasHeight)
        assertEquals(2000f, inkAt(1490f).canvasHeight)
        assertEquals(4000f, inkAt(3990f).canvasHeight)
        assertEquals(4000f, inkAt(4000f).canvasHeight)
    }

    @Test
    fun includesImageAndMultilineTextBoundsAndPreservesOldCoordinates() {
        val image = WhiteboardImage("image", "attachment", 0f, 1100f, 400f, 600f)
        val text = WhiteboardText("text", "第一行\n第二行", 100f, 2900f, 400f, 80f)
        val board = inkAt(200f).copy(images = listOf(image), texts = listOf(text))
        assertEquals(2000f, board.copy(texts = emptyList()).canvasHeight)
        assertEquals(3500f, board.canvasHeight)
        assertEquals(2900f, board.texts.single().y)
        assertEquals(1100f, board.images.single().y)
        assertEquals(1000f, board.copy(images = emptyList(), texts = emptyList()).canvasHeight)
    }

    @Test
    fun restoresHeightFromLegacyJsonWithoutAddingASerializedField() {
        assertEquals(1000f, Json.decodeFromString<Whiteboard>("{\"strokes\":[]}").canvasHeight)
        val legacy = "{\"strokes\":[{\"points\":[{\"x\":100.0,\"y\":3200.0}]}]}"
        val board = Json.decodeFromString<Whiteboard>(legacy)
        val encoded = Json.encodeToString(board)
        assertEquals(3500f, board.canvasHeight)
        assertFalse(encoded.contains("canvasHeight"))
        assertEquals(board.canvasHeight, Json.decodeFromString<Whiteboard>(encoded).canvasHeight)
        assertEquals(board, Json.decodeFromString<Whiteboard>(encoded))
    }

    private fun inkAt(y: Float) = Whiteboard(strokes = listOf(
        WhiteboardStroke(width = 40f, points = listOf(WhiteboardPoint(500f, y))),
    ))
}
