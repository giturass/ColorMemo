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
        listOf(WhiteboardShape.RECTANGLE, WhiteboardShape.ELLIPSE, WhiteboardShape.TRIANGLE,
            WhiteboardShape.DIAMOND, WhiteboardShape.TRAPEZOID, WhiteboardShape.HEXAGON).forEach { shape ->
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
    fun squareKeepsEqualSidesInAllDragDirections() {
        val start = point(500f, 500f)
        listOf(point(800f, 900f), point(200f, 900f), point(800f, 100f), point(200f, 100f)).forEach { end ->
            val points = WhiteboardShape.SQUARE.points(start, end, 3f)
            assertEquals(points.first(), points.last())
            assertEquals(4, points.distinct().size)
            points.zipWithNext().forEach { (a, b) ->
                assertEquals(300f, kotlin.math.abs(a.x - b.x) + kotlin.math.abs(a.y - b.y))
                assertTrue(a.x == b.x || a.y == b.y)
            }
            points.forEach { p ->
                assertTrue(p.x in minOf(start.x, end.x)..maxOf(start.x, end.x))
                assertTrue(p.y in minOf(start.y, end.y)..maxOf(start.y, end.y))
            }
        }
    }

    @Test
    fun curvedArrowBendsAndItsHeadFollowsTheFinalTangent() {
        val start = point(200f, 700f)
        val end = point(800f, 700f)
        val points = WhiteboardShape.CURVED_ARROW.points(start, end, 6f)
        val curve = points.dropLast(3)
        assertEquals(start, curve.first())
        assertEquals(end, curve.last())
        assertTrue(curve.minOf { it.y } < 650f)
        val previous = curve.dropLast(1).last()
        val dx = end.x - previous.x
        val dy = end.y - previous.y
        val wings = listOf(points[points.lastIndex - 2], points.last())
        assertWingsBehindTip(end, dx, dy, wings)
    }

    @Test
    fun doubleArrowHasOpposingHeadsInEveryDirection() {
        val start = point(500f, 500f)
        listOf(point(800f, 500f), point(200f, 500f), point(500f, 800f), point(500f, 200f),
            point(800f, 800f), point(200f, 200f)).forEach { end ->
            val points = WhiteboardShape.DOUBLE_ARROW.points(start, end, 6f)
            val dx = end.x - start.x
            val dy = end.y - start.y
            assertWingsBehindTip(start, -dx, -dy, listOf(points[0], points[2]))
            assertWingsBehindTip(end, dx, dy, listOf(points[5], points[7]))
            assertTrue(points.zipWithNext().any { (a, b) -> a == start && b == end })
        }
    }

    @Test
    fun cylinderAndConeHaveCurvedRimsWithoutLinesAcrossTheirBases() {
        listOf(WhiteboardShape.CYLINDER, WhiteboardShape.CONE).forEach { shape ->
            val points = shape.points(point(100f, 200f), point(700f, 1000f), 3f)
            assertEquals(points, shape.points(point(700f, 1000f), point(100f, 200f), 3f))
            assertEquals(100f, points.minOf { it.x })
            assertEquals(700f, points.maxOf { it.x })
            assertEquals(200f, points.minOf { it.y })
            assertEquals(1000f, points.maxOf { it.y })
            points.zipWithNext().filter { (a, b) -> a.y > 800f && b.y > 800f }.forEach { (a, b) ->
                assertTrue(kotlin.math.abs(a.x - b.x) < 50f)
            }
        }
    }

    @Test
    fun cubeHasThreeSquareFaceEdgesAndSphereKeepsRoundOutline() {
        val cube = WhiteboardShape.CUBE.points(point(100f, 200f), point(700f, 1000f), 3f)
        val aligned = cube.zipWithNext().filter { (a, b) -> a.x == b.x || a.y == b.y }
        assertEquals(6, aligned.size)
        aligned.forEach { (a, b) -> assertEquals(432f, kotlin.math.abs(a.x - b.x) + kotlin.math.abs(a.y - b.y)) }
        val sphere = WhiteboardShape.SPHERE.points(point(100f, 200f), point(700f, 1000f), 3f)
        assertEquals(600f, sphere.maxOf { it.x } - sphere.minOf { it.x })
        assertEquals(600f, sphere.maxOf { it.y } - sphere.minOf { it.y })
        sphere.forEach { p ->
            val dx = p.x - 400f
            val dy = p.y - 500f
            assertTrue(dx * dx + dy * dy <= 301f * 301f)
        }
    }

    @Test
    fun coordinateAxesCrossAtTheCenterAndOnlyArrowheadsUseDiagonalLines() {
        val points = WhiteboardShape.COORDINATE_AXES.points(point(100f, 200f), point(700f, 1000f), 3f)
        val origin = point(400f, 600f)
        val xTip = point(700f, 600f)
        val yTip = point(400f, 200f)
        assertTrue(points.contains(origin))
        assertTrue(points.contains(point(100f, 600f)))
        assertTrue(points.contains(point(400f, 1000f)))
        val diagonals = points.zipWithNext().filter { (a, b) -> a.x != b.x && a.y != b.y }
        assertTrue(diagonals.isNotEmpty())
        diagonals.forEach { (a, b) -> assertTrue(a == xTip || b == xTip || a == yTip || b == yTip) }
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
            listOf(point(0f, 0f) to point(1000f, 2000f), point(1f, 1f) to point(0f, 0f),
                point(0.5f, 0f) to point(0f, 0f), point(500f, 500f) to point(500.5f, 500.5f)).forEach { (start, end) ->
                shape.points(start, end, 40f).forEach { p ->
                    assertTrue("$shape $start -> $end produced $p", p.x.isFinite() && p.x in 0f..Whiteboard.CANVAS_WIDTH)
                    assertTrue("$shape $start -> $end produced $p", p.y.isFinite() && p.y in 0f..Whiteboard.CANVAS_HEIGHT)
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
    fun shapesUseExistingJsonAndSvgFormatsAndKeepTheirOutsidePartsAfterErasing() {
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
            val erased = session.working.eraseBetween(hit, hit, 8f)
            assertTrue(erased.strokes.isNotEmpty())
            assertNotEquals(session.working, erased)
            assertEquals(erased, SyncJson.json.decodeFromString<Whiteboard>(SyncJson.json.encodeToString(erased)))
            val erasedDocument = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(whiteboardSvg(erased).byteInputStream())
            assertEquals(erased.strokes.size, erasedDocument.getElementsByTagName("path").length)
        }
    }

    private fun point(x: Float, y: Float) = WhiteboardPoint(x, y)

    private fun assertWingsBehindTip(tip: WhiteboardPoint, dx: Float, dy: Float, wings: List<WhiteboardPoint>) {
        wings.forEach { wing -> assertTrue((wing.x - tip.x) * dx + (wing.y - tip.y) * dy < 0f) }
        val crosses = wings.map { (it.x - tip.x) * dy - (it.y - tip.y) * dx }
        assertTrue(crosses[0] * crosses[1] < 0f)
    }
}
