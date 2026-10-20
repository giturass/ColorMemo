package dev.colormemo.app.ui

import dev.colormemo.core.model.Whiteboard
import dev.colormemo.core.model.WhiteboardBrush
import dev.colormemo.core.model.WhiteboardImage
import dev.colormemo.core.model.WhiteboardPoint
import dev.colormemo.core.model.WhiteboardStroke
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class WhiteboardEditingTest {
    @Test
    fun fastEraserSwipeCutsOnlyTheCrossingEvenWhenBothSamplesAreFarAway() {
        val board = board(stroke(100f, 500f, 900f, 500f))

        val erased = board.eraseBetween(point(500f, 100f), point(500f, 900f), 10f)

        assertEquals(listOf(stroke(100f, 500f, 487f, 500f), stroke(513f, 500f, 900f, 500f)), erased.strokes)
        assertEquals(erased, board.eraseBetween(point(500f, 900f), point(500f, 100f), 10f))
    }

    @Test
    fun eraserAccountsForStrokeWidthAndLeavesUnrelatedStrokes() {
        val thick = stroke(100f, 100f, 900f, 100f).copy(width = 40f)
        val unrelated = stroke(100f, 500f, 900f, 500f)
        val board = board(thick, unrelated)

        val erased = board.eraseBetween(point(300f, 130f), point(600f, 130f), 10f)

        assertEquals(listOf(
            thick.copy(points = listOf(point(100f, 100f), point(300f, 100f))),
            thick.copy(points = listOf(point(600f, 100f), point(900f, 100f))),
            unrelated,
        ), erased.strokes)
    }

    @Test
    fun eraserHitsDotsAndRepeatedPoints() {
        val dot = WhiteboardStroke(points = listOf(point(500f, 500f)))
        val repeated = WhiteboardStroke(points = listOf(point(500f, 500f), point(500f, 500f)))

        assertTrue(board(dot, repeated).eraseBetween(point(100f, 500f), point(900f, 500f), 10f).strokes.isEmpty())
    }

    @Test
    fun eraserTapCutsASparseLineAndPreservesItsDistantEnds() {
        val board = board(stroke(100f, 500f, 900f, 500f))

        val erased = board.eraseBetween(point(500f, 501f), point(500f, 501f), 10f)

        assertEquals(2, erased.strokes.size)
        assertEquals(point(100f, 500f), erased.strokes.first().points.first())
        assertEquals(point(900f, 500f), erased.strokes.last().points.last())
        assertEquals(487.039f, erased.strokes.first().points.last().x, 0.001f)
        assertEquals(512.961f, erased.strokes.last().points.first().x, 0.001f)
        assertSame(erased, erased.eraseBetween(point(500f, 501f), point(500f, 501f), 10f))
    }

    @Test
    fun collinearOverlappingSwipeLeavesBothOutsideSections() {
        val board = board(stroke(100f, 500f, 900f, 500f))

        val erased = board.eraseBetween(point(400f, 500f), point(600f, 500f), 0f)

        assertEquals(listOf(stroke(100f, 500f, 397f, 500f), stroke(603f, 500f, 900f, 500f)), erased.strokes)
    }

    @Test
    fun diagonalSweepUsesTheSameLocalRadius() {
        val board = board(stroke(100f, 100f, 900f, 900f))

        val erased = board.eraseBetween(point(100f, 900f), point(900f, 100f), 10f)

        assertEquals(2, erased.strokes.size)
        assertEquals(point(100f, 100f), erased.strokes.first().points.first())
        assertEquals(point(900f, 900f), erased.strokes.last().points.last())
        assertEquals(490.8076f, erased.strokes.first().points.last().x, 0.001f)
        assertEquals(509.1924f, erased.strokes.last().points.first().x, 0.001f)
    }

    @Test
    fun swipeEndUsesACircularBoundaryInsteadOfAnInfiniteStrip() {
        val board = board(stroke(510f, 100f, 510f, 900f))

        val erased = board.eraseBetween(point(400f, 500f), point(500f, 500f), 10f)

        assertEquals(2, erased.strokes.size)
        assertEquals(491.6934f, erased.strokes.first().points.last().y, 0.001f)
        assertEquals(508.3066f, erased.strokes.last().points.first().y, 0.001f)
        assertSame(board, board.eraseBetween(point(400f, 500f), point(496f, 500f), 10f))
    }

    @Test
    fun multipleCrossingsInOneStrokeKeepEveryOutsideFragmentAndItsStyle() {
        WhiteboardBrush.entries.forEach { brush ->
            val original = WhiteboardStroke(color = 0xFF1967D2L, points = listOf(
                point(100f, 100f), point(900f, 100f), point(900f, 500f),
                point(100f, 500f), point(100f, 900f), point(900f, 900f),
            ), brush = brush)
            val image = WhiteboardImage("image", "attachment", 0f, 0f, 100f, 100f)
            val board = Whiteboard(listOf(original), listOf(image))

            val erased = board.eraseBetween(point(500f, 0f), point(500f, 1000f), 10f)

            assertEquals(4, erased.strokes.size)
            assertEquals(listOf(image), erased.images)
            erased.strokes.forEach { fragment ->
                assertEquals(original.color, fragment.color)
                assertEquals(original.width, fragment.width)
                assertEquals(brush, fragment.brush)
                assertTrue(fragment.points.all { it.x < 490f } || fragment.points.all { it.x > 510f })
            }
            original.points.forEach { p -> assertTrue(erased.strokes.any { p in it.points }) }
        }
    }

    @Test
    fun cuttingOneEdgeOfAClosedShapeKeepsTheOtherEdgesConnected() {
        val rectangle = WhiteboardStroke(points = listOf(
            point(100f, 100f), point(900f, 100f), point(900f, 900f), point(100f, 900f), point(100f, 100f),
        ))

        val erased = board(rectangle).eraseBetween(point(500f, 100f), point(500f, 100f), 10f)

        assertEquals(listOf(rectangle.copy(points = listOf(
            point(513f, 100f), point(900f, 100f), point(900f, 900f),
            point(100f, 900f), point(100f, 100f), point(487f, 100f),
        ))), erased.strokes)
    }

    @Test
    fun fragmentLimitRejectsTheCutWithoutDeletingAnyOutsideInk() {
        val unrelated = WhiteboardStroke(points = listOf(point(100f, 100f)))
        val target = stroke(100f, 500f, 900f, 500f)
        val full = Whiteboard(List(Whiteboard.MAX_STROKES - 1) { unrelated } + target)
        var limitReached = false

        assertSame(full, full.eraseBetween(point(500f, 500f), point(500f, 500f), 10f) { limitReached = true })
        assertTrue(limitReached)
        val withRoom = full.copy(strokes = full.strokes.drop(1))
        val erased = withRoom.eraseBetween(point(500f, 500f), point(500f, 500f), 10f)
        assertEquals(Whiteboard.MAX_STROKES, erased.strokes.size)
        assertEquals(withRoom.strokes.dropLast(1), erased.strokes.dropLast(2))
        val fullyErased = full.eraseBetween(point(100f, 500f), point(900f, 500f), 10f)
        assertEquals(full.strokes.dropLast(1), fullyErased.strokes)
    }

    @Test
    fun pointLimitRejectsFragmentationButStillAllowsAnEraseThatFreesSpace() {
        val manyPoints = WhiteboardStroke(points = List(Whiteboard.MAX_POINTS - 2) { point(500f, 100f) })
        val full = board(manyPoints, stroke(100f, 500f, 900f, 500f))
        var limitReached = false

        assertSame(full, full.eraseBetween(point(500f, 500f), point(500f, 500f), 10f) { limitReached = true })
        assertTrue(limitReached)
        val erased = full.eraseBetween(point(500f, 0f), point(500f, 1000f), 10f)
        assertEquals(listOf(stroke(100f, 500f, 487f, 500f), stroke(513f, 500f, 900f, 500f)), erased.strokes)
    }

    @Test
    fun distantAndDisjointParallelSwipesDoNotErase() {
        val board = board(stroke(100f, 500f, 400f, 500f))

        assertSame(board, board.eraseBetween(point(100f, 530f), point(400f, 530f), 10f))
        assertSame(board, board.eraseBetween(point(600f, 500f), point(900f, 500f), 10f))
    }

    @Test
    fun recordedPointsClampAndRoundToHalfUnits() {
        assertEquals(point(0f, 2000f), whiteboardPoint(-10f, 2050f))
        assertEquals(point(1000f, 1750f), whiteboardPoint(1050f, 1750f))
        assertEquals(point(101f, 200.5f), whiteboardPoint(100.9f, 200.6f))
    }

    @Test
    fun pointSamplingUsesEuclideanDistance() {
        assertFalse(point(1f, 1f).isFarEnoughFrom(point(0f, 0f), 2.5f))
        assertTrue(point(1.5f, 2f).isFarEnoughFrom(point(0f, 0f), 2.5f))
    }

    @Test
    fun eraserCanReachTheVisibleCornerOfASquareMarkerDot() {
        val dot = WhiteboardStroke(width = 40f, points = listOf(point(500f, 500f)), brush = WhiteboardBrush.MARKER)
        val board = Whiteboard(listOf(dot))
        assertTrue(board.eraseBetween(point(519f, 519f), point(519f, 519f), 0.5f).strokes.isEmpty())
    }

    private fun point(x: Float, y: Float) = WhiteboardPoint(x, y)
    private fun stroke(x1: Float, y1: Float, x2: Float, y2: Float) =
        WhiteboardStroke(points = listOf(point(x1, y1), point(x2, y2)))
    private fun board(vararg strokes: WhiteboardStroke) = Whiteboard(strokes.toList())
}
