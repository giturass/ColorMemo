package dev.colormemo.core.sync

import dev.colormemo.core.model.Note
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SyncJsonTest {
    private val note = Note(id = "note", revision = "revision", deviceId = "device", createdAt = 1, updatedAt = 1)

    @Test
    fun markdownUnicodeAndMetadataRoundTrip() {
        val source = SyncDocument(notes = listOf(note.copy(content = "# 彩笺\n\n- [x] Markdown **粗体** 🍊\n")))
        assertEquals(source, SyncJson.decodeDocument(SyncJson.encodeDocument(source)))
    }

    @Test
    fun missingSchemaAndFutureSchemaFailClosed() {
        listOf("{}", "[]", "{\"schemaVersion\":2,\"notes\":[]}", "{\"schemaVersion\":1}").forEach {
            assertThrows(SyncProtocolException::class.java) { SyncJson.decode(it) }
        }
    }

    @Test
    fun unknownFieldsFailInsteadOfBeingDroppedOnTheNextWrite() {
        assertThrows(SyncProtocolException::class.java) {
            SyncJson.decode("{\"schemaVersion\":1,\"notes\":[],\"futureData\":{}}")
        }
    }

    @Test
    fun missingNoteIdentityDoesNotGenerateRandomVersionsDuringDownload() {
        assertThrows(SyncProtocolException::class.java) {
            SyncJson.decode("{\"schemaVersion\":1,\"notes\":[{\"title\":\"incomplete\"}]}")
        }
    }

    @Test
    fun duplicateNoteIdsAndInvalidRevisionHistoryAreRejected() {
        assertThrows(SyncProtocolException::class.java) { SyncJson.validate(SyncDocument(notes = listOf(note, note))) }
        assertThrows(SyncProtocolException::class.java) {
            SyncJson.validate(SyncDocument(notes = listOf(note.copy(ancestorRevisions = setOf(note.revision)))))
        }
    }

    @Test
    fun oversizedPayloadAndCorruptUtf8AreRejectedBeforeImport() {
        assertThrows(SyncProtocolException::class.java) { SyncJson.decode(ByteArray(SyncJson.MAX_DOCUMENT_BYTES + 1)) }
        assertThrows(SyncProtocolException::class.java) { SyncJson.decode(byteArrayOf(0xc3.toByte(), 0x28)) }
    }

    @Test
    fun purgedRecordCannotSmuggleRetainedContent() {
        assertThrows(SyncProtocolException::class.java) {
            SyncJson.validate(SyncDocument(notes = listOf(note.copy(isPurged = true, deletedAt = 2, content = "old"))))
        }
    }

    @Test
    fun importedNotesMustStayWithinTheEditorsLimits() {
        assertThrows(SyncProtocolException::class.java) {
            SyncJson.validate(SyncDocument(notes = listOf(note.copy(title = "a".repeat(501)))))
        }
        assertThrows(SyncProtocolException::class.java) {
            SyncJson.validate(SyncDocument(notes = listOf(note.copy(content = "a".repeat(100_001)))))
        }
    }
}
