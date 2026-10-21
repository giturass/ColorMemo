package dev.colormemo.core.sync

import dev.colormemo.core.model.Note
import dev.colormemo.core.model.NoteAttachment
import dev.colormemo.core.model.WhiteboardImage
import dev.colormemo.core.model.Whiteboard
import dev.colormemo.core.model.WhiteboardBrush
import dev.colormemo.core.model.WhiteboardPoint
import dev.colormemo.core.model.WhiteboardStroke
import dev.colormemo.core.model.WhiteboardText
import dev.colormemo.core.model.WhiteboardRect
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
    fun cutoutsAndLowerCanvasContentRoundTripInBackupAndEncryptedSync() {
        val attachment = NoteAttachment.create("image.png", "image/png", byteArrayOf(1, 2))
        val holes = listOf(WhiteboardRect(200f, 3000f, 500f, 3500f))
        val clipped = Whiteboard(
            strokes = listOf(stroke.copy(points = listOf(WhiteboardPoint(1000f, 4000f)), cutouts = holes)),
            images = listOf(WhiteboardImage("image", attachment.id, 0f, 2000f, 1000f, 2000f, holes)),
            texts = listOf(WhiteboardText("text", "下半页", 100f, 3000f, 600f, cutouts = holes)),
        )
        val document = SyncDocument(notes = listOf(note.copy(whiteboard = clipped, attachments = listOf(attachment))))
        assertEquals(document, SyncJson.decodeDocument(SyncJson.encodeDocument(document)))
        val key = SyncEncryptionKey.parse(SyncEncryptionKey.generate())
        assertEquals(document, EncryptedSyncJson.decode(EncryptedSyncJson.encode(document, key), key))
        val legacy = SyncJson.encode(SyncDocument(notes = listOf(note.copy(whiteboard = board))))
        org.junit.Assert.assertFalse(legacy.contains("cutouts"))
        assertEquals(board, SyncJson.decode(legacy).notes.single().whiteboard)
    }

    @Test
    fun invalidCutoutsAreRejectedOnEveryLayerBeforeImportOrExport() {
        val attachment = NoteAttachment.create("image.png", "image/png", byteArrayOf(1, 2))
        val image = WhiteboardImage("image", attachment.id, 0f, 0f, 500f, 500f)
        val text = WhiteboardText("text", "文字", 100f, 100f, 200f)
        val area = WhiteboardRect(10f, 20f, 100f, 200f)
        val invalid = listOf(listOf(area.copy(left = -1f)), listOf(area.copy(top = -1f)),
            listOf(area.copy(right = 1001f)), listOf(area.copy(bottom = 4001f)),
            listOf(area.copy(right = 10f)), listOf(area.copy(bottom = 19f)),
            List(Whiteboard.MAX_CUTOUTS_PER_LAYER + 1) { area })
        invalid.forEach { holes ->
            listOf(Whiteboard(strokes = listOf(stroke.copy(cutouts = holes))),
                Whiteboard(images = listOf(image.copy(cutouts = holes))),
                Whiteboard(texts = listOf(text.copy(cutouts = holes)))).forEach { drawing ->
                val document = SyncDocument(notes = listOf(note.copy(whiteboard = drawing, attachments = listOf(attachment))))
                assertThrows(SyncProtocolException::class.java) { SyncJson.encode(document) }
                assertThrows(SyncProtocolException::class.java) { SyncJson.decode(SyncJson.json.encodeToString(document)) }
            }
        }
        listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY).forEach { value ->
            listOf(area.copy(left = value), area.copy(top = value), area.copy(right = value), area.copy(bottom = value)).forEach { hole ->
                val drawing = Whiteboard(strokes = listOf(stroke.copy(cutouts = listOf(hole))))
                assertThrows(SyncProtocolException::class.java) { SyncJson.validate(SyncDocument(notes = listOf(note.copy(whiteboard = drawing)))) }
            }
        }
    }

    @Test
    fun editableUnicodeTextRoundTripsThroughBackupAndEncryptedSync() {
        val text = WhiteboardText("text", "角度 60°\n<标注> & 😀", 100f, 1200f, 400f, 40f, 0xFF1967D2L)
        val source = SyncDocument(notes = listOf(note.copy(whiteboard = board.copy(texts = listOf(text)))))
        assertEquals(source, SyncJson.decodeDocument(SyncJson.encodeDocument(source)))
        val key = SyncEncryptionKey.parse(SyncEncryptionKey.generate())
        assertEquals(source, EncryptedSyncJson.decode(EncryptedSyncJson.encode(source, key), key))
        val oldBoard = SyncJson.encode(SyncDocument(notes = listOf(note.copy(whiteboard = board))))
        org.junit.Assert.assertFalse(oldBoard.contains("\"texts\""))
        assertEquals(board, SyncJson.decode(oldBoard).notes.single().whiteboard)
    }

    @Test
    fun textLimitsBoundsColorAndXmlCharactersAreValidatedOnImportAndExport() {
        val text = WhiteboardText("text", "标注", 100f, 200f, 300f, 32f)
        val invalid = listOf(text.copy(id = ""), text.copy(text = " "), text.copy(text = "x".repeat(Whiteboard.MAX_TEXT_LENGTH + 1)),
            text.copy(text = "x\u0000"), text.copy(text = "x\uFFFF"),
            text.copy(x = -1f), text.copy(y = -1f), text.copy(x = 701f), text.copy(y = 3990f),
            text.copy(width = 0f), text.copy(width = 1001f), text.copy(fontSize = 11.9f), text.copy(fontSize = 96.1f),
            text.copy(color = 0xFEFFFFFFL), text.copy(color = 0x100000000L))
        invalid.forEach { assertRejected(board.copy(texts = listOf(it))) }
        val validDocument = SyncDocument(notes = listOf(note.copy(whiteboard = board.copy(texts = listOf(text)))))
        listOf("\\ud800", "\\udfff").forEach { invalidSurrogate ->
            assertThrows(SyncProtocolException::class.java) {
                SyncJson.decode(SyncJson.encode(validDocument).replace("标注", invalidSurrogate))
            }
        }
        assertThrows(SyncProtocolException::class.java) {
            val invalidBoard = board.copy(texts = listOf(text.copy(text = "x\uD800")))
            SyncJson.encode(SyncDocument(notes = listOf(note.copy(whiteboard = invalidBoard))))
        }
        assertRejected(board.copy(texts = listOf(text, text)))
        assertRejected(board.copy(texts = List(Whiteboard.MAX_TEXTS + 1) { text.copy(id = "text-$it") }))
        listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY).forEach { value ->
            listOf(text.copy(x = value), text.copy(y = value), text.copy(width = value), text.copy(fontSize = value)).forEach {
                assertThrows(SyncProtocolException::class.java) {
                    val invalidBoard = board.copy(texts = listOf(it))
                    SyncJson.validate(SyncDocument(notes = listOf(note.copy(whiteboard = invalidBoard))))
                }
            }
        }
        val maximum = board.copy(texts = List(Whiteboard.MAX_TEXTS) { text.copy(id = "text-$it") })
        val source = SyncDocument(notes = listOf(note.copy(whiteboard = maximum)))
        assertEquals(source, SyncJson.decode(SyncJson.encode(source)))
    }

    @Test
    fun imageLayersAndInkRoundTripTogetherWhileOldBoardsOmitImageField() {
        val attachment = NoteAttachment.create("图片.png", "image/png", byteArrayOf(1, 2))
        val image = WhiteboardImage("image", attachment.id, 100f, 1100f, 800f, 600f)
        val source = SyncDocument(notes = listOf(note.copy(whiteboard = board.copy(images = listOf(image)), attachments = listOf(attachment))))
        assertEquals(source, SyncJson.decode(SyncJson.encode(source)))
        org.junit.Assert.assertFalse(SyncJson.encode(SyncDocument(notes = listOf(note.copy(whiteboard = board)))).contains("\"images\""))
        val key = SyncEncryptionKey.parse(SyncEncryptionKey.generate())
        assertEquals(source, EncryptedSyncJson.decode(EncryptedSyncJson.encode(source, key), key))
    }

    @Test
    fun imageLayersRejectMissingAttachmentsInvalidBoundsAndDuplicateIds() {
        val attachment = NoteAttachment.create("图片.png", "image/png", byteArrayOf(1, 2))
        val image = WhiteboardImage("image", attachment.id, 0f, 0f, 1000f, 4000f)
        val source = note.copy(whiteboard = board.copy(images = listOf(image)), attachments = listOf(attachment))
        val invalidImages = listOf(image.copy(attachmentId = "missing"), image.copy(id = ""), image.copy(x = -1f),
            image.copy(y = -1f), image.copy(x = 1f), image.copy(y = 1f), image.copy(width = 0f), image.copy(height = -1f))
        val invalidNotes = invalidImages.map { source.copy(whiteboard = board.copy(images = listOf(it))) } +
            listOf(source.copy(attachments = emptyList()),
                source.copy(attachments = listOf(attachment.copy(mimeType = "text/plain"))),
                source.copy(whiteboard = board.copy(images = listOf(image, image))),
                source.copy(whiteboard = board.copy(images = List(Whiteboard.MAX_IMAGES + 1) { image.copy(id = "image-$it") })))
        invalidNotes.forEach { invalid ->
            val document = SyncDocument(notes = listOf(invalid))
            assertThrows(SyncProtocolException::class.java) { SyncJson.encode(document) }
            assertThrows(SyncProtocolException::class.java) { SyncJson.decode(SyncJson.json.encodeToString(document)) }
        }
        listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY).forEach { value ->
            listOf(image.copy(x = value), image.copy(y = value), image.copy(width = value), image.copy(height = value)).forEach {
                assertThrows(SyncProtocolException::class.java) {
                    val invalid = source.copy(whiteboard = board.copy(images = listOf(it)))
                    SyncJson.validate(SyncDocument(notes = listOf(invalid)))
                }
            }
        }
    }

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
            WhiteboardPoint(0f, 4000f), WhiteboardPoint(1000f, 4000f))
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
            WhiteboardPoint(1000.01f, 0f), WhiteboardPoint(0f, 4000.01f)).forEach { point ->
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
        assertThrows("Export $drawing", SyncProtocolException::class.java) { SyncJson.encode(document) }
        assertThrows("Import $drawing", SyncProtocolException::class.java) { SyncJson.decode(SyncJson.json.encodeToString(document)) }
    }
}
