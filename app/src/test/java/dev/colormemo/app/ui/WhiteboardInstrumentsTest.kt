package dev.colormemo.app.ui

import dev.colormemo.app.data.whiteboardSvg
import dev.colormemo.core.model.Whiteboard
import dev.colormemo.core.model.WhiteboardBrush
import dev.colormemo.core.model.WhiteboardImage
import dev.colormemo.core.model.WhiteboardPoint
import dev.colormemo.core.model.WhiteboardStroke
import dev.colormemo.core.sync.SyncJson
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sqrt
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

class WhiteboardInstrumentsTest {
    @Test
    fun compassPenKeepsItsNeedleAndRadiusFixedAndDrawsOnlyTheTracedArc() {
        val initial = WhiteboardInstrumentState(radius = 100f)
        val drag = compassDrag(initial)
        val adjusted = drag.update(WhiteboardPoint(500f, 250f))
        assertEquals(initial.center, adjusted.center)
        assertEquals(100f, adjusted.radius, 0.01f)
        assertEquals(90f, adjusted.baselineDegrees, 0.01f)
        val points = drag.drawingPoints
        assertEquals(WhiteboardPoint(600f, 500f), points.first())
        assertEquals(WhiteboardPoint(500f, 400f), points.last())
        assertNotEquals(points.first(), points.last())
        points.forEach { p ->
            assertEquals(100f, sqrt((p.x - adjusted.centerX) * (p.x - adjusted.centerX) +
                (p.y - adjusted.centerY) * (p.y - adjusted.centerY)), 0.4f)
            assertTrue(p.x in 500f..600f && p.y in 400f..500f)
        }
        assertEquals(100 * PI / 2, pathLength(points), 2.0)
    }

    @Test
    fun compassCrossesZeroInBothDirectionsAndKeepsInkWhenReversing() {
        listOf(1f, -1f).forEach { direction ->
            val initial = WhiteboardInstrumentState(radius = 100f, baselineDegrees = if (direction > 0f) 350f else 10f)
            val drag = compassDrag(initial)
            drag.update(initial.pointAt(initial.baselineDegrees + direction * 20f))
            val firstArc = drag.drawingPoints
            assertEquals(100 * PI / 9, pathLength(firstArc), 1.0)
            drag.update(initial.pointAt(initial.baselineDegrees + direction * 80f, 200f))
            val furthest = drag.drawingPoints.last()
            drag.update(initial.pointAt(initial.baselineDegrees + direction * 20f, 150f))
            assertEquals(firstArc.last(), drag.drawingPoints.last())
            assertTrue(drag.drawingPoints.contains(furthest))
            assertEquals(100 * 140 * PI / 180, pathLength(drag.drawingPoints), 3.0)
        }
    }

    @Test
    fun compassCanDrawAFullTurnAndContinueWithoutLosingItsSweep() {
        val initial = WhiteboardInstrumentState(radius = 150f, baselineDegrees = 330f)
        val drag = compassDrag(initial)
        for (sweep in 60..420 step 60) {
            drag.update(initial.pointAt(initial.baselineDegrees + sweep))
            if (sweep == 360) assertEquals(drag.drawingPoints.first(), drag.drawingPoints.last())
        }
        assertNotEquals(drag.drawingPoints.first(), drag.drawingPoints.last())
        assertEquals(150 * 420 * PI / 180, pathLength(drag.drawingPoints), 4.0)
    }

    @Test
    fun compassTapRadialMotionAndMovingTheNeedleDoNotDraw() {
        val initial = WhiteboardInstrumentState(radius = 100f)
        val drag = compassDrag(initial)
        assertEquals(initial, drag.update(initial.handlePoint(InstrumentHandle.PEN)))
        assertEquals(initial, drag.update(initial.pointAt(0f, 250f)))
        assertEquals(initial, drag.update(initial.center))
        assertTrue(drag.drawingPoints.isEmpty())
        val offsetStart = initial.pointAt(5f, 105f)
        val offsetDrag = WhiteboardInstrumentDrag(initial, WhiteboardInstrument.COMPASS, offsetStart, 20f)
        assertEquals(initial, offsetDrag.update(offsetStart))
        assertTrue(offsetDrag.drawingPoints.isEmpty())
        val moving = WhiteboardInstrumentDrag(initial, WhiteboardInstrument.COMPASS, initial.center, 20f)
        assertNotEquals(initial.center, moving.update(WhiteboardPoint(700f, 900f)).center)
        assertTrue(moving.drawingPoints.isEmpty())
    }

