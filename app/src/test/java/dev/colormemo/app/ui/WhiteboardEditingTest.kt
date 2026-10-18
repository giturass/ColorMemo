package dev.colormemo.app.ui

import dev.colormemo.core.model.Whiteboard
import dev.colormemo.core.model.WhiteboardPoint
import dev.colormemo.core.model.WhiteboardStroke
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class WhiteboardEditingTest {
    @Test
    fun fastEraserSwipeHitsCrossedStrokeEvenWhenBothEndsAreFarAway() {
        val board = board(stroke(100f, 500f, 900f, 500f))

        assertTrue(board.eraseBetween(point(500f, 100f), point(500f, 900f), 10f).strokes.isEmpty())
    }

    @Test
    fun eraserAccountsForStrokeWidthAndLeavesUnrelatedStrokes() {
        val thick = stroke(100f, 100f, 900f, 100f).copy(width = 40f)
        val unrelated = stroke(100f, 500f, 900f, 500f)
        val board = board(thick, unrelated)

        val erased = board.eraseBetween(point(300f, 130f), point(600f, 130f), 10f)

        assertEquals(listOf(unrelated), erased.strokes)
    }

    @Test
    fun eraserHitsDotsAndRepeatedPoints() {
        val dot = WhiteboardStroke(points = listOf(point(500f, 500f)))
        val repeated = WhiteboardStroke(points = listOf(point(500f, 500f), point(500f, 500f)))

        assertTrue(board(dot, repeated).eraseBetween(point(100f, 500f), point(900f, 500f), 10f).strokes.isEmpty())
    }

    @Test
    fun eraserTapHitsLineInterior() {
        val board = board(stroke(100f, 500f, 900f, 500f))

        assertTrue(board.eraseBetween(point(500f, 501f), point(500f, 501f), 10f).strokes.isEmpty())
    }

    @Test
    fun collinearOverlappingSwipeErasesStroke() {
        val board = board(stroke(100f, 500f, 900f, 500f))

        assertTrue(board.eraseBetween(point(400f, 500f), point(600f, 500f), 0f).strokes.isEmpty())
    }

    @Test
    fun distantAndDisjointParallelSwipesDoNotErase() {
        val board = board(stroke(100f, 500f, 400f, 500f))

        assertSame(board, board.eraseBetween(point(100f, 530f), point(400f, 530f), 10f))
        assertSame(board, board.eraseBetween(point(600f, 500f), point(900f, 500f), 10f))
    }

    @Test
    fun recordedPointsClampAndRoundToHalfUnits() {
        assertEquals(point(0f, 1000f), whiteboardPoint(-10f, 1050f))
        assertEquals(point(101f, 200.5f), whiteboardPoint(100.9f, 200.6f))
    }

    @Test
    fun pointSamplingUsesEuclideanDistance() {
        assertFalse(point(1f, 1f).isFarEnoughFrom(point(0f, 0f), 2.5f))
        assertTrue(point(1.5f, 2f).isFarEnoughFrom(point(0f, 0f), 2.5f))
    }

    private fun point(x: Float, y: Float) = WhiteboardPoint(x, y)
    private fun stroke(x1: Float, y1: Float, x2: Float, y2: Float) =
        WhiteboardStroke(points = listOf(point(x1, y1), point(x2, y2)))
    private fun board(vararg strokes: WhiteboardStroke) = Whiteboard(strokes.toList())
}
