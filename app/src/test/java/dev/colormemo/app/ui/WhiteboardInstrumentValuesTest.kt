package dev.colormemo.app.ui

import kotlin.math.PI
import kotlin.math.sqrt
import dev.colormemo.core.model.Whiteboard
import dev.colormemo.core.model.WhiteboardBrush
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class WhiteboardInstrumentValuesTest {
    @Test
    fun manualValuesAcceptDecimalsAndInclusiveLimits() {
        val range = 24f..500f
        assertEquals(125.5f, parseWhiteboardInstrumentValue(" 125.5 ", range)!!, 0f)
        assertEquals(125.5f, parseWhiteboardInstrumentValue("125,5", range)!!, 0f)
        assertEquals(24f, parseWhiteboardInstrumentValue("24", range)!!, 0f)
        assertEquals(500f, parseWhiteboardInstrumentValue("500", range)!!, 0f)
    }

    @Test
    fun manualValuesRejectInvalidAndOutOfRangeInputWithoutClamping() {
        listOf("", " ", ".", "1.2.3", "abc", "NaN", "Infinity", "-Infinity", "1e100", "-1", "23.99", "500.01")
            .forEach { assertNull(it, parseWhiteboardInstrumentValue(it, 24f..500f)) }
        val moved = WhiteboardInstrumentState(centerX = 80f, radius = 50f)
        assertNull(parseWhiteboardInstrumentValue("100", MIN_INSTRUMENT_RADIUS..moved.maximumRadius))
    }

    @Test
    fun enteredRadiansDrawTheExpectedArcAndLength() {
        val state = WhiteboardInstrumentState(radius = 100f)
        listOf((PI / 2).toFloat() to (500f to 400f),
            PI.toFloat() to (400f to 500f), (3 * PI / 2).toFloat() to (500f to 600f),
            FULL_TURN_RADIANS to (600f to 500f)).forEach { (radians, expected) ->
            val parsed = parseWhiteboardInstrumentValue(radians.toString(), 0f..FULL_TURN_RADIANS)!!
            val changed = state.copy(arcRadians = parsed)
            val drawing = Whiteboard().withInstrumentDrawing(WhiteboardInstrument.COMPASS, changed,
                0xFF1967D2L, 3f, WhiteboardBrush.FINELINER)!!
            val points = drawing.strokes.single().points
            assertEquals(state.handlePoint(InstrumentHandle.PEN), points.first())
            assertEquals(expected.first, points.last().x, 0.01f)
            assertEquals(expected.second, points.last().y, 0.01f)
            val length = points.zipWithNext().sumOf { (a, b) ->
                sqrt(((a.x - b.x) * (a.x - b.x) + (a.y - b.y) * (a.y - b.y)).toDouble())
            }
            assertEquals((state.radius * radians).toDouble(), length, 2.0)
            if (radians <= PI.toFloat()) assertTrue(points.all { it.y <= state.centerY })
            assertEquals(state.center, changed.center)
            assertEquals(state.radius, changed.radius, 0f)
        }
    }

    @Test
    fun stampingStartsAtThePenAndAdvancesItToContinueTheArcAcrossZero() {
        val state = WhiteboardInstrumentState(baselineDegrees = 330f, arcRadians = (PI / 2).toFloat())
        val points = state.drawingPoints(WhiteboardInstrument.COMPASS)
        val continued = state.afterCompassDrawing()
        assertEquals(60f, continued.baselineDegrees, 0.001f)
        assertEquals(continued.drawingPoints(WhiteboardInstrument.COMPASS).first(), points.last())
        assertEquals(state.arcRadians, continued.arcRadians, 0f)
    }

    @Test
    fun zeroSweepCreatesNoStrokeAndOversizedSweepsAreRejected() {
        val board = Whiteboard()
        assertSame(board, board.withInstrumentDrawing(WhiteboardInstrument.COMPASS,
            WhiteboardInstrumentState(arcRadians = 0f), 0xFF1967D2L, 3f, WhiteboardBrush.FINELINER))
        assertNull(parseWhiteboardInstrumentValue("6.29", 0f..FULL_TURN_RADIANS))
        assertNull(parseWhiteboardInstrumentValue("-0.01", 0f..FULL_TURN_RADIANS))
    }
}
