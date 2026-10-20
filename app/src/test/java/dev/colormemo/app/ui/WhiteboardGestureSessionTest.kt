package dev.colormemo.app.ui

import dev.colormemo.core.model.Whiteboard
import dev.colormemo.core.model.WhiteboardBrush
import dev.colormemo.core.model.WhiteboardPoint
import dev.colormemo.core.model.WhiteboardStroke
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class WhiteboardGestureSessionTest {
    @Test
    fun tapImmediatelyPublishesOneDotWithSelectedInk() {
        val before = Whiteboard()
        val session = session(before, color = 0xFF1967D2L, width = 14f)
        val down = point(100f, 200f)

        val working = session.begin(down)
        session.update(down, finalPoint = true)

        assertEquals(listOf(WhiteboardStroke(0xFF1967D2L, 14f, listOf(down))), working.strokes)
        assertEquals(working, session.working)
        assertTrue(session.hasChanges)
        assertTrue(before.strokes.isEmpty())
    }

    @Test
    fun samplingMeasuresFromLastRecordedPointAndAlwaysKeepsShortFinalMovement() {
        val session = session()
        session.begin(point(0f, 0f))

        session.update(point(1f, 1f))
        assertEquals(listOf(point(0f, 0f)), session.working.strokes.single().points)
        session.update(point(1.5f, 2f))
        session.update(point(2f, 2.5f), finalPoint = true)

        assertEquals(
            listOf(point(0f, 0f), point(1.5f, 2f), point(2f, 2.5f)),
            session.working.strokes.single().points,
        )
    }

    @Test
    fun secondFingerRollsBackVisibleStrokeAndDoesNotCreateAnUndoEntry() {
        val before = Whiteboard(listOf(stroke(100f, 500f, 900f, 500f)))
        val session = session(before)
        session.begin(point(100f, 100f))
        session.update(point(300f, 100f))
        assertTrue(session.hasChanges)

        assertSame(before, session.startNavigation())

        assertFalse(session.hasChanges)
        assertTrue(session.isNavigating)
        assertSame(before, session.working)
    }

    @Test
    fun navigationIgnoresRemainingFingerAndFinalUp() {
        val session = session()
        session.begin(point(100f, 100f))
        session.startNavigation()

        session.update(point(300f, 100f))
        session.update(point(301f, 100f), finalPoint = true)
        session.begin(point(500f, 500f))

        assertSame(session.before, session.working)
        assertFalse(session.hasChanges)
        assertTrue(session.isNavigating)
    }

    @Test
    fun eraserTapAndFastSwipeAreVisibleButNavigationRestoresOriginalStrokeOrder() {
        val first = stroke(100f, 100f, 900f, 100f)
        val second = stroke(100f, 500f, 900f, 500f)
        val third = stroke(100f, 900f, 900f, 900f)
        val before = Whiteboard(listOf(first, second, third))
        val session = session(before, erasing = true)

        val firstLeft = first.copy(points = listOf(point(100f, 100f), point(469f, 100f)))
        val firstRight = first.copy(points = listOf(point(531f, 100f), point(900f, 100f)))
        val secondLeft = second.copy(points = listOf(point(100f, 500f), point(469f, 500f)))
        val secondRight = second.copy(points = listOf(point(531f, 500f), point(900f, 500f)))
        assertEquals(listOf(firstLeft, firstRight, second, third), session.begin(point(500f, 100f)).strokes)
        assertEquals(listOf(firstLeft, firstRight, secondLeft, secondRight, third), session.update(point(500f, 700f)).strokes)
        assertTrue(session.hasChanges)
        session.startNavigation()
        session.update(point(500f, 900f), finalPoint = true)

        assertSame(before, session.working)
        assertEquals(listOf(first, second, third), session.working.strokes)
        assertFalse(session.hasChanges)
    }

    @Test
    fun erasingEmptyAreaDoesNotCreateAnUndoEntry() {
        val before = Whiteboard(listOf(stroke(100f, 500f, 900f, 500f)))
        val session = session(before, erasing = true)

        session.begin(point(100f, 100f))
        session.update(point(900f, 100f), finalPoint = true)

        assertSame(before, session.working)
        assertFalse(session.hasChanges)
    }

    @Test
    fun strokeLimitStillAllowsNavigation() {
        val dot = WhiteboardStroke(points = listOf(point(100f, 100f)))
        val before = Whiteboard(List(Whiteboard.MAX_STROKES) { dot })
        val session = session(before)

        assertSame(before, session.begin(point(500f, 500f)))
        assertTrue(session.limitReached)
        assertFalse(session.hasChanges)
        session.startNavigation()

        assertTrue(session.isNavigating)
        assertFalse(session.limitReached)
        assertSame(before, session.working)
    }

    @Test
    fun pointLimitStillAllowsNavigationWithoutModifyingExistingStroke() {
        val stroke = WhiteboardStroke(points = List(Whiteboard.MAX_POINTS) { point(100f, 100f) })
        val before = Whiteboard(listOf(stroke))
        val session = session(before)

        session.begin(point(500f, 500f))
        session.update(point(700f, 500f), finalPoint = true)
        assertTrue(session.limitReached)
        assertSame(before, session.working)
        session.startNavigation()

        assertTrue(session.isNavigating)
        assertFalse(session.hasChanges)
        assertSame(before, session.working)
    }

    @Test
    fun reachingPointLimitDuringStrokeCanStillRollbackForNavigation() {
        val stroke = WhiteboardStroke(points = List(Whiteboard.MAX_POINTS - 2) { point(100f, 100f) })
        val before = Whiteboard(listOf(stroke))
        val session = session(before)
        session.begin(point(500f, 500f))
        session.update(point(600f, 500f))
        session.update(point(700f, 500f))

        assertTrue(session.limitReached)
        assertEquals(Whiteboard.MAX_POINTS, session.working.strokes.sumOf { it.points.size })
        assertTrue(session.hasChanges)
        session.startNavigation()

        assertFalse(session.hasChanges)
        assertSame(before, session.working)
    }

    @Test
    fun eraserStillWorksAtCapacity() {
        val stroke = WhiteboardStroke(points = List(Whiteboard.MAX_POINTS) { point(100f, 100f) })
        val before = Whiteboard(listOf(stroke))
        val session = session(before, erasing = true)

        session.begin(point(100f, 100f))

        assertTrue(session.working.strokes.isEmpty())
        assertTrue(session.hasChanges)
        assertFalse(session.limitReached)
    }

    @Test
    fun everyBrushKeepsItsStyleAcrossSamplesAndNavigationRollsItBack() {
        WhiteboardBrush.entries.forEach { brush ->
            val session = WhiteboardGestureSession(Whiteboard(), false, 0xFF1967D2L, 40f, brush)
            session.begin(point(100f, 100f))
            session.update(point(500f, 200f), finalPoint = true)
            assertEquals(brush, session.working.strokes.single().brush)
            assertEquals(40f, session.working.strokes.single().width)
            assertEquals(2, session.working.strokes.single().points.size)
            session.startNavigation()
            assertTrue(session.working.strokes.isEmpty())
        }
    }

    @Test
    fun eraserRadiusControlsTapAndSwipeReach() {
        val before = Whiteboard(listOf(stroke(100f, 500f, 900f, 500f)))
        val small = WhiteboardGestureSession(before, true, 0xFF202124L, 3f, eraserRadius = 8f)
        small.begin(point(100f, 550f))
        small.update(point(900f, 550f), finalPoint = true)
        assertSame(before, small.working)

        val largeTap = WhiteboardGestureSession(before, true, 0xFF202124L, 3f, eraserRadius = 56f)
        largeTap.begin(point(500f, 550f))
        assertEquals(2, largeTap.working.strokes.size)
        assertEquals(point(100f, 500f), largeTap.working.strokes.first().points.first())
        assertEquals(point(900f, 500f), largeTap.working.strokes.last().points.last())

        val largeSwipe = WhiteboardGestureSession(before, true, 0xFF202124L, 3f, eraserRadius = 56f)
        largeSwipe.begin(point(0f, 550f))
        assertSame(before, largeSwipe.working)
        largeSwipe.update(point(1000f, 550f), finalPoint = true)
        assertTrue(largeSwipe.working.strokes.isEmpty())
        largeSwipe.startNavigation()
        assertSame(before, largeSwipe.working)
    }

    @Test
    fun fragmentLimitKeepsTheOriginalInkAndALaterEndCutCanStillSucceed() {
        val dot = WhiteboardStroke(points = listOf(point(100f, 100f)))
        val before = Whiteboard(List(Whiteboard.MAX_STROKES - 1) { dot } + stroke(100f, 500f, 900f, 500f))
        val session = session(before, erasing = true)

        assertSame(before, session.begin(point(500f, 500f)))
        assertTrue(session.limitReached)
        assertFalse(session.hasChanges)
        session.update(point(900f, 500f), finalPoint = true)
        assertFalse(session.limitReached)
        assertEquals(Whiteboard.MAX_STROKES, session.working.strokes.size)
        assertEquals(stroke(100f, 500f, 469f, 500f), session.working.strokes.last())
        assertTrue(session.hasChanges)
        session.startNavigation()
        assertSame(before, session.working)
        assertFalse(session.limitReached)
    }

    @Test
    fun eraserRadiusIsConstrainedAndStillWorksAtCapacity() {
        val stroke = WhiteboardStroke(points = List(Whiteboard.MAX_POINTS) { point(500f, 500f) })
        val before = Whiteboard(listOf(stroke))
        val tooSmall = WhiteboardGestureSession(before, true, 0xFF202124L, 3f, eraserRadius = -10f)
        tooSmall.begin(point(500f, 510f))
        assertTrue(tooSmall.working.strokes.isEmpty())
        val tooLarge = WhiteboardGestureSession(before, true, 0xFF202124L, 3f, eraserRadius = 1000f)
        tooLarge.begin(point(500f, 650f))
        assertSame(before, tooLarge.working)
    }

    @Test
    fun localEraseTracksTheVisibleCircleAcrossViewportSizesAndZoomLevels() {
        val before = Whiteboard(listOf(stroke(100f, 500f, 900f, 500f)))
        for ((width, height) in listOf(400f to 650f, 800f to 1300f)) for (zoom in listOf(1f, 2f, 8f)) {
            val transform = WhiteboardViewport(zoom = zoom).transform(width, height)
            val screen = transform.worldToScreen(500f, 500f)
            val world = transform.screenToWorld(screen.x, screen.y)
            val session = WhiteboardGestureSession(before, true, 0xFF202124L, 6f, eraserRadius = 28f)

            session.begin(point(world.x, world.y))

            assertEquals(2, session.working.strokes.size)
            val left = session.working.strokes.first().points.last()
            val right = session.working.strokes.last().points.first()
            assertEquals((28f + 3f) * transform.scale, screen.x - transform.worldToScreen(left.x, left.y).x, 0.001f)
            assertEquals((28f + 3f) * transform.scale, transform.worldToScreen(right.x, right.y).x - screen.x, 0.001f)
        }
    }

    private fun session(
        before: Whiteboard = Whiteboard(),
        erasing: Boolean = false,
        color: Long = 0xFF202124L,
        width: Float = 6f,
    ) = WhiteboardGestureSession(before, erasing, color, width)

    private fun point(x: Float, y: Float) = WhiteboardPoint(x, y)

    private fun stroke(x1: Float, y1: Float, x2: Float, y2: Float) =
        WhiteboardStroke(points = listOf(point(x1, y1), point(x2, y2)))
}