    @Test
    fun compassPreviewIsOneStyledStrokeThatCanBeSavedAndRolledBackForNavigation() {
        val initial = WhiteboardInstrumentState(radius = 100f)
        val image = WhiteboardImage("image", "file", 0f, 0f, 100f, 100f)
        val before = Whiteboard(images = listOf(image))
        val drag = compassDrag(initial)
        val session = WhiteboardGestureSession(before, false, 0xFF1967D2L, 16f, WhiteboardBrush.WATERCOLOR)
        assertSame(before, session.previewStroke(drag.drawingPoints))
        listOf(30f, 90f, 120f).forEach { angle ->
            drag.update(initial.pointAt(angle))
            session.previewStroke(drag.drawingPoints)
            assertEquals(1, session.working.strokes.size)
        }
        val board = session.working
        val stroke = board.strokes.single()
        assertEquals(0xFF1967D2L, stroke.color)
        assertEquals(16f, stroke.width)
        assertEquals(WhiteboardBrush.WATERCOLOR, stroke.brush)
        assertEquals(before.images, board.images)
        assertEquals(board, SyncJson.json.decodeFromString<Whiteboard>(SyncJson.json.encodeToString(board)))
        val svg = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(
            whiteboardSvg(board, mapOf("file" to "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Y9Zl1sAAAAASUVORK5CYII=")).byteInputStream(),
        )
        assertTrue(svg.getElementsByTagName("path").length > 0)
        assertEquals(initial, drag.startNavigation())
        assertSame(before, session.startNavigation())
        assertEquals(initial, drag.update(initial.pointAt(180f)))
        assertTrue(drag.drawingPoints.isEmpty())
        session.previewStroke(drag.drawingPoints)
        assertFalse(session.hasChanges)
        assertFalse(session.limitReached)
    }

    @Test
    fun compassCapacityRejectsTheWholeArcAndStillAllowsNavigation() {
        val initial = WhiteboardInstrumentState(radius = 100f)
        val drag = compassDrag(initial)
        drag.update(initial.pointAt(90f))
        val existing = WhiteboardStroke(points = List(Whiteboard.MAX_POINTS - drag.drawingPoints.size) { WhiteboardPoint(0f, 0f) })
        val before = Whiteboard(listOf(existing))
        val session = WhiteboardGestureSession(before, false, 0xFF202124L, 3f)
        session.previewStroke(drag.drawingPoints)
        assertEquals(Whiteboard.MAX_POINTS, session.working.strokes.sumOf { it.points.size })
        assertTrue(session.hasChanges)
        drag.update(initial.pointAt(180f))
        assertSame(before, session.previewStroke(drag.drawingPoints))
        assertTrue(session.limitReached)
        assertFalse(session.hasChanges)
        session.startNavigation()
        assertFalse(session.limitReached)

        val full = Whiteboard(List(Whiteboard.MAX_STROKES) { existing.copy(points = listOf(WhiteboardPoint(0f, 0f))) })
        val blocked = WhiteboardGestureSession(full, false, 0xFF202124L, 3f)
        assertSame(full, blocked.previewStroke(drag.drawingPoints))
        assertTrue(blocked.limitReached)
    }

    @Test
    fun movingAndResizingKeepTheCompleteGuideInsideTheBoard() {
        WhiteboardInstrument.entries.forEach { instrument ->
            val initial = WhiteboardInstrumentState(radius = 200f)
            val drag = WhiteboardInstrumentDrag(initial, instrument, initial.center, 20f)
            val moved = drag.update(WhiteboardPoint(-10f, 3000f))
            assertEquals(200f, moved.centerX)
            assertEquals(1800f, moved.centerY)
            val resized = moved.resized(900f)
            assertEquals(200f, resized.radius)
            resized.drawingPoints(instrument).forEach {
                assertTrue(it.x in 0f..Whiteboard.CANVAS_WIDTH && it.y in 0f..Whiteboard.CANVAS_HEIGHT)
            }
        }
    }

