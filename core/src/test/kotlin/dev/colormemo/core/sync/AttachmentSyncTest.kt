package dev.colormemo.core.sync

import dev.colormemo.core.model.Note
import dev.colormemo.core.model.NoteAttachment
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

class AttachmentSyncTest {
    private val file = NoteAttachment.create("测试 [附件].bin", "application/octet-stream", ByteArray(256) { it.toByte() })
    private val base = Note(id = "note", revision = "base", deviceId = "one", createdAt = 1, updatedAt = 1)

    @Test fun completeBinaryAndMetadataSurviveEncryptedSyncAndJsonBackup() {
        val source = SyncDocument(notes = listOf(base.copy(attachments = listOf(file))))
        assertEquals(source, SyncJson.decodeDocument(SyncJson.encodeDocument(source)))
        val key = SyncEncryptionKey.parse(SyncEncryptionKey.generate())
        val encrypted = EncryptedSyncJson.encode(source, key)
        assertFalse(encrypted.toString(Charsets.UTF_8).contains(file.fileName))
        assertFalse(encrypted.toString(Charsets.UTF_8).contains(file.dataBase64))
        val restored = EncryptedSyncJson.decode(encrypted, key)
        assertEquals(source, restored)
        assertArrayEquals(ByteArray(256) { it.toByte() }, restored.notes.single().attachments.single().bytes())
    }

    @Test fun attachmentOnlyConcurrentEditsKeepBothFilesAndConverge() {
        val other = NoteAttachment.create("另一附件.txt", "text/plain", "另一份文件".toByteArray())
        val local = base.copy(attachments = listOf(file)).edited("one", 2, "local")
        val remote = base.copy(attachments = listOf(other)).edited("two", 3, "remote")
        val result = MergeEngine.merge(listOf(local), listOf(remote))
        assertEquals(1, result.conflictCount)
        assertEquals(setOf(file, other), result.notes.flatMap { it.attachments }.toSet())
        assertEquals(result.notes, MergeEngine.merge(MergeEngine.merge(result.notes, listOf(local)).notes, listOf(remote)).notes)
        assertEquals(result.notes, MergeEngine.merge(listOf(remote), listOf(local)).notes)
    }

    @Test fun trashAndRestoreKeepFilesAndPurgeRemovesTheirData() {
        val original = base.copy(attachments = listOf(file))
        val trashed = original.copy(deletedAt = 2).edited("one", 2, "trash")
        val restored = trashed.copy(deletedAt = null).edited("one", 3, "restore")
        val purged = trashed.copy(isPurged = true).edited("one", 4, "purge")
        assertEquals(original.attachments, trashed.attachments)
        assertEquals(original.attachments, restored.attachments)
        assertTrue(purged.attachments.isEmpty())
        assertEquals(listOf(purged), MergeEngine.merge(listOf(original), listOf(purged)).notes)
        assertRejected(base.copy(isPurged = true, deletedAt = 2, attachments = listOf(file)))
    }

    @Test fun invalidBytesSizesHashesAndBase64AreRejectedBeforeMerge() {
        val oneByte = NoteAttachment.create("a", "text/plain", byteArrayOf(0))
        listOf(
            file.copy(dataBase64 = file.dataBase64.dropLast(4)),
            file.copy(sizeBytes = file.sizeBytes + 1),
            file.copy(sizeBytes = -1),
            file.copy(sha256 = "0".repeat(64)),
            file.copy(dataBase64 = "!".repeat(file.dataBase64.length)),
            oneByte.copy(dataBase64 = "AB=="), // Decodes to the same byte but has noncanonical pad bits.
            file.copy(sizeBytes = NoteAttachment.MAX_FILE_BYTES + 1),
        ).forEach { assertRejected(base.copy(attachments = listOf(it))) }
    }

    @Test fun unsafeMetadataAndDuplicateAttachmentIdsAreRejected() {
        listOf(
            file.copy(id = "../../private"), file.copy(fileName = "../secret"),
            file.copy(fileName = "a\\b"), file.copy(fileName = "a\nb"),
            file.copy(fileName = ""), file.copy(mimeType = "text/plain\ntext/html"),
            file.copy(sha256 = "../private"),
        ).forEach { assertRejected(base.copy(attachments = listOf(it))) }
        assertRejected(base.copy(attachments = listOf(file, file)))
    }

    @Test fun maximumFileAndNoteLimitsAreEnforced() {
        val maximum = NoteAttachment.create("large.bin", "application/octet-stream", ByteArray(NoteAttachment.MAX_FILE_BYTES))
        val document = SyncDocument(notes = listOf(base.copy(attachments = listOf(maximum))))
        assertEquals(document, SyncJson.decode(SyncJson.encode(document)))
        assertThrows(IllegalArgumentException::class.java) {
            NoteAttachment.create("large.bin", "application/octet-stream", ByteArray(NoteAttachment.MAX_FILE_BYTES + 1))
        }
        val another = maximum.copy(id = "00000000-0000-0000-0000-000000000001")
        assertRejected(base.copy(attachments = listOf(maximum, another)))
        assertRejected(base.copy(attachments = List(21) { file.copy(id = "00000000-0000-0000-0000-${it.toString().padStart(12, '0')}") }))
    }

    @Test fun emptyFilesRoundTripAndLegacyNotesDoNotAcquireNewFields() {
        val empty = NoteAttachment.create("空文件", "text/plain", byteArrayOf())
        val source = SyncDocument(notes = listOf(base.copy(attachments = listOf(empty))))
        assertEquals(source, SyncJson.decode(SyncJson.encode(source)))
        assertFalse(SyncJson.encode(SyncDocument(notes = listOf(base))).contains("attachments"))
    }

    private fun assertRejected(note: Note) {
        val document = SyncDocument(notes = listOf(note))
        assertThrows(SyncProtocolException::class.java) { SyncJson.encode(document) }
        assertThrows(SyncProtocolException::class.java) { SyncJson.decode(SyncJson.json.encodeToString(document)) }
        assertThrows(SyncProtocolException::class.java) { MergeEngine.merge(listOf(base), listOf(note)) }
    }
}
