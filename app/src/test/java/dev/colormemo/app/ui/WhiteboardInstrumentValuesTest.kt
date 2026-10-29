package dev.colormemo.app.ui

import kotlin.math.PI
import kotlin.math.sqrt
import dev.colormemo.core.model.Whiteboard
import dev.colormemo.core.model.WhiteboardBrush
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
    fun enteredRadiusDrawsACompleteCircleAndKeepsTheNeedleAndStartingDirection() {
        val state = WhiteboardInstrumentState(radius = 100f, baselineDegrees = 330f)
        listOf("24", "125.5", "500").forEach { input ->
            val radius = parseWhiteboardInstrumentValue(input, MIN_INSTRUMENT_RADIUS..state.maximumRadius)!!
            val changed = state.resized(radius)
            val drawing = Whiteboard().withInstrumentDrawing(WhiteboardInstrument.COMPASS, changed,
                0xFF1967D2L, 3f, WhiteboardBrush.FINELINER)!!
            val points = drawing.strokes.single().points
            assertEquals(points.first().x, points.last().x, 0.01f)
            assertEquals(points.first().y, points.last().y, 0.01f)
            assertEquals(state.centerX - radius, points.minOf { it.x }, 0.01f)
            assertEquals(state.centerX + radius, points.maxOf { it.x }, 0.01f)
            assertEquals(state.centerY - radius, points.minOf { it.y }, 0.01f)
            assertEquals(state.centerY + radius, points.maxOf { it.y }, 0.01f)
            // Saved stroke coordinates are rounded to half a board unit.
            points.forEach { point ->
                assertEquals(radius, sqrt((point.x - state.centerX) * (point.x - state.centerX) +
                    (point.y - state.centerY) * (point.y - state.centerY)), 0.4f)
            }
            val length = points.zipWithNext().sumOf { (a, b) ->
                sqrt(((a.x - b.x) * (a.x - b.x) + (a.y - b.y) * (a.y - b.y)).toDouble())
            }
            assertEquals(2 * PI * radius, length, 2.0)
            assertEquals(state.center, changed.center)
            assertEquals(radius, changed.radius, 0f)
            assertEquals(state.baselineDegrees, changed.baselineDegrees, 0f)
        }
    }
}
