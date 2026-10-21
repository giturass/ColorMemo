package dev.colormemo.app.ui

import dev.colormemo.core.model.Whiteboard
import dev.colormemo.core.model.WhiteboardImage
import dev.colormemo.core.model.WhiteboardPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WhiteboardImagesTest {
    private val image = WhiteboardImage("image", "attachment", 0f, 0f, 400f, 200f)

    @Test
    fun insertsInsideCurrentViewportAtEveryZoomIncludingLowerCanvas() {
        for (extent in listOf(1000f, 1500f, 4000f)) for (zoom in listOf(1f, 2f, 8f)) for (centerY in listOf(0f, 500f, 1900f)) {
            val transform = WhiteboardViewport(centerY = centerY, zoom = zoom).transform(400f, 650f, extent)
            for (source in listOf(image, image.copy(width = 10f, height = 2000f))) {
                val placed = source.placedIn(transform)
                assertEquals(source.width / source.height, placed.width / placed.height, 0.001f)
                assertTrue(placed.x >= 0f && placed.y >= 0f)
                assertTrue(placed.x + placed.width <= Whiteboard.CANVAS_WIDTH)
                assertTrue(placed.y + placed.height <= extent)
                val topLeft = transform.worldToScreen(placed.x, placed.y)
                val bottomRight = transform.worldToScreen(placed.x + placed.width, placed.y + placed.height)
                assertTrue(transform.containsScreenPoint(topLeft.x, topLeft.y))
                assertTrue(transform.containsScreenPoint(bottomRight.x, bottomRight.y))
            }
        }
    }

    @Test
    fun drawingErasingAndSwitchingToNavigationPreserveImageLayer() {
        val before = Whiteboard(images = listOf(image))
        val drawing = WhiteboardGestureSession(before, false, 0xFF202124, 6f)
        drawing.begin(WhiteboardPoint(100f, 100f))
        drawing.update(WhiteboardPoint(200f, 100f), finalPoint = true)
        assertEquals(before.images, drawing.working.images)
        assertTrue(drawing.working.strokes.isNotEmpty())
        val eraser = WhiteboardGestureSession(drawing.working, true, 0xFF202124, 6f)
        eraser.begin(WhiteboardPoint(150f, 100f))
        assertEquals(2, eraser.working.strokes.size)
        assertEquals(WhiteboardPoint(100f, 100f), eraser.working.strokes.first().points.first())
        assertEquals(WhiteboardPoint(200f, 100f), eraser.working.strokes.last().points.last())
        assertEquals(before.images, eraser.working.images)
        assertEquals(before, drawing.startNavigation())
    }
}
