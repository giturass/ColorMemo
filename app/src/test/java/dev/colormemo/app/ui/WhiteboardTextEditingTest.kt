package dev.colormemo.app.ui

import dev.colormemo.app.data.whiteboardSvg
import dev.colormemo.core.model.Whiteboard
import dev.colormemo.core.model.WhiteboardImage
import dev.colormemo.core.model.WhiteboardPoint
import dev.colormemo.core.model.WhiteboardStroke
import dev.colormemo.core.model.WhiteboardText
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.*
import org.junit.Test

class WhiteboardTextEditingTest {
    private val text = WhiteboardText("text", "第一行\n第二行", 100f, 200f, 200f, 32f, 0xFF1967D2L)

    @Test
    fun movementClampsTheWholeTextBoxAndPreservesOtherLayers() {
        val board = Whiteboard(strokes = listOf(WhiteboardStroke(points = listOf(WhiteboardPoint(0f, 0f)))),
            images = listOf(WhiteboardImage("image", "file", 0f, 0f, 100f, 100f)), texts = listOf(text))
        val drag = WhiteboardTextDrag(board, text, WhiteboardPoint(110f, 210f), 5f)
        assertSame(board, drag.update(WhiteboardPoint(111f, 211f)))
        val moved = drag.update(WhiteboardPoint(2000f, 5000f))
        val box = moved.texts.single()
        assertEquals(Whiteboard.CANVAS_WIDTH, box.x + box.width)
        assertEquals(Whiteboard.CANVAS_HEIGHT, box.y + box.height)
        assertEquals(text.text, box.text)
        assertEquals(text.color, box.color)
        assertEquals(board.images, moved.images)
        assertEquals(board.strokes, moved.strokes)
        assertTrue(drag.hasChanges)
        assertEquals(board, drag.startNavigation())
        assertFalse(drag.hasChanges)
        assertEquals(board, drag.update(WhiteboardPoint(500f, 500f)))
    }

    @Test
    fun hitTestingUsesTheTopmostTextAndSupportsSmallTouchPadding() {
        val top = text.copy(id = "top")
        val board = Whiteboard(texts = listOf(text, top))
        assertEquals(top, board.textAt(WhiteboardPoint(150f, 250f)))
        assertNull(board.textAt(WhiteboardPoint(99f, 250f)))
        assertEquals(top, board.textAt(WhiteboardPoint(99f, 250f), 4f))
        assertTrue(board.hasContent)
        assertFalse(Whiteboard().hasContent)
    }

    @Test
    fun inkAndErasingPreserveEditableText() {
        val before = Whiteboard(texts = listOf(text))
        val drawing = WhiteboardGestureSession(before, false, 0xFF202124L, 3f, shape = WhiteboardShape.CUBE)
        drawing.begin(WhiteboardPoint(100f, 200f))
        val after = drawing.update(WhiteboardPoint(500f, 600f), finalPoint = true)
        assertEquals(before.texts, after.texts)
        val erased = after.eraseBetween(WhiteboardPoint(0f, 0f), WhiteboardPoint(1000f, 2000f), 100f)
        assertEquals(before.texts, erased.texts)
    }

    @Test
    fun svgKeepsMultilineUnicodeTextLiteralAndPlacesItAboveInk() {
        val value = text.copy(text = "  <script>& \"你好\" 😀\n第二行")
        val board = Whiteboard(strokes = listOf(WhiteboardStroke(points = listOf(WhiteboardPoint(10f, 20f), WhiteboardPoint(30f, 40f)))), texts = listOf(value))
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(whiteboardSvg(board).byteInputStream())
        assertEquals(0, document.getElementsByTagName("script").length)
        val element = document.getElementsByTagName("text").item(0) as org.w3c.dom.Element
        assertEquals("32.0", element.getAttribute("font-size"))
        assertEquals("#1967d2", element.getAttribute("fill"))
        assertEquals("preserve", element.getAttribute("xml:space"))
        val lines = element.getElementsByTagName("tspan")
        assertEquals(2, lines.length)
        assertEquals(value.text.substringBefore('\n'), lines.item(0).textContent)
        assertEquals("第二行", lines.item(1).textContent)
        assertEquals("232.0", (lines.item(0) as org.w3c.dom.Element).getAttribute("y"))
        assertEquals("272.0", (lines.item(1) as org.w3c.dom.Element).getAttribute("y"))
        assertTrue(whiteboardSvg(board).indexOf("<text") > whiteboardSvg(board).indexOf("<path"))
    }
}
