package dev.colormemo.app.ui

import dev.colormemo.core.model.Whiteboard
import dev.colormemo.core.model.WhiteboardBrush
import dev.colormemo.core.model.WhiteboardPoint
import dev.colormemo.core.model.WhiteboardStroke
import org.junit.Assert.*
import org.junit.Test

class WhiteboardRulerTest {
    @Test
    fun rulerDrawsItsMarkedEdgeAtTheDisplayedLengthAndDirection() {
        val horizontal = WhiteboardInstrumentState(radius = 100f)
        assertEquals(180f, horizontal.rulerLength, 0.001f)
        assertEquals(listOf(WhiteboardPoint(410f, 470f), WhiteboardPoint(590f, 470f)),
            horizontal.drawingPoints(WhiteboardInstrument.RULER))
        val vertical = horizontal.copy(baselineDegrees = 90f)
        assertEquals(listOf(WhiteboardPoint(470f, 590f), WhiteboardPoint(470f, 410f)),
            vertical.drawingPoints(WhiteboardInstrument.RULER))
        val longer = horizontal.resized(200f)
        assertEquals(360f, longer.rulerLength, 0.001f)
        assertEquals(horizontal.center, longer.center)
    }

    @Test
    fun edgeDrawingProjectsOntoTheRulerAndPreservesInkWhenReversing() {
        val initial = WhiteboardInstrumentState(radius = 100f)
        val drag = rulerDrag(initial, WhiteboardPoint(500f, 470f))
        assertEquals(initial, drag.update(WhiteboardPoint(570f, 420f)))
        assertEquals(listOf(WhiteboardPoint(500f, 470f), WhiteboardPoint(570f, 470f)), drag.drawingPoints)
        drag.update(WhiteboardPoint(450f, 580f))
        assertEquals(listOf(WhiteboardPoint(450f, 470f), WhiteboardPoint(570f, 470f)), drag.drawingPoints)
        drag.update(WhiteboardPoint(800f, 450f))
        drag.update(WhiteboardPoint(300f, 520f))
        assertEquals(initial.drawingPoints(WhiteboardInstrument.RULER), drag.drawingPoints)
    }

    @Test
    fun rotatedRulerProjectsAndClampsInItsOwnCoordinates() {
        val initial = WhiteboardInstrumentState(radius = 100f, baselineDegrees = 90f)
        val drag = rulerDrag(initial, WhiteboardPoint(470f, 550f))
        assertEquals(initial, drag.update(WhiteboardPoint(700f, 450f)))
        assertEquals(listOf(WhiteboardPoint(470f, 550f), WhiteboardPoint(470f, 450f)), drag.drawingPoints)
        drag.update(WhiteboardPoint(450f, 300f))
        assertEquals(WhiteboardPoint(470f, 410f), drag.drawingPoints.last())
    }

    @Test
    fun tappingTheEdgeOrMovingPerpendicularlyDoesNotDraw() {
        val initial = WhiteboardInstrumentState(radius = 100f)
        val start = WhiteboardPoint(500f, 476f)
        val drag = rulerDrag(initial, start)
        assertEquals(initial, drag.update(start))
        assertEquals(initial, drag.update(WhiteboardPoint(500f, 400f)))
        assertTrue(drag.drawingPoints.isEmpty())
    }

    @Test
    fun movingTheBodyPreservesTheGrabOffsetAndNeverDraws() {
        val initial = WhiteboardInstrumentState(radius = 100f)
        val start = WhiteboardPoint(430f, 518f)
        val drag = rulerDrag(initial, start)
        assertEquals(initial, drag.update(start))
        val moved = drag.update(WhiteboardPoint(530f, 718f))
        assertEquals(WhiteboardPoint(600f, 700f), moved.center)
        assertEquals(initial.radius, moved.radius)
        assertEquals(initial.baselineDegrees, moved.baselineDegrees)
        assertTrue(drag.drawingPoints.isEmpty())
    }

    @Test
    fun rotationPreservesTheInitialGrabAngleAcrossZeroAndIgnoresTheCenter() {
        val initial = WhiteboardInstrumentState(radius = 100f, baselineDegrees = 350f)
        val start = initial.pointAt(355f, 63f)
        val drag = rulerDrag(initial, start)
        assertEquals(350f, drag.update(start).baselineDegrees, 0.001f)
        val rotated = drag.update(initial.pointAt(15f, 160f))
        assertEquals(10f, rotated.baselineDegrees, 0.001f)
        assertEquals(initial.center, rotated.center)
        assertEquals(initial.radius, rotated.radius)
        assertEquals(rotated, drag.update(initial.center))
        assertTrue(drag.drawingPoints.isEmpty())
    }

