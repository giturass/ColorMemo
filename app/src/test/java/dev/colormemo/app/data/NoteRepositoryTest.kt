package dev.colormemo.app.data

import androidx.room.InvalidationTracker
import dev.colormemo.app.ui.MemoSection
import dev.colormemo.app.ui.NoteDraft
import dev.colormemo.app.ui.inSection
import dev.colormemo.core.model.Note
import dev.colormemo.core.model.NoteAttachment
import dev.colormemo.core.model.Whiteboard
import dev.colormemo.core.sync.MergeEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.Callable

class NoteRepositoryTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun restoringTextBoardsAndArchivedNotesKeepsTheirOriginalSectionAndOrder() = runBlocking {
        val attachment = NoteAttachment.create("test.txt", "text/plain", "附件".toByteArray())
        val originals = listOf(
            note("newer", updatedAt = 300),
            note("older", updatedAt = 100),
            note("text", updatedAt = 200),
            note("pinned", updatedAt = 150).copy(isPinned = true),
            note("board", updatedAt = 200).copy(whiteboard = Whiteboard()),
            note("archived-text", updatedAt = 200).copy(isArchived = true),
            note("archived-board", updatedAt = 200).copy(isArchived = true, isPinned = true, whiteboard = Whiteboard()),
        ).map { it.copy(attachments = listOf(attachment)) }
        val repository = repository()
        repository.merge(originals)

        originals.forEach { repository.trash(it.id) }
        val deleted = repository.snapshot().associateBy(Note::id)
        originals.reversed().forEach { repository.restore(it.id) }
        val restored = repository.snapshot()

        originals.forEach { original ->
            val trashed = deleted.getValue(original.id)
            val actual = restored.single { it.id == original.id }
            assertNotNull(trashed.deletedAt)
            assertEquals(original.updatedAt, trashed.updatedAt)
            assertNull(actual.deletedAt)
            assertEquals(original.updatedAt, actual.updatedAt)
            assertEquals(original.isArchived, actual.isArchived)
            assertEquals(original.isPinned, actual.isPinned)
            assertEquals(original.whiteboard, actual.whiteboard)
            assertEquals(original.attachments, actual.attachments)
            assertEquals(trashed.revision, actual.parentRevision)
            assertTrue(original.revision in actual.ancestorRevisions)
            assertTrue(trashed.revision in actual.ancestorRevisions)
        }
        listOf(MemoSection.NOTES, MemoSection.WHITEBOARDS, MemoSection.ARCHIVE).forEach { section ->
            assertEquals(originals.idsInOrder(section), restored.idsInOrder(section))
        }
        assertEquals(restored.sortedBy(Note::id), MergeEngine.merge(deleted.values.toList(), restored).notes)
    }

    @Test
    fun repeatedTrashAndRestoreDoNotCreateExtraRevisions() = runBlocking {
        val repository = repository()
        repository.merge(listOf(note("note")))
        repository.trash("note")
        val trashed = repository.snapshot().single()
        repository.trash("note")
        assertEquals(trashed, repository.snapshot().single())

        repository.restore("note")
        val restored = repository.snapshot().single()
        repository.restore("note")
        assertEquals(restored, repository.snapshot().single())
    }

    @Test
    fun savingContentAfterRestoreStillAdvancesTheModificationTime() = runBlocking {
        val repository = repository()
        repository.merge(listOf(note("note")))
        repository.trash("note")
        repository.restore("note")
        val restored = repository.snapshot().single()
        val saved = repository.save(NoteDraft(baseNote = restored, title = "修改后的内容"))

        assertTrue(saved.updatedAt > restored.updatedAt)
        assertNotEquals(restored.revision, saved.revision)
        assertEquals(restored.revision, saved.parentRevision)
    }

    @Test
    fun permanentlyDeletedNotesCannotBeRestored() {
        val repository = repository()
        runBlocking {
            repository.merge(listOf(note("note")))
            repository.trash("note")
            repository.purge("note")
        }
        val purged = runBlocking { repository.snapshot().single() }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repository.restore("note") }
        }
        assertEquals(purged, runBlocking { repository.snapshot().single() })
    }

    private fun repository() = NoteRepository(
        MemoryNoteDatabase(), "device", AttachmentStore(temporary.newFolder()),
    )

    private fun note(id: String, updatedAt: Long = 100) = Note(
        id = id, title = id, createdAt = 50, updatedAt = updatedAt,
        revision = "original-$id", deviceId = "device",
    )

    private fun List<Note>.idsInOrder(section: MemoSection) = filter { it.inSection(section) }
        .sortedWith(compareByDescending<Note> { it.isPinned }.thenByDescending { it.updatedAt })
        .map(Note::id)

    /** Exercise repository serialization and mutations without Android's SQLite runtime. */
    private class MemoryNoteDatabase : NoteDatabase() {
        private val rows = MutableStateFlow<List<NoteEntity>>(emptyList())
        private val dao = object : NoteDao {
            override fun observeAll() = rows
            override fun getAll() = rows.value
            override fun find(id: String) = rows.value.firstOrNull { it.id == id }
            override fun upsert(notes: List<NoteEntity>) {
                rows.value = (rows.value.associateBy(NoteEntity::id) + notes.associateBy(NoteEntity::id)).values.toList()
            }
        }

        override fun notes() = dao
        override fun runInTransaction(body: Runnable) = body.run()
        override fun <V> runInTransaction(body: Callable<V>): V = body.call()
        override fun clearAllTables() { rows.value = emptyList() }
        override fun createInvalidationTracker(): InvalidationTracker = error("SQLite is not used in this test")
    }
}
