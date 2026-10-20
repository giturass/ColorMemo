package dev.colormemo.app.ui

import dev.colormemo.app.data.whiteboardSvg
import dev.colormemo.core.model.Whiteboard
import dev.colormemo.core.model.WhiteboardBrush
import dev.colormemo.core.model.WhiteboardImage
import dev.colormemo.core.model.WhiteboardPoint
import dev.colormemo.core.model.WhiteboardStroke
import dev.colormemo.core.sync.SyncJson
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.sqrt
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class WhiteboardShapesTest {
    @Test
    fun rectangleClosesItsOutlineWithoutInteriorDiagonalsWhenDraggedBackwards() {
        val points = WhiteboardShape.RECTANGLE.points(point(700f, 900f), point(100f, 200f), 3f)

        assertEquals(listOf(point(100f, 200f), point(700f, 200f), point(700f, 900f),
            point(100f, 900f), point(100f, 200f)), points)
    }

    @Test
    fun closedTemplatesAreIndependentOfDragDirection() {
        listOf(WhiteboardShape.RECTANGLE, WhiteboardShape.ELLIPSE, WhiteboardShape.TRIANGLE, WhiteboardShape.DIAMOND).forEach { shape ->
            val points = shape.points(point(100f, 200f), point(700f, 900f), 3f)
            assertEquals(points, shape.points(point(700f, 900f), point(100f, 200f), 3f))
            assertEquals(points.first(), points.last())
            assertEquals(100f, points.minOf { it.x })
            assertEquals(700f, points.maxOf { it.x })
            assertEquals(200f, points.minOf { it.y })
            assertEquals(900f, points.maxOf { it.y })
        }
    }

    @Test
    fun circleKeepsEqualRadiiAndFitsTheDragBoundsInEveryQuadrant() {
        val start = point(500f, 500f)
        listOf(point(700f, 900f), point(300f, 900f), point(700f, 100f), point(300f, 100f)).forEach { end ->
            val points = WhiteboardShape.CIRCLE.points(start, end, 3f)
            val centerX = (points.minOf { it.x } + points.maxOf { it.x }) / 2f
            val centerY = (points.minOf { it.y } + points.maxOf { it.y }) / 2f
            assertEquals(200f, points.maxOf { it.x } - points.minOf { it.x })
            assertEquals(200f, points.maxOf { it.y } - points.minOf { it.y })
            assertEquals(points.first(), points.last())
            points.forEach { p ->
                val dx = p.x - centerX
                val dy = p.y - centerY
                assertEquals(100f, sqrt(dx * dx + dy * dy), 0.5f)
                assertTrue(p.x in minOf(start.x, end.x)..maxOf(start.x, end.x))
                assertTrue(p.y in minOf(start.y, end.y)..maxOf(start.y, end.y))
            }
        }
    }

    @Test
    fun ellipseSamplesStayOnTheOutline() {
        val points = WhiteboardShape.ELLIPSE.points(point(100f, 200f), point(900f, 1800f), 3f)
        points.forEach { p ->
            val x = (p.x - 500f) / 400f
            val y = (p.y - 1000f) / 800f
            assertEquals(1f, x * x + y * y, 0.003f)
        }
    }

    @Test
    fun arrowPointsTowardReleaseWithTwoWingsBehindItsTip() {
        val start = point(500f, 500f)
        listOf(point(800f, 500f), point(200f, 500f), point(500f, 800f), point(500f, 200f),
            point(800f, 800f), point(200f, 200f)).forEach { end ->
            val points = WhiteboardShape.ARROW.points(start, end, 6f)
            assertEquals(start, points.first())
            assertEquals(end, points[1])
            assertEquals(end, points[3])
            val dx = end.x - start.x
            val dy = end.y - start.y
            val wings = listOf(points[2], points[4])
            wings.forEach { wing -> assertTrue((wing.x - end.x) * dx + (wing.y - end.y) * dy < 0f) }
            val crosses = wings.map { (it.x - end.x) * dy - (it.y - end.y) * dx }
            assertTrue(crosses[0] * crosses[1] < 0f)
        }
    }

    @Test
    fun templatesStayInsideTheCanvasAndDoNotTurnTapsIntoDots() {
        WhiteboardShape.entries.forEach { shape ->
            assertTrue(shape.points(point(500f, 500f), point(500f, 500f), 40f).isEmpty())
            listOf(point(0f, 0f) to point(1000f, 2000f), point(1f, 1f) to point(0f, 0f)).forEach { (start, end) ->
                shape.points(start, end, 40f).forEach { p ->
                    assertTrue(p.x.isFinite() && p.x in 0f..Whiteboard.CANVAS_WIDTH)
                    assertTrue(p.y.isFinite() && p.y in 0f..Whiteboard.CANVAS_HEIGHT)
                }
            }
        }
        assertEquals(2, WhiteboardShape.LINE.points(point(100f, 100f), point(100f, 900f), 3f).size)
        assertTrue(WhiteboardShape.RECTANGLE.points(point(100f, 100f), point(100f, 900f), 3f).isEmpty())
    }

    @Test
    fun dragReplacesOnePreviewStrokeAndPreservesInkAndImages() {
        val image = WhiteboardImage("image", "attachment", 0f, 0f, 100f, 100f)
        val before = Whiteboard(images = listOf(image))
        WhiteboardShape.entries.forEach { shape ->
            WhiteboardBrush.entries.forEach { brush ->
                val session = WhiteboardGestureSession(before, false, 0xFF1967D2L, 16f, brush, shape)
                assertSame(before, session.begin(point(100f, 100f)))
                val firstPreview = session.update(point(300f, 400f))
                val finalPreview = session.update(point(700f, 900f), finalPoint = true)
                assertNotEquals(firstPreview, finalPreview)
                assertEquals(1, finalPreview.strokes.size)
                val stroke = finalPreview.strokes.single()
                assertEquals(0xFF1967D2L, stroke.color)
                assertEquals(16f, stroke.width)
                assertEquals(brush, stroke.brush)
                assertEquals(listOf(image), finalPreview.images)
                assertEquals(before, session.before)
                assertTrue(session.hasChanges)

                assertSame(before, session.startNavigation())
                session.update(point(900f, 1000f), finalPoint = true)
                assertFalse(session.hasChanges)
            }
        }
    }

    @Test
    fun returningToTheAnchorRemovesThePreviewAndCreatesNoUndoEntry() {
        val session = WhiteboardGestureSession(Whiteboard(), false, 0xFF202124L, 3f, shape = WhiteboardShape.RECTANGLE)
        session.begin(point(100f, 100f))
        session.update(point(700f, 900f))
        assertTrue(session.hasChanges)

        session.update(point(100f, 100f), finalPoint = true)

        assertSame(session.before, session.working)
        assertFalse(session.hasChanges)
    }

    @Test
    fun capacityRejectsTheWholeShapeAndExactCapacityStillAllowsPreviewUpdates() {
        val existing = WhiteboardStroke(points = List(Whiteboard.MAX_POINTS - 5) { point(0f, 0f) })
        val before = Whiteboard(listOf(existing))
        val session = WhiteboardGestureSession(before, false, 0xFF202124L, 3f, shape = WhiteboardShape.RECTANGLE)
        session.begin(point(100f, 100f))
        session.update(point(300f, 400f))
        session.update(point(700f, 900f), finalPoint = true)
        assertEquals(Whiteboard.MAX_POINTS, session.working.strokes.sumOf { it.points.size })
        assertEquals(700f, session.working.strokes.last().points.maxOf { it.x })
        assertFalse(session.limitReached)

        val almostFull = before.copy(strokes = listOf(existing.copy(points = existing.points + point(0f, 0f))))
        val rejected = WhiteboardGestureSession(almostFull, false, 0xFF202124L, 3f, shape = WhiteboardShape.RECTANGLE)
        rejected.begin(point(100f, 100f))
        rejected.update(point(700f, 900f), finalPoint = true)
        assertSame(almostFull, rejected.working)
        assertTrue(rejected.limitReached)
        assertFalse(rejected.hasChanges)
        rejected.startNavigation()
        assertFalse(rejected.limitReached)
    }

    @Test
    fun strokeLimitPreventsShapesAndStillAllowsNavigation() {
        val dot = WhiteboardStroke(points = listOf(point(0f, 0f)))
        val before = Whiteboard(strokes = List(Whiteboard.MAX_STROKES) { dot })
        val session = WhiteboardGestureSession(before, false, 0xFF202124L, 3f, shape = WhiteboardShape.LINE)
        session.begin(point(100f, 100f))
        session.update(point(700f, 900f), finalPoint = true)
        assertSame(before, session.working)
        assertTrue(session.limitReached)
        assertFalse(session.hasChanges)
        session.startNavigation()
        assertTrue(session.isNavigating)
        assertFalse(session.limitReached)
    }

    @Test
    fun shapesUseExistingJsonAndSvgFormatsAndEraseAsOneStroke() {
        val sessions = WhiteboardShape.entries.map { shape ->
            WhiteboardGestureSession(Whiteboard(), false, 0xFF1967D2L, 6f, shape = shape).apply {
                begin(point(100f, 200f))
                update(point(700f, 900f), finalPoint = true)
            }
        }
        val board = Whiteboard(strokes = sessions.map { it.working.strokes.single() })
        val json = SyncJson.json.encodeToString(board)
        assertEquals(board, SyncJson.json.decodeFromString<Whiteboard>(json))
        assertFalse(json.contains("shape"))
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(whiteboardSvg(board).byteInputStream())
        assertEquals(WhiteboardShape.entries.size, document.getElementsByTagName("path").length)
        sessions.forEach { session ->
            val stroke = session.working.strokes.single()
            val hit = stroke.points.first()
            assertTrue(session.working.eraseBetween(hit, hit, 8f).strokes.isEmpty())
        }
    }

    private fun point(x: Float, y: Float) = WhiteboardPoint(x, y)
}
