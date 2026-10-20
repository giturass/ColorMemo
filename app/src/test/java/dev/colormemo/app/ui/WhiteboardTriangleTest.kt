package dev.colormemo.app.ui

import dev.colormemo.app.data.whiteboardSvg
import dev.colormemo.core.model.Whiteboard
import dev.colormemo.core.model.WhiteboardBrush
import dev.colormemo.core.model.WhiteboardPoint
import dev.colormemo.core.model.WhiteboardStroke
import dev.colormemo.core.sync.SyncJson
import javax.xml.parsers.DocumentBuilderFactory
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

class WhiteboardTriangleTest {
    @Test
    fun firstDragKeepsOnlyTheChosenEdgeAndDoesNotCompleteATriangle() {
        val first = point(150f, 300f)
        val second = point(620f, 780f)
        val drag = WhiteboardTwoEdgeDrag(WhiteboardShape.TRIANGLE, null, first, 20f)
        assertTrue(drag.update(first).isEmpty())
        assertEquals(listOf(first, second), drag.update(second))
        val result = drag.finish()
        assertEquals(WhiteboardTwoEdgeBase(first, second), result.base)
        assertTrue(result.drawingPoints.isEmpty())
        assertTrue(WhiteboardShape.TRIANGLE.points(first, second, 3f).isEmpty())
    }

    @Test
    fun secondDragShowsTwoEdgesAndOnlyClosesTheTriangleOnRelease() {
        val first = point(150f, 300f)
        val second = point(620f, 780f)
        val third = point(300f, 900f)
        val base = WhiteboardTwoEdgeBase(first, second)
        val drag = WhiteboardTwoEdgeDrag(WhiteboardShape.TRIANGLE, base, second, 20f)
        assertEquals(base.points, drag.update(second))
        assertEquals(listOf(first, second, third), drag.update(third))
        val result = drag.finish()
        assertNull(result.base)
        assertEquals(listOf(first, second, third, first), result.drawingPoints)
    }

    @Test
    fun eitherEndpointCanStartTheSecondEdgeAndAnOffsetGrabDoesNotMoveIt() {
        val base = WhiteboardTwoEdgeBase(point(200f, 300f), point(650f, 700f))
        val down = point(208f, 295f)
        val drag = WhiteboardTwoEdgeDrag(WhiteboardShape.TRIANGLE, base, down, 20f)
        assertEquals(base.points, drag.update(down))
        drag.update(point(308f, 175f))
        assertEquals(listOf(base.second, base.first, point(300f, 180f), base.second), drag.finish().drawingPoints)
    }

    @Test
    fun tapsShortEdgesAndCollinearVerticesKeepTheConstructionUnfinished() {
        val first = point(100f, 300f)
        val tap = WhiteboardTwoEdgeDrag(WhiteboardShape.TRIANGLE, null, first, 20f)
        tap.update(first)
        assertNull(tap.finish().base)
        tap.update(point(101f, 300f))
        assertNull(tap.finish().base)
        val base = WhiteboardTwoEdgeBase(first, point(600f, 800f))
        val second = WhiteboardTwoEdgeDrag(WhiteboardShape.TRIANGLE, base, base.second, 20f)
        assertEquals(base, second.finish().base)
        second.update(point(800f, 1000f))
        assertEquals(base, second.finish().base)
        assertTrue(second.finish().drawingPoints.isEmpty())
        second.update(point(800f, 900f))
        assertNull(second.finish().base)
        assertEquals(4, second.finish().drawingPoints.size)
    }

    @Test
    fun secondEdgeMustStartNearAnExistingEndpoint() {
        val base = WhiteboardTwoEdgeBase(point(100f, 300f), point(600f, 800f))
        val drag = WhiteboardTwoEdgeDrag(WhiteboardShape.TRIANGLE, base, point(350f, 550f), 20f)
        assertEquals(base.points, drag.update(point(700f, 200f)))
        assertEquals(base, drag.finish().base)
        assertTrue(drag.finish().drawingPoints.isEmpty())
    }

    @Test
    fun secondFingerDiscardsOnlyTheCurrentEdgeAndKeepsThePreviousBase() {
        val base = WhiteboardTwoEdgeBase(point(100f, 300f), point(600f, 800f))
        listOf(null, base).forEach { before ->
            val drag = WhiteboardTwoEdgeDrag(WhiteboardShape.TRIANGLE, before, before?.second ?: point(100f, 300f), 20f)
            drag.update(point(700f, 200f))
            assertEquals(before?.points.orEmpty(), drag.startNavigation())
            assertEquals(before?.points.orEmpty(), drag.update(point(300f, 150f)))
            assertEquals(before, drag.finish().base)
            assertTrue(drag.finish().drawingPoints.isEmpty())
        }
    }

    @Test
    fun arbitraryVerticesIncludingHorizontalAndVerticalEdgesArePreserved() {
        listOf(
            listOf(point(100f, 200f), point(700f, 200f), point(300f, 900f)),
            listOf(point(700f, 200f), point(700f, 900f), point(100f, 600f)),
            listOf(point(800f, 900f), point(100f, 300f), point(600f, 200f)),
        ).forEach { (a, b, c) ->
            assertEquals(listOf(a, b, c, a), WhiteboardShape.TRIANGLE.points(a, b, 3f, c))
        }
    }

    @Test
    fun completedTriangleIsOneStyledStrokeForUndoSavingAndExport() {
        val base = WhiteboardTwoEdgeBase(point(100f, 200f), point(700f, 200f))
        val drag = WhiteboardTwoEdgeDrag(WhiteboardShape.TRIANGLE, base, base.second, 20f)
        drag.update(point(300f, 900f))
        val points = drag.finish().drawingPoints
        WhiteboardBrush.entries.forEach { brush ->
            val existing = WhiteboardStroke(points = listOf(point(50f, 50f)))
            val before = Whiteboard(listOf(existing))
            val session = WhiteboardGestureSession(before, false, 0xFF1967D2L, 16f, brush)
            assertFalse(session.hasChanges)
            val board = session.previewStroke(points)
            assertTrue(session.hasChanges)
            assertEquals(before, session.before)
            assertEquals(listOf(existing, WhiteboardStroke(0xFF1967D2L, 16f, points, brush)), board.strokes)
            assertEquals(board, SyncJson.json.decodeFromString<Whiteboard>(SyncJson.json.encodeToString(board)))
            val svg = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(whiteboardSvg(board).byteInputStream())
            assertTrue(svg.getElementsByTagName("path").length > 0)
            assertSame(before, session.startNavigation())
            assertFalse(session.hasChanges)
        }
    }

    @Test
    fun capacityAcceptsTheWholeTriangleOrPreservesTheOriginalBoard() {
        val points = whiteboardTrianglePoints(point(100f, 200f), point(700f, 200f), point(300f, 900f))
        val existing = WhiteboardStroke(points = List(Whiteboard.MAX_POINTS - points.size) { point(0f, 0f) })
        val before = Whiteboard(listOf(existing))
        val session = WhiteboardGestureSession(before, false, 0xFF202124L, 3f)
        assertEquals(Whiteboard.MAX_POINTS, session.previewStroke(points).strokes.sumOf { it.points.size })
        assertFalse(session.limitReached)
        val full = before.copy(strokes = listOf(existing.copy(points = existing.points + point(0f, 0f))))
        val rejected = WhiteboardGestureSession(full, false, 0xFF202124L, 3f)
        assertSame(full, rejected.previewStroke(points))
        assertFalse(rejected.hasChanges)
        assertTrue(rejected.limitReached)
    }

    private fun point(x: Float, y: Float) = WhiteboardPoint(x, y)
}