    @Test
    fun protractorReadsTheAngleRelativeToItsRotatedBaseline() {
        val initial = WhiteboardInstrumentState(baselineDegrees = 330f, angleDegrees = 60f)
        listOf(0f, 30f, 90f, 179f, 180f).forEach { angle ->
            val drag = WhiteboardInstrumentDrag(initial, WhiteboardInstrument.PROTRACTOR,
                initial.handlePoint(InstrumentHandle.MEASUREMENT), 20f)
            val measured = drag.update(initial.pointAt(initial.baselineDegrees + angle))
            assertEquals(angle, measured.angleDegrees, 0.01f)
            assertEquals(initial.center, measured.center)
            assertEquals(initial.radius, measured.radius)
            assertEquals(initial.baselineDegrees, measured.baselineDegrees)
        }
        val rotation = WhiteboardInstrumentDrag(initial, WhiteboardInstrument.PROTRACTOR,
            initial.handlePoint(InstrumentHandle.BASELINE), 20f)
        val rotated = rotation.update(initial.pointAt(20f, initial.radius * 0.4f))
        assertEquals(110f, rotated.baselineDegrees, 0.01f)
        assertEquals(initial.angleDegrees, rotated.angleDegrees)
    }

    @Test
    fun secondFingerRestoresTheInstrumentBeforeNavigation() {
        WhiteboardInstrument.entries.forEach { instrument ->
            val initial = WhiteboardInstrumentState()
            val drag = WhiteboardInstrumentDrag(initial, instrument, initial.center, 20f)
            assertNotEquals(initial, drag.update(WhiteboardPoint(700f, 900f)))
            assertEquals(initial, drag.startNavigation())
            assertEquals(initial, drag.update(WhiteboardPoint(200f, 1000f)))
        }
    }

    @Test
    fun stampedDrawingsUseOrdinaryInkAndRejectPartialShapesAtCapacity() {
        WhiteboardInstrument.entries.forEach { instrument ->
            val state = WhiteboardInstrumentState()
            val drawing = Whiteboard().withInstrumentDrawing(instrument, state, 0xFF1967D2L, 16f, WhiteboardBrush.WATERCOLOR)!!
            val stroke = drawing.strokes.single()
            assertEquals(WhiteboardBrush.WATERCOLOR, stroke.brush)
            assertEquals(0xFF1967D2L, stroke.color)
            assertEquals(16f, stroke.width)
            assertEquals(drawing, SyncJson.json.decodeFromString<Whiteboard>(SyncJson.json.encodeToString(drawing)))
            val svg = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(whiteboardSvg(drawing).byteInputStream())
            assertTrue(svg.getElementsByTagName("path").length > 0)
            val existing = WhiteboardStroke(points = List(Whiteboard.MAX_POINTS - stroke.points.size) { WhiteboardPoint(0f, 0f) })
            val before = Whiteboard(listOf(existing), images = listOf(WhiteboardImage("image", "file", 0f, 0f, 100f, 100f)))
            val exact = before.withInstrumentDrawing(instrument, state, 0xFF202124L, 3f, WhiteboardBrush.FINELINER)!!
            assertEquals(Whiteboard.MAX_POINTS, exact.strokes.sumOf { it.points.size })
            assertEquals(before.images, exact.images)
            assertNull(exact.withInstrumentDrawing(instrument, state, 0xFF202124L, 3f, WhiteboardBrush.FINELINER))
            assertNull(Whiteboard(List(Whiteboard.MAX_STROKES) { existing.copy(points = listOf(WhiteboardPoint(0f, 0f))) })
                .withInstrumentDrawing(instrument, state, 0xFF202124L, 3f, WhiteboardBrush.FINELINER))
        }
    }

    @Test
    fun stampedAngleHasTheSameOpeningAsTheProtractor() {
        val state = WhiteboardInstrumentState(baselineDegrees = 25f, angleDegrees = 75f)
        val points = state.drawingPoints(WhiteboardInstrument.PROTRACTOR)
        val a = points[0]
        val vertex = points[1]
        val b = points[2]
        val dot = (a.x - vertex.x) * (b.x - vertex.x) + (a.y - vertex.y) * (b.y - vertex.y)
        val denominator = sqrt((a.x - vertex.x) * (a.x - vertex.x) + (a.y - vertex.y) * (a.y - vertex.y)) *
            sqrt((b.x - vertex.x) * (b.x - vertex.x) + (b.y - vertex.y) * (b.y - vertex.y))
        val angle = Math.toDegrees(kotlin.math.acos((dot / denominator).toDouble()))
        assertTrue(abs(angle - 75.0) < 0.2)
    }

    private fun compassDrag(state: WhiteboardInstrumentState) = WhiteboardInstrumentDrag(
        state, WhiteboardInstrument.COMPASS, state.handlePoint(InstrumentHandle.PEN), 20f,
    )

    private fun pathLength(points: List<WhiteboardPoint>): Double = points.zipWithNext().sumOf { (a, b) ->
        sqrt(((a.x - b.x) * (a.x - b.x) + (a.y - b.y) * (a.y - b.y)).toDouble())
    }
}
