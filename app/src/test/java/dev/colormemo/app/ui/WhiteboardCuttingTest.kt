package dev.colormemo.app.ui

import dev.colormemo.app.data.whiteboardSvg
import dev.colormemo.core.model.Whiteboard
import dev.colormemo.core.model.WhiteboardImage
import dev.colormemo.core.model.WhiteboardPoint
import dev.colormemo.core.model.WhiteboardRect
import dev.colormemo.core.model.WhiteboardStroke
import dev.colormemo.core.model.WhiteboardText
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.*
import org.junit.Test
import org.w3c.dom.Element

class WhiteboardCuttingTest {
    private val cut = WhiteboardRect(200f, 200f, 400f, 400f)
    private val stroke = WhiteboardStroke(width = 40f, points = listOf(WhiteboardPoint(100f, 300f), WhiteboardPoint(700f, 300f)))
    private val image = WhiteboardImage("image", "attachment", 100f, 100f, 600f, 600f)
    private val text = WhiteboardText("text", "保留选区外文字", 100f, 300f, 600f)
    private val board = Whiteboard(listOf(stroke), listOf(image), listOf(text))

    @Test
    fun cutsAllExistingLayersAndKeepsNewInkDrawableInsideTheHole() {
        val after = board.cutOut(cut)!!
        assertEquals(stroke.points, after.strokes.single().points)
        assertEquals(listOf(cut), after.images.single().cutouts)
        assertTrue(after.strokes.single().cutouts.single().contains(WhiteboardPoint(300f, 300f)))
        assertTrue(after.texts.single().cutouts.single().contains(WhiteboardPoint(300f, 320f)))
        assertTrue(board.strokes.single().cutouts.isEmpty()) // Previous document remains usable by undo.
        val gesture = WhiteboardGestureSession(after, false, 0xFF202124L, 3f)
        val drawn = gesture.begin(WhiteboardPoint(300f, 300f))
        assertTrue(drawn.strokes.last().cutouts.isEmpty())
        assertEquals(after, gesture.startNavigation())
        assertEquals(after, after.cutOut(cut))
        assertEquals(after, after.cutOut(WhiteboardRect(800f, 2000f, 900f, 3000f)))
    }

    @Test
    fun successiveCutsCanRemoveEntireObjectsAndTheirImageReferences() {
        val left = board.cutOut(WhiteboardRect(0f, 0f, 350f, 4000f))!!
        val empty = left.cutOut(WhiteboardRect(350f, 0f, 1000f, 4000f))!!
        assertEquals(Whiteboard(), empty)
        assertFalse(empty.hasContent)
    }

    @Test
    fun selectionNormalizesReverseDragsAndNavigationRestoresPreviousRegion() {
        val drag = WhiteboardSelectionDrag(cut, WhiteboardPoint(900f, 3900f), 5f)
        assertEquals(cut, drag.update(WhiteboardPoint(899f, 3899f)))
        assertEquals(WhiteboardRect(0f, 1000f, 900f, 3900f), drag.update(WhiteboardPoint(-100f, 1000f)))
        assertEquals(cut, drag.cancel())
        assertEquals(cut, drag.update(WhiteboardPoint(500f, 3000f)))
        assertNull(WhiteboardSelectionDrag(null, WhiteboardPoint(100f, 100f), 5f).update(WhiteboardPoint(100f, 500f)))
    }

    @Test
    fun cutTextMovesWithItsHolesAndErasingKeepsStrokeHoles() {
        val after = board.cutOut(cut)!!
        assertNull(after.textAt(WhiteboardPoint(300f, 320f)))
        val clippedText = after.texts.single()
        val moved = clippedText.movedTo(200f, 600f)
        assertTrue(moved.cutouts.single().contains(WhiteboardPoint(400f, 620f)))
        assertEquals(clippedText.text, moved.text)
        val erased = after.eraseBetween(WhiteboardPoint(500f, 250f), WhiteboardPoint(500f, 350f), 10f)
        assertTrue(erased.strokes.size > 1)
        erased.strokes.forEach { assertEquals(after.strokes.single().cutouts, it.cutouts) }
    }

    @Test
    fun hittingCutoutLimitRejectsTheWholeOperationWithoutPartiallyRemovingLayers() {
        val holes = List(Whiteboard.MAX_CUTOUTS_PER_LAYER) { index -> WhiteboardRect(100f + index, 110f, 100.5f + index, 120f) }
        val full = board.copy(images = listOf(image.copy(cutouts = holes)))
        assertNull(full.cutOut(cut))
        assertEquals(holes, full.images.single().cutouts)
        // A selection covering existing holes replaces them, releasing capacity.
        assertNotNull(full.cutOut(WhiteboardRect(100f, 100f, 500f, 500f)))
    }

    @Test
    fun svgMasksOverlapAsAUnionAndOnlyAffectTheirOwnLayers() {
        val after = board.cutOut(cut)!!.cutOut(WhiteboardRect(300f, 300f, 500f, 500f))!!
        val later = WhiteboardStroke(points = listOf(WhiteboardPoint(300f, 300f)))
        val svg = whiteboardSvg(after.copy(strokes = after.strokes + later), mapOf("attachment" to "data:image/png;base64,AA=="))
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(svg.byteInputStream())
        assertEquals("0 0 1000 4000", doc.documentElement.getAttribute("viewBox"))
        val masks = doc.getElementsByTagName("mask")
        assertEquals(3, masks.length)
        val ids = mutableSetOf<String>()
        for (index in 0 until masks.length) {
            val mask = masks.item(index) as Element
            ids += mask.getAttribute("id")
            assertEquals("userSpaceOnUse", mask.getAttribute("maskUnits"))
            val rectangles = mask.getElementsByTagName("rect")
            assertEquals(3, rectangles.length)
            assertEquals("white", (rectangles.item(0) as Element).getAttribute("fill"))
            assertEquals("black", (rectangles.item(1) as Element).getAttribute("fill"))
            assertEquals("black", (rectangles.item(2) as Element).getAttribute("fill"))
        }
        assertEquals(3, ids.size)
        assertEquals("svg", doc.getElementsByTagName("circle").item(0).parentNode.nodeName)
        val groups = doc.getElementsByTagName("g")
        for (index in 0 until groups.length) {
            assertTrue(ids.contains((groups.item(index) as Element).getAttribute("mask").removePrefix("url(#").removeSuffix(")")))
        }
    }
}
