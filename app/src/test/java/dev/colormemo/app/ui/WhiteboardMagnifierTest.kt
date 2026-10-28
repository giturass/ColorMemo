package dev.colormemo.app.ui

import dev.colormemo.core.model.WhiteboardPoint
import org.junit.Assert.*
import org.junit.Test

class WhiteboardMagnifierTest {
    @Test
    fun lensFollowsAboveTheFingerWithRoomToSeeTheEndpoint() {
        val lens = whiteboardMagnifierPlacement(360f, 640f, 180f, 300f, 1f)!!
        assertEquals(WhiteboardMagnifierSide.ABOVE, lens.side)
        assertEquals(180f, lens.centerX, 0.01f)
        assertEquals(36f, 300f - (lens.centerY + lens.height / 2f), 0.01f)
    }

    @Test
    fun lensStaysInsideTheViewportAndOutsideTheFingerInBothOrientations() {
        for (density in listOf(1f, 1.5f, 2.75f)) {
            for ((width, height) in listOf(320f to 480f, 640f to 240f, 240f to 320f)) {
                var previous: WhiteboardMagnifierPlacement? = null
                for (xStep in 0..10) for (yStep in 0..10) {
                    val x = width * xStep / 10f
                    val y = height * yStep / 10f
                    val lens = whiteboardMagnifierPlacement(width * density, height * density,
                        x * density, y * density, density, previous)
                    assertNotNull("No lens at ($x, $y) on $width x $height with density $density", lens)
                    val left = (lens!!.centerX - lens.width / 2f) / density
                    val right = (lens.centerX + lens.width / 2f) / density
                    val top = (lens.centerY - lens.height / 2f) / density
                    val bottom = (lens.centerY + lens.height / 2f) / density
                    assertTrue(left >= 7.99f && top >= 7.99f && right <= width - 7.99f && bottom <= height - 7.99f)
                    assertTrue(right <= x - 35.99f || left >= x + 35.99f || bottom <= y - 35.99f || top >= y + 35.99f)
                    previous = lens
                }
            }
        }
    }

    @Test
    fun topEdgeUsesASideAndSmallMovementsDoNotFlipTheLens() {
        val top = whiteboardMagnifierPlacement(360f, 640f, 170f, 20f, 1f)!!
        assertEquals(WhiteboardMagnifierSide.RIGHT, top.side)
        val crossedCenter = whiteboardMagnifierPlacement(360f, 640f, 180f, 20f, 1f, top)!!
        assertEquals(top.side, crossedCenter.side)
        val nearThreshold = whiteboardMagnifierPlacement(360f, 640f, 180f, 130f, 1f, crossedCenter)!!
        assertEquals(top.side, nearThreshold.side)
        val lower = whiteboardMagnifierPlacement(360f, 640f, 180f, 145f, 1f, nearThreshold)!!
        assertEquals(WhiteboardMagnifierSide.ABOVE, lower.side)
    }

    @Test
    fun narrowViewportCanUseTheSpaceBelowTheFinger() {
        val lens = whiteboardMagnifierPlacement(180f, 400f, 90f, 20f, 1f)!!
        assertEquals(WhiteboardMagnifierSide.BELOW, lens.side)
        assertTrue(lens.centerY - lens.height / 2f >= 56f)
    }

    @Test
    fun insufficientSpaceAndInvalidPointersHideTheLens() {
        assertNull(whiteboardMagnifierPlacement(140f, 140f, 70f, 70f, 1f))
        assertNull(whiteboardMagnifierPlacement(60f, 400f, 30f, 20f, 1f))
        assertNull(whiteboardMagnifierPlacement(0f, 0f, 0f, 0f, 1f))
        assertNull(whiteboardMagnifierPlacement(360f, 640f, -1f, 20f, 1f))
        assertNull(whiteboardMagnifierPlacement(360f, 640f, 20f, 641f, 1f))
        assertNull(whiteboardMagnifierPlacement(360f, 640f, Float.NaN, 20f, 1f))
        assertNull(whiteboardMagnifierPlacement(360f, 640f, 20f, 20f, 0f))
    }

    @Test
    fun triangleAndAngleFocusOnTheSnappedEndpointAndStopForNavigation() {
        for (shape in listOf(WhiteboardShape.TRIANGLE, WhiteboardShape.ANGLE)) {
            val base = WhiteboardTwoEdgeBase(WhiteboardPoint(200f, 300f), WhiteboardPoint(650f, 700f))
            val drag = WhiteboardTwoEdgeDrag(shape, base, WhiteboardPoint(208f, 295f), 20f)
            assertEquals(base.first, drag.drawingTip)
            drag.update(WhiteboardPoint(308f, 175f))
            assertEquals(WhiteboardPoint(300f, 180f), drag.drawingTip)
            drag.startNavigation()
            drag.update(WhiteboardPoint(400f, 400f))
            assertNull(drag.drawingTip)
            val missed = WhiteboardTwoEdgeDrag(shape, base, WhiteboardPoint(400f, 500f), 20f)
            assertNull(missed.drawingTip)
        }
    }

    @Test
    fun compassFocusFollowsTheActualArcWhenTheFingerMovesOutsideItsRadius() {
        val state = WhiteboardInstrumentState(radius = 100f)
        val drag = WhiteboardInstrumentDrag(state, WhiteboardInstrument.COMPASS,
            state.handlePoint(InstrumentHandle.PEN), 20f)
        drag.update(WhiteboardPoint(500f, 250f))
        assertEquals(WhiteboardPoint(500f, 400f), drag.drawingTip)
        assertEquals(drag.drawingPoints.last(), drag.drawingTip)
        drag.update(state.pointAt(137f, 200f))
        assertEquals(drag.drawingPoints.last(), drag.drawingTip)
        drag.startNavigation()
        assertNull(drag.drawingTip)
    }

    @Test
    fun rulerFocusTracksTheCurrentContactEvenWhenReversingAlongExistingInk() {
        val state = WhiteboardInstrumentState(radius = 100f)
        val drag = WhiteboardInstrumentDrag(state, WhiteboardInstrument.RULER, state.rulerPoint(0f), 20f)
        drag.update(state.rulerPoint(150f, 10f))
        drag.update(state.rulerPoint(50f, 10f))
        assertEquals(state.rulerPoint(50f), drag.drawingTip)
        assertEquals(state.rulerPoint(150f), drag.drawingPoints.last())
        drag.startNavigation()
        assertNull(drag.drawingTip)
    }

    @Test
    fun movingAnInstrumentDoesNotShowAnInkMagnifier() {
        for (instrument in WhiteboardInstrument.entries) {
            val state = WhiteboardInstrumentState(radius = 100f)
            val drag = WhiteboardInstrumentDrag(state, instrument, state.center, 20f)
            drag.update(WhiteboardPoint(550f, 550f))
            assertNull(drag.drawingTip)
        }
    }
}
