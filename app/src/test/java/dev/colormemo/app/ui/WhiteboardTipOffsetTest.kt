package dev.colormemo.app.ui

import dev.colormemo.core.model.Whiteboard
import dev.colormemo.core.model.WhiteboardPoint
import dev.colormemo.core.sync.SyncJson
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

class WhiteboardTipOffsetTest {
    @Test
    fun offsetKeepsTheSamePhysicalDistanceAcrossDensitiesZoomAndPan() {
        for (density in listOf(1f, 1.5f, 2.75f)) {
            for ((width, height) in listOf(360f to 640f, 640f to 360f)) {
                for (zoom in listOf(1f, 4f, 8f, 16f)) {
                    val transform = WhiteboardViewport(450f, 1300f, zoom).transform(width * density, height * density)
                    val input = WhiteboardDrawingInput(transform, WHITEBOARD_TIP_OFFSET_DP * density)
                    val x = width * density / 2f
                    val y = height * density / 2f
                    assertTrue(input.contains(x, y))
                    val tip = input.pointAt(x, y)
                    val screen = transform.worldToScreen(tip.x, tip.y)
                    // Saved points use the existing half-unit whiteboard grid.
                    val tolerance = 0.251f * transform.scale
                    assertEquals(x, screen.x, tolerance)
                    assertEquals(48f * density, y - screen.y, tolerance)
                }
            }
        }
    }

    @Test
    fun linePreviewFinalPointAndSavedCoordinatesAllUseTheOffsetTip() {
        val transform = transform()
        val input = WhiteboardDrawingInput(transform, 48f)
        val session = WhiteboardGestureSession(Whiteboard(), false, 0xFF1967D2L, 3f, shape = WhiteboardShape.LINE)
        session.begin(input.pointAt(100f, 200f))
        session.update(input.pointAt(250f, 350f))
        assertEquals(listOf(WhiteboardPoint(100f, 152f), WhiteboardPoint(250f, 302f)), session.working.strokes.single().points)
        session.update(input.pointAt(251f, 351f), finalPoint = true)
        val saved = SyncJson.json.decodeFromString<Whiteboard>(SyncJson.json.encodeToString(session.working))
        assertEquals(listOf(WhiteboardPoint(100f, 152f), WhiteboardPoint(251f, 303f)), saved.strokes.single().points)
        assertEquals(session.working, saved)
        assertSame(session.before, session.startNavigation())
        session.update(input.pointAt(280f, 400f), finalPoint = true)
        assertFalse(session.hasChanges)
    }

    @Test
    fun topEdgeShortensOffsetContinuouslyWithoutFlippingOrLeavingTheViewport() {
        val transform = transform().copy(originY = -200f)
        val input = WhiteboardDrawingInput(transform, 48f)
        var previous = 0f
        for (fingerY in 0..100) {
            val tip = input.pointAt(180f, fingerY.toFloat())
            val screen = transform.worldToScreen(tip.x, tip.y)
            assertTrue(input.contains(180f, fingerY.toFloat()))
            assertTrue(screen.y in 0f..fingerY.toFloat())
            assertTrue(screen.y - previous in 0f..1.001f)
            previous = screen.y
        }
        assertFalse(input.contains(180f, -1f))
        assertFalse(input.contains(180f, 641f))
        assertFalse(input.contains(-1f, 300f))
        assertFalse(input.contains(361f, 300f))
    }

    @Test
    fun boardHitTestingAndExpansionUseTheTipEvenWhenTheFingerIsBelowTheBoard() {
        val transform = transform().copy(scale = 0.5f, canvasHeight = 1000f)
        val input = WhiteboardDrawingInput(transform, 48f)
        assertTrue(input.contains(180f, 540f))
        assertEquals(984f, input.pointAt(180f, 540f).y, 0.01f)
        assertFalse(input.contains(180f, 600f))
        assertTrue(input.contains(180f, 600f, canvasHeight = 1500f))
        assertEquals(1104f, input.pointAt(180f, 600f).y, 0.01f)
        val letterbox = WhiteboardDrawingInput(transform.copy(originX = 80f, scale = 0.2f), 48f)
        assertFalse(letterbox.contains(79f, 100f))
        assertTrue(letterbox.contains(80f, 100f))
    }