    @Test
    fun smallRulerStillSeparatesTheDrawingEdgeFromBothHandles() {
        val initial = WhiteboardInstrumentState(radius = MIN_INSTRUMENT_RADIUS)
        listOf(InstrumentHandle.CENTER, InstrumentHandle.RULER_ROTATION).forEach { handle ->
            val drag = rulerDrag(initial, initial.handlePoint(handle), touchRadius = 100f)
            drag.update(WhiteboardPoint(520f, 520f))
            assertTrue(drag.drawingPoints.isEmpty())
        }
        val edge = rulerDrag(initial, initial.rulerPoint(0f), touchRadius = 100f)
        edge.update(initial.rulerPoint(initial.rulerLength))
        assertEquals(initial.drawingPoints(WhiteboardInstrument.RULER), edge.drawingPoints)
    }

    @Test
    fun navigationRollsBackTheWholeStyledLineAndKeepsTheRulerFixed() {
        val initial = WhiteboardInstrumentState(radius = 100f)
        val before = Whiteboard(strokes = listOf(WhiteboardStroke(points = listOf(WhiteboardPoint(0f, 0f)))))
        val session = WhiteboardGestureSession(before, false, 0xFF1967D2L, 16f, WhiteboardBrush.MARKER)
        val drag = rulerDrag(initial, initial.rulerPoint(0f))
        listOf(30f, 90f, 180f).forEach { distance ->
            drag.update(initial.rulerPoint(distance, 10f))
            session.previewStroke(drag.drawingPoints)
            assertEquals(2, session.working.strokes.size)
        }
        assertEquals(WhiteboardStroke(0xFF1967D2L, 16f, initial.drawingPoints(WhiteboardInstrument.RULER), WhiteboardBrush.MARKER),
            session.working.strokes.last())
        assertTrue(session.hasChanges)
        assertEquals(initial, drag.startNavigation())
        assertSame(before, session.startNavigation())
        assertEquals(initial, drag.update(WhiteboardPoint(700f, 700f)))
        assertTrue(drag.drawingPoints.isEmpty())
        assertSame(before, session.previewStroke(drag.drawingPoints))
        assertFalse(session.hasChanges)
    }

    @Test
    fun insufficientCapacityRejectsTheEntireTracedLine() {
        val initial = WhiteboardInstrumentState(radius = 100f)
        val before = Whiteboard(listOf(WhiteboardStroke(points = List(Whiteboard.MAX_POINTS - 1) { WhiteboardPoint(0f, 0f) })))
        val session = WhiteboardGestureSession(before, false, 0xFF202124L, 3f)
        val drag = rulerDrag(initial, initial.rulerPoint(0f))
        drag.update(initial.rulerPoint(initial.rulerLength))
        assertSame(before, session.previewStroke(drag.drawingPoints))
        assertTrue(session.limitReached)
        assertFalse(session.hasChanges)
        session.startNavigation()
        assertFalse(session.limitReached)
    }

    @Test
    fun rotatingAndResizingNearBoardBoundariesKeepsAllFourCornersInside() {
        listOf(0f, 45f, 90f, 137f, 270f, 359f).forEach { angle ->
            listOf(WhiteboardPoint(-10f, -10f), WhiteboardPoint(1100f, 5000f)).forEach { center ->
                val state = WhiteboardInstrumentState(center.x, center.y, 900f, angle).constrained().resized(900f)
                listOf(0f, state.rulerLength).forEach { distance ->
                    listOf(0f, state.rulerHalfWidth * 2f).forEach { depth ->
                        val point = state.rulerPoint(distance, depth)
                        assertTrue(point.x in 0f..Whiteboard.CANVAS_WIDTH)
                        assertTrue(point.y in 0f..Whiteboard.CANVAS_HEIGHT)
                    }
                }
            }
        }
    }

    private fun rulerDrag(state: WhiteboardInstrumentState, start: WhiteboardPoint, touchRadius: Float = 20f) =
        WhiteboardInstrumentDrag(state, WhiteboardInstrument.RULER, start, touchRadius)
}
