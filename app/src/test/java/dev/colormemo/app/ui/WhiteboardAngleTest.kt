package dev.colormemo.app.ui

import dev.colormemo.app.data.whiteboardSvg
import dev.colormemo.core.model.Whiteboard
import dev.colormemo.core.model.WhiteboardBrush
import dev.colormemo.core.model.WhiteboardPoint
import dev.colormemo.core.model.WhiteboardStroke
import dev.colormemo.core.sync.SyncJson
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

class WhiteboardAngleTest {
    @Test
    fun firstSegmentRemainsProvisionalUntilTheSecondSegmentIsDrawn() {
        val first = point(150f, 300f)
        val second = point(620f, 780f)
        val drag = WhiteboardTwoEdgeDrag(WhiteboardShape.ANGLE, null, first, 20f)
        assertTrue(drag.update(first).isEmpty())
        assertEquals(listOf(first, second), drag.update(second))
        assertEquals(WhiteboardTwoEdgeBase(first, second), drag.finish().base)
        assertTrue(drag.finish().drawingPoints.isEmpty())
        assertTrue(WhiteboardShape.ANGLE.points(first, second, 3f).isEmpty())
    }

    @Test
    fun eitherEndpointCanBeTheVertexAndBothSegmentsKeepTheirChosenDirections() {
        val base = WhiteboardTwoEdgeBase(point(200f, 300f), point(650f, 700f))
        base.points.forEach { vertex ->
            val other = base.points.single { it != vertex }
            val down = point(vertex.x + 8f, vertex.y - 5f)
            val drag = WhiteboardTwoEdgeDrag(WhiteboardShape.ANGLE, base, down, 20f)
            assertEquals(base.points, drag.update(down))
            val tip = point(vertex.x - 100f, vertex.y + 200f)
            assertEquals(listOf(other, vertex, tip), drag.update(point(down.x - 100f, down.y + 200f)))
            val result = drag.finish()
            assertNull(result.base)
            assertEquals(listOf(other, vertex, tip), result.drawingPoints.take(3))
            assertNotEquals(other, result.drawingPoints.last())
        }
    }

    @Test
    fun markerFollowsAcuteRightObtuseAndStraightAnglesAcrossAllDirections() {
        val vertex = point(500f, 700f)
        listOf(0f, 50f, 170f, 275f, 350f).forEach { baseline ->
            listOf(10f, 45f, 90f, 140f, 180f, 225f, 350f).forEach { opening ->
                val first = radial(vertex, baseline, 200f)
                val second = radial(vertex, baseline + opening, 300f)
                val points = WhiteboardShape.ANGLE.points(first, vertex, 3f, second)
                assertEquals(listOf(first, vertex, second), points.take(3))
                val arc = points.drop(3)
                assertTrue(arc.size >= 2)
                arc.forEach { assertEquals(44f, distance(it, vertex), 0.5f) }
                val fromVertex = point(arc.first().x - vertex.x, arc.first().y - vertex.y)
                val secondDirection = point(second.x - vertex.x, second.y - vertex.y)
                assertEquals(0f, fromVertex.x * secondDirection.y - fromVertex.y * secondDirection.x, 120f)
                assertTrue(fromVertex.x * secondDirection.x + fromVertex.y * secondDirection.y > 0f)
                val minor = minOf(opening, 360f - opening)
                val length = arc.zipWithNext().sumOf { (a, b) -> distance(a, b).toDouble() }
                assertEquals(44 * minor * PI / 180, length, 2.0)
            }
        }
    }

    @Test
    fun horizontalVerticalAndOverlappingArmsAreSupportedButTapsDoNotFinish() {
        val vertex = point(300f, 500f)
        val base = WhiteboardTwoEdgeBase(point(300f, 200f), vertex)
        val drag = WhiteboardTwoEdgeDrag(WhiteboardShape.ANGLE, base, vertex, 20f)
        assertEquals(base, drag.finish().base)
        drag.update(point(301f, 500f))
        assertEquals(base, drag.finish().base)
        drag.update(point(600f, 500f))
        assertEquals(listOf(base.first, vertex, point(600f, 500f)), drag.finish().drawingPoints.take(3))
        assertEquals(listOf(point(300f, 200f), vertex, point(300f, 100f)),
            whiteboardAnglePoints(point(300f, 200f), vertex, point(300f, 100f)))
        assertTrue(whiteboardAnglePoints(vertex, vertex, point(600f, 500f)).isEmpty())
    }

