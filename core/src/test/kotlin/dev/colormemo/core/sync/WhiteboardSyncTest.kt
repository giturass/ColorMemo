package dev.colormemo.core.sync

import dev.colormemo.core.model.Note
import dev.colormemo.core.model.Whiteboard
import dev.colormemo.core.model.WhiteboardBrush
import dev.colormemo.core.model.WhiteboardPoint
import dev.colormemo.core.model.WhiteboardStroke
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class WhiteboardSyncTest {
    private val note = Note(id = "note", revision = "revision", deviceId = "device", createdAt = 1, updatedAt = 1)
    private val stroke = WhiteboardStroke(points = listOf(WhiteboardPoint(0f, 0f), WhiteboardPoint(1000f, 1000f)))
    private val board = Whiteboard(strokes = listOf(stroke))

    @Test
    fun legacyTextDocumentDecodesAndReencodesWithoutAddingAnyFields() {
        val legacy = """
            {"schemaVersion":1,"notes":[{
            "id":"note","title":"","content":"","color":"DEFAULT","isPinned":false,"isArchived":false,
            "deletedAt":null,"isPurged":false,"reminderAt":null,"createdAt":1,"updatedAt":1,
            "revision":"revision","parentRevision":null,"deviceId":"device","ancestorRevisions":[],
            "conflictOf":null,"conflictRevision":null}]}
        """.trimIndent().replace("\n", "")
        val decoded = SyncJson.decode(legacy)
        assertEquals(note, decoded.notes.single())
        assertNull(decoded.notes.single().whiteboard)
        assertEquals(legacy, SyncJson.encode(decoded))
    }

    @Test
    fun mixedTextAndWhiteboardDocumentRetainsStrokeOrderAndMetadata() {
        val drawing = board.copy(strokes = board.strokes + stroke.copy(color = 0xFFF44336, width = 40f))
        val source = SyncDocument(notes = listOf(note.copy(whiteboard = drawing), note.copy(id = "text")))
        assertEquals(source, SyncJson.decodeDocument(SyncJson.encodeDocument(source)))
    }

    @Test
    fun emptyWhiteboardRetainsItsTypeAfterSerialization() {
        val source = SyncDocument(notes = listOf(note.copy(whiteboard = Whiteboard())))
        assertEquals(source, SyncJson.decode(SyncJson.encode(source)))
        assertNotNull(SyncJson.decode(SyncJson.encode(source)).notes.single().whiteboard)
    }

    @Test
    fun singlePointStrokeAndAllCanvasEdgesAreValid() {
        val edges = listOf(WhiteboardPoint(0f, 0f), WhiteboardPoint(1000f, 0f),
            WhiteboardPoint(0f, 2000f), WhiteboardPoint(1000f, 2000f))
        val drawing = Whiteboard(edges.mapIndexed { index, point ->
            WhiteboardStroke(
                color = if (index % 2 == 0) 0xFF000000 else 0xFFFFFFFF,
                width = if (index % 2 == 0) 1f else 40f,
                points = listOf(point),
            )
        })
        val document = SyncDocument(notes = listOf(note.copy(whiteboard = drawing)))
        assertEquals(document, SyncJson.decode(SyncJson.encode(document)))
    }

    @Test
    fun maximumStrokeAndPointCountsRoundTrip() {
        val drawing = Whiteboard(List(Whiteboard.MAX_STROKES) {
            stroke.copy(points = List(Whiteboard.MAX_POINTS / Whiteboard.MAX_STROKES) { WhiteboardPoint(25f, 75f) })
        })
        val document = SyncDocument(notes = listOf(note.copy(whiteboard = drawing)))
        assertEquals(document, SyncJson.decode(SyncJson.encode(document)))
    }

    @Test
    fun strokeAndCombinedPointLimitsAreEnforcedOnImportAndExport() {
        assertRejected(Whiteboard(List(Whiteboard.MAX_STROKES + 1) { stroke }))
        val fullStroke = stroke.copy(points = List(Whiteboard.MAX_POINTS) { WhiteboardPoint(25f, 75f) })
        assertRejected(Whiteboard(listOf(fullStroke, stroke)))
        assertRejected(Whiteboard(listOf(fullStroke.copy(points = fullStroke.points + WhiteboardPoint(0f, 0f)))))
    }

    @Test
    fun emptyStrokesAndInvalidPenAttributesAreRejectedOnImportAndExport() {
        assertRejected(Whiteboard(listOf(stroke.copy(points = emptyList()))))
        listOf(-1L, 0L, 0xFEFFFFFFL, 0x100000000L).forEach { color ->
            assertRejected(Whiteboard(listOf(stroke.copy(color = color))))
        }
        listOf(-1f, 0f, 0.99f, 40.01f).forEach { width ->
            assertRejected(Whiteboard(listOf(stroke.copy(width = width))))
        }
    }

    @Test
    fun outOfBoundsCoordinatesAreRejectedOnBothAxes() {
        listOf(WhiteboardPoint(-0.01f, 0f), WhiteboardPoint(0f, -0.01f),
            WhiteboardPoint(1000.01f, 0f), WhiteboardPoint(0f, 2000.01f)).forEach { point ->
            assertRejected(Whiteboard(listOf(stroke.copy(points = listOf(point)))))
        }
    }

    @Test
    fun nonFiniteGeometryIsRejectedBeforeExport() {
        listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY).forEach { value ->
            listOf(stroke.copy(width = value), stroke.copy(points = listOf(WhiteboardPoint(value, 0f))),
                stroke.copy(points = listOf(WhiteboardPoint(0f, value)))).forEach { invalid ->
                assertThrows(SyncProtocolException::class.java) {
                    SyncJson.encode(SyncDocument(notes = listOf(note.copy(whiteboard = Whiteboard(listOf(invalid))))))
                }
            }
        }
    }

    @Test
    fun maliciousNonFiniteAndUnknownStrokeFieldsFailClosedDuringImport() {
        val encoded = SyncJson.encode(SyncDocument(notes = listOf(note.copy(whiteboard = board))))
        listOf("NaN", "Infinity", "-Infinity", "1e100").forEach { value ->
            assertThrows(SyncProtocolException::class.java) { SyncJson.decode(encoded.replace("\"x\":0.0", "\"x\":$value")) }
        }
        assertThrows(SyncProtocolException::class.java) {
            SyncJson.decode(encoded.replace("\"width\":6.0", "\"width\":6.0,\"futureBrush\":true"))
        }
    }

    @Test
    fun purgeClearsWhiteboardButTrashAndRestoreRetainDrawing() {
        val source = note.copy(whiteboard = board)
        val deleted = source.copy(deletedAt = 2).edited("device", 2, "deleted")
        assertEquals(board, deleted.whiteboard)
        val restored = deleted.copy(deletedAt = null).edited("device", 3, "restored")
        assertEquals(board, restored.whiteboard)
        val purged = deleted.copy(isPurged = true).edited("device", 4, "purged")
        assertNull(purged.whiteboard)
        assertNotNull(purged.deletedAt)
        assertEquals(purged, SyncJson.decode(SyncJson.encode(SyncDocument(notes = listOf(purged)))).notes.single())
        assertEquals(listOf(purged), MergeEngine.merge(listOf(purged), listOf(source)).notes)
    }

    @Test
    fun purgedRecordsCannotRetainEvenAnEmptyWhiteboard() {
        listOf(Whiteboard(), board).forEach { drawing ->
            val document = SyncDocument(notes = listOf(note.copy(isPurged = true, deletedAt = 2, whiteboard = drawing)))
            assertThrows(SyncProtocolException::class.java) { SyncJson.validate(document) }
            assertThrows(SyncProtocolException::class.java) { SyncJson.decode(SyncJson.json.encodeToString(document)) }
        }
    }

    @Test
    fun brushesRoundTripAndLegacyStrokesKeepTheirAppearance() {
        val source = SyncDocument(notes = listOf(note.copy(whiteboard = Whiteboard(
            WhiteboardBrush.entries.map { stroke.copy(brush = it, width = 40f) },
        ))))
        assertEquals(source, SyncJson.decode(SyncJson.encode(source)))
        val legacy = SyncJson.encode(SyncDocument(notes = listOf(note.copy(whiteboard = board))))
        org.junit.Assert.assertFalse(legacy.contains("brush"))
        assertEquals(WhiteboardBrush.FINELINER, SyncJson.decode(legacy).notes.single().whiteboard!!.strokes.single().brush)
        assertThrows(SyncProtocolException::class.java) { SyncJson.decode(SyncJson.encode(source).replace("WATERCOLOR", "UNKNOWN_BRUSH")) }
    }

    private fun assertRejected(drawing: Whiteboard) {
        val document = SyncDocument(notes = listOf(note.copy(whiteboard = drawing)))
        assertThrows(SyncProtocolException::class.java) { SyncJson.encode(document) }
        assertThrows(SyncProtocolException::class.java) { SyncJson.decode(SyncJson.json.encodeToString(document)) }
    }
}