    @Test
    fun disabledOffsetPreservesDirectDrawingAndOtherToolCoordinates() {
        val transform = transform().copy(originY = -200f, scale = 2f)
        val direct = WhiteboardDrawingInput(transform)
        assertEquals(WhiteboardPoint(90f, 250f), direct.pointAt(180f, 300f))
        assertFalse(direct.contains(180f, 641f))
        // The cut tool can still map positions outside the viewport while dragging.
        assertEquals(WhiteboardPoint(200f, 450f), direct.pointAt(400f, 700f))
    }

    @Test
    fun twoEdgeShapesSnapAtTheOffsetCursorAndKeepEitherVertexFixed() {
        val input = WhiteboardDrawingInput(transform(), 48f)
        for (shape in listOf(WhiteboardShape.TRIANGLE, WhiteboardShape.ANGLE)) {
            val first = WhiteboardTwoEdgeDrag(shape, null, input.pointAt(100f, 248f), 24f)
            first.update(input.pointAt(300f, 448f))
            val base = first.finish().base!!
            assertEquals(WhiteboardTwoEdgeBase(WhiteboardPoint(100f, 200f), WhiteboardPoint(300f, 400f)), base)
            for (vertex in base.points) {
                val down = input.pointAt(vertex.x + 8f, vertex.y + 48f - 5f)
                val second = WhiteboardTwoEdgeDrag(shape, base, down, WHITEBOARD_TIP_SNAP_RADIUS_DP)
                assertEquals(vertex, second.drawingTip)
                second.update(input.pointAt(vertex.x + 58f, vertex.y + 48f - 105f))
                val tip = WhiteboardPoint(vertex.x + 50f, vertex.y - 100f)
                assertEquals(tip, second.drawingTip)
                assertEquals(listOf(base.points.single { it != vertex }, vertex, tip), second.finish().drawingPoints.take(3))
                assertEquals(base.points, second.startNavigation())
                assertNull(second.drawingTip)
                assertEquals(base, second.finish().base)
            }
        }
    }

    @Test
    fun offsetCursorCanFindAnEndpointAfterTouchDownWithoutDrawingAnExtraSegment() {
        val input = WhiteboardDrawingInput(transform(), 48f)
        for (shape in listOf(WhiteboardShape.TRIANGLE, WhiteboardShape.ANGLE)) {
            val base = WhiteboardTwoEdgeBase(WhiteboardPoint(100f, 200f), WhiteboardPoint(300f, 400f))
            val drag = WhiteboardTwoEdgeDrag(shape, base, input.pointAt(200f, 248f), WHITEBOARD_TIP_SNAP_RADIUS_DP,
                acquireEndpointWhileDragging = true)
            assertNull(drag.drawingTip)
            assertEquals(base.points, drag.update(input.pointAt(100f, 248f)))
            assertEquals(base.first, drag.drawingTip)
            drag.update(input.pointAt(150f, 148f))
            assertEquals(listOf(base.second, base.first, WhiteboardPoint(150f, 100f)), drag.finish().drawingPoints.take(3))
            drag.startNavigation()
            drag.update(input.pointAt(300f, 448f))
            assertNull(drag.drawingTip)
            assertEquals(base, drag.finish().base)
        }
    }

    @Test
    fun snappingKeepsAtLeastThirtySixDpBetweenTheFingerAndTheSecondEdgeTip() {
        for (density in listOf(1f, 2.75f)) {
            val transform = transform().copy(viewportWidth = 360f * density,
                viewportHeight = 640f * density, scale = density)
            val input = WhiteboardDrawingInput(transform, WHITEBOARD_TIP_OFFSET_DP * density)
            val base = WhiteboardTwoEdgeBase(WhiteboardPoint(100f, 200f), WhiteboardPoint(300f, 400f))
            for (grabY in listOf(-12f, 0f, 12f)) {
                val down = input.pointAt(100f * density, (200f + 48f + grabY) * density)
                val drag = WhiteboardTwoEdgeDrag(WhiteboardShape.ANGLE, base, down,
                    WHITEBOARD_TIP_SNAP_RADIUS_DP * density / transform.scale)
                assertEquals(base.first, drag.drawingTip)
                val fingerY = (100f + 48f + grabY) * density
                drag.update(input.pointAt(150f * density, fingerY))
                val tip = drag.drawingTip!!
                val screen = transform.worldToScreen(tip.x, tip.y)
                assertTrue(fingerY - screen.y >= 36f * density)
            }
        }
    }

    private fun transform() = WhiteboardTransform(360f, 640f, 0f, 0f, 1f)
}