    @Test
    fun markerStaysCircularNearTheBoundaryAndCanFitAtACorner() {
        val vertex = point(990f, 500f)
        val points = whiteboardAnglePoints(point(1000f, 700f), vertex, point(1000f, 300f))
        points.drop(3).forEach { assertEquals(10f, distance(it, vertex), 0.5f) }
        val corner = point(0f, 0f)
        val atCorner = whiteboardAnglePoints(point(200f, 0f), corner, point(0f, 300f))
        assertTrue(atCorner.size > 3)
        atCorner.drop(3).forEach { assertEquals(44f, distance(it, corner), 0.5f) }
        (points + atCorner).forEach {
            assertTrue(it.x in 0f..Whiteboard.CANVAS_WIDTH && it.y in 0f..Whiteboard.CANVAS_HEIGHT)
        }
    }

    @Test
    fun secondFingerRestoresTheFirstSegmentAndIgnoresLaterDrawingPoints() {
        val base = WhiteboardTwoEdgeBase(point(100f, 300f), point(600f, 800f))
        val drag = WhiteboardTwoEdgeDrag(WhiteboardShape.ANGLE, base, base.second, 20f)
        drag.update(point(700f, 200f))
        assertEquals(base.points, drag.startNavigation())
        assertEquals(base.points, drag.update(point(300f, 150f)))
        assertEquals(base, drag.finish().base)
        assertTrue(drag.finish().drawingPoints.isEmpty())
    }

    @Test
    fun completedAngleIsOneStyledStrokeForSavingExportAndUndo() {
        val base = WhiteboardTwoEdgeBase(point(100f, 200f), point(700f, 200f))
        val drag = WhiteboardTwoEdgeDrag(WhiteboardShape.ANGLE, base, base.second, 20f)
        drag.update(point(300f, 900f))
        val points = drag.finish().drawingPoints
        val existing = WhiteboardStroke(points = listOf(point(50f, 50f)))
        val before = Whiteboard(listOf(existing))
        val session = WhiteboardGestureSession(before, false, 0xFF1967D2L, 16f, WhiteboardBrush.MARKER)
        val board = session.previewStroke(points)
        assertEquals(listOf(existing, WhiteboardStroke(0xFF1967D2L, 16f, points, WhiteboardBrush.MARKER)), board.strokes)
        assertEquals(board, SyncJson.json.decodeFromString<Whiteboard>(SyncJson.json.encodeToString(board)))
        val svg = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(whiteboardSvg(board).byteInputStream())
        assertEquals(1, svg.getElementsByTagName("path").length)
        assertTrue(session.hasChanges)
        assertSame(before, session.startNavigation())
        assertFalse(session.hasChanges)
    }

    @Test
    fun capacityAcceptsTheEntireAngleOrPreservesTheOriginalBoard() {
        val points = whiteboardAnglePoints(point(100f, 200f), point(700f, 200f), point(300f, 900f))
        val existing = WhiteboardStroke(points = List(Whiteboard.MAX_POINTS - points.size) { point(0f, 0f) })
        val before = Whiteboard(listOf(existing))
        val session = WhiteboardGestureSession(before, false, 0xFF202124L, 3f)
        assertEquals(Whiteboard.MAX_POINTS, session.previewStroke(points).strokes.sumOf { it.points.size })
        val full = before.copy(strokes = listOf(existing.copy(points = existing.points + point(0f, 0f))))
        val rejected = WhiteboardGestureSession(full, false, 0xFF202124L, 3f)
        assertSame(full, rejected.previewStroke(points))
        assertFalse(rejected.hasChanges)
        assertTrue(rejected.limitReached)
    }

    private fun point(x: Float, y: Float) = WhiteboardPoint(x, y)

    private fun radial(center: WhiteboardPoint, degrees: Float, length: Float): WhiteboardPoint {
        val radians = degrees * PI / 180
        return whiteboardPoint(center.x + cos(radians).toFloat() * length, center.y + sin(radians).toFloat() * length)
    }

    private fun distance(a: WhiteboardPoint, b: WhiteboardPoint): Float =
        sqrt((a.x - b.x) * (a.x - b.x) + (a.y - b.y) * (a.y - b.y))
}
