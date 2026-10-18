package dev.colormemo.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WhiteboardViewportTest {
    @Test
    fun defaultCameraShowsAnEnlargedBoardAtTheViewportCenter() {
        val transform = WhiteboardViewport().transform(400f, 700f)

        assertEquals(0.8f, transform.scale, EPSILON)
        assertEquals(800f, transform.canvasSizePx, EPSILON)
        assertPoint(200f, 350f, transform.worldToScreen(500f, 500f))
        assertPoint(500f, 500f, transform.screenToWorld(200f, 350f))
    }

    @Test
    fun pinchKeepsTheWorldPointUnderAnOffCenterCentroid() {
        val viewport = WhiteboardViewport()
        val anchor = viewport.transform(600f, 800f).screenToWorld(170f, 310f)

        val zoomed = viewport.applyGesture(600f, 800f, 170f, 310f, zoomChange = 1.5f)

        assertEquals(3f, zoomed.zoom, EPSILON)
        assertPoint(170f, 310f, zoomed.transform(600f, 800f).worldToScreen(anchor.x, anchor.y))
    }

    @Test
    fun combinedPanAndPinchMovesTheAnchorWithTheFingers() {
        val viewport = WhiteboardViewport()
        val anchor = viewport.transform(600f, 800f).screenToWorld(200f, 300f)

        val moved = viewport.applyGesture(
            600f, 800f, 200f, 300f,
            panX = 45f, panY = -30f, zoomChange = 1.4f,
        )

        assertPoint(245f, 270f, moved.transform(600f, 800f).worldToScreen(anchor.x, anchor.y))
    }

    @Test
    fun aPanMovesTheBoardByScreenPixelsAtAnyZoom() {
        for (zoom in listOf(2f, 4f, 8f)) {
            val viewport = WhiteboardViewport(zoom = zoom)
            val before = viewport.transform(600f, 800f).worldToScreen(450f, 500f)

            val moved = viewport.applyGesture(600f, 800f, 300f, 400f, panX = 24f, panY = -12f)
            val after = moved.transform(600f, 800f).worldToScreen(450f, 500f)

            assertPoint(before.x + 24f, before.y - 12f, after)
        }
    }

    @Test
    fun panningStopsAtEveryBoardEdgeWithoutExposingBlankSpace() {
        val viewport = WhiteboardViewport()
        val topLeft = viewport.applyGesture(600f, 800f, 300f, 400f, panX = 10000f, panY = 10000f)
            .transform(600f, 800f)
        val bottomRight = viewport.applyGesture(600f, 800f, 300f, 400f, panX = -10000f, panY = -10000f)
            .transform(600f, 800f)

        assertPoint(0f, 0f, topLeft.worldToScreen(0f, 0f))
        assertPoint(600f, 800f, bottomRight.worldToScreen(1000f, 1000f))
        assertTrue(topLeft.containsScreenPoint(0f, 0f))
        assertTrue(bottomRight.containsScreenPoint(600f, 800f))
    }

    @Test
    fun fitViewCentersTheWholeSquareBoardInBothOrientations() {
        val viewport = WhiteboardViewport(centerX = 100f, centerY = 900f, zoom = 1f)
        val portrait = viewport.transform(400f, 700f)
        val landscape = viewport.transform(700f, 400f)

        assertPoint(0f, 150f, portrait.worldToScreen(0f, 0f))
        assertPoint(400f, 550f, portrait.worldToScreen(1000f, 1000f))
        assertPoint(150f, 0f, landscape.worldToScreen(0f, 0f))
        assertPoint(550f, 400f, landscape.worldToScreen(1000f, 1000f))
    }

    @Test
    fun blankMarginsAndPositionsOutsideTheViewportAreNotDrawable() {
        val fitted = WhiteboardViewport(zoom = 1f).transform(400f, 700f)
        val enlarged = WhiteboardViewport(zoom = 4f).transform(400f, 700f)

        assertFalse(fitted.containsScreenPoint(200f, 100f))
        assertFalse(fitted.containsScreenPoint(200f, 600f))
        assertTrue(fitted.containsScreenPoint(0f, 150f))
        assertTrue(fitted.containsScreenPoint(400f, 550f))
        assertTrue(fitted.screenToWorld(200f, 100f).y < 0f)
        assertTrue(fitted.screenToWorld(200f, 600f).y > 1000f)
        assertFalse(enlarged.containsScreenPoint(-1f, 300f))
        assertFalse(enlarged.containsScreenPoint(200f, 701f))
    }

    @Test
    fun zoomLimitsDoNotCauseTheAnchorToJump() {
        val viewport = WhiteboardViewport(zoom = 4f)
        val anchor = viewport.transform(600f, 800f).screenToWorld(200f, 300f)
        val maximum = viewport.applyGesture(600f, 800f, 200f, 300f, zoomChange = 100f)
        val unchanged = maximum.applyGesture(600f, 800f, 200f, 300f, zoomChange = 2f)
        val minimum = maximum.applyGesture(600f, 800f, 200f, 300f, zoomChange = 0.0001f)

        assertEquals(WhiteboardViewport.MAX_ZOOM, maximum.zoom, EPSILON)
        assertPoint(200f, 300f, maximum.transform(600f, 800f).worldToScreen(anchor.x, anchor.y))
        assertEquals(maximum.centerX, unchanged.centerX, EPSILON)
        assertEquals(maximum.centerY, unchanged.centerY, EPSILON)
        assertEquals(WhiteboardViewport.MIN_ZOOM, minimum.zoom, EPSILON)
        assertPoint(300f, 400f, minimum.transform(600f, 800f).worldToScreen(500f, 500f))
    }

    @Test
    fun coordinateTransformsRoundTripAfterZoomPanAndResize() {
        val viewport = WhiteboardViewport().applyGesture(
            600f, 800f, 170f, 210f,
            panX = -35f, panY = 50f, zoomChange = 2.5f,
        )

        for ((width, height) in listOf(600f to 800f, 800f to 600f, 360f to 1000f)) {
            val transform = viewport.transform(width, height)
            for ((x, y) in listOf(0f to 0f, 123.5f to 987f, 500f to 500f, 1000f to 1000f)) {
                val screen = transform.worldToScreen(x, y)
                assertPoint(x, y, transform.screenToWorld(screen.x, screen.y))
            }
        }
    }

    @Test
    fun resizingPreservesTheCameraCenterWhereTheBoardCanCoverTheViewport() {
        val viewport = WhiteboardViewport(centerX = 425f, centerY = 575f, zoom = 4f)
        val portrait = viewport.transform(400f, 700f)
        val landscape = viewport.transform(700f, 400f)

        assertPoint(200f, 350f, portrait.worldToScreen(425f, 575f))
        assertPoint(350f, 200f, landscape.worldToScreen(425f, 575f))
        assertEquals(portrait.scale, landscape.scale, EPSILON)
    }

    @Test
    fun resizingConstrainsTheNewVisibleAreaAndCentersAnUndersizedAxis() {
        val viewport = WhiteboardViewport(centerX = 250f, centerY = 250f, zoom = 2f)

        val resized = viewport.constrained(800f, 300f)
        val transform = resized.transform(800f, 300f)

        assertEquals(500f, resized.centerX, EPSILON)
        assertEquals(250f, resized.centerY, EPSILON)
        assertPoint(100f, 0f, transform.worldToScreen(0f, 0f))
        assertPoint(700f, 600f, transform.worldToScreen(1000f, 1000f))
    }

    private fun assertPoint(x: Float, y: Float, actual: WhiteboardViewportPoint) {
        assertEquals(x, actual.x, EPSILON)
        assertEquals(y, actual.y, EPSILON)
    }

    companion object {
        private const val EPSILON = 0.0002f
    }
}
