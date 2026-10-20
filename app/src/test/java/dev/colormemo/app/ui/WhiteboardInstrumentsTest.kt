package dev.colormemo.app.ui

import dev.colormemo.app.data.whiteboardSvg
import dev.colormemo.core.model.Whiteboard
import dev.colormemo.core.model.WhiteboardBrush
import dev.colormemo.core.model.WhiteboardImage
import dev.colormemo.core.model.WhiteboardPoint
import dev.colormemo.core.model.WhiteboardStroke
import dev.colormemo.core.sync.SyncJson
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.abs
import kotlin.math.sqrt
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

class WhiteboardInstrumentsTest {
    @Test
    fun compassDragKeepsItsNeedleFixedAndCreatesACircleWithTheChosenRadius() {
        val initial = WhiteboardInstrumentState(radius = 100f)
        val drag = WhiteboardInstrumentDrag(initial, WhiteboardInstrument.COMPASS, initial.handlePoint(InstrumentHandle.RADIUS), 20f)
        val adjusted = drag.update(WhiteboardPoint(700f, 650f))
        assertEquals(initial.center, adjusted.center)
        assertEquals(250f, adjusted.radius, 0.01f)
        val points = adjusted.drawingPoints(WhiteboardInstrument.COMPASS)
        assertEquals(points.first(), points.last())
        points.forEach { p ->
            assertEquals(250f, sqrt((p.x - adjusted.centerX) * (p.x - adjusted.centerX) +
                (p.y - adjusted.centerY) * (p.y - adjusted.centerY)), 0.4f)
        }
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
}
