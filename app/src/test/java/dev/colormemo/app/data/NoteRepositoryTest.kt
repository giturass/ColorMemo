package dev.colormemo.app.data

import androidx.room.InvalidationTracker
import dev.colormemo.app.ui.MemoSection
import dev.colormemo.app.ui.NoteDraft
import dev.colormemo.app.ui.inSection
import dev.colormemo.core.model.Note
import dev.colormemo.core.model.NoteAttachment
import dev.colormemo.core.model.Whiteboard
import dev.colormemo.core.model.WhiteboardText
import dev.colormemo.core.model.WhiteboardImage
import dev.colormemo.core.model.WhiteboardPoint
import dev.colormemo.core.model.WhiteboardStroke
import dev.colormemo.core.model.NoteColor
import dev.colormemo.core.mcp.AiCommand
import dev.colormemo.core.mcp.AiNoteException
import dev.colormemo.core.sync.SyncDocument
import dev.colormemo.core.sync.SyncJson
import dev.colormemo.core.sync.MergeEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class NoteRepositoryTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun draftRecoveryPreservesFullBaselineAndAttachmentsWithoutWritingANote() = runBlocking {
        val database = MemoryNoteDatabase()
        val attachments = AttachmentStore(temporary.newFolder())
        val repository = NoteRepository(database, "device", attachments)
        val attachment = NoteAttachment.create("test.txt", "text/plain", "未保存附件".toByteArray())
        val base = note("original").copy(content = "旧正文", attachments = listOf(attachment))
        val opened = EditorSession.open(base)
        val edited = opened.copy(draft = opened.draft.copy(title = "标题", content = "未保存正文\n".repeat(5000), reminderAt = 1L))
        repository.writeDraft(edited)

        val restarted = NoteRepository(database, "device", attachments)
        assertEquals(edited, restarted.loadDraft())
        assertTrue(restarted.snapshot().isEmpty())
        assertTrue(database.drafts().load()!!.document.size < 100_000)
    }

    @Test
    fun completedDraftIsRemovedAndSavingRecoveryRetainsConcurrentEdits() = runBlocking {
        val repository = repository()
        val base = note("shared").copy(content = "原文")
        repository.merge(listOf(base))
        val opened = EditorSession.open(base)
        val edited = opened.copy(draft = opened.draft.copy(content = "本机未保存正文"))
        repository.writeDraft(edited)
        repository.merge(listOf(base.copy(content = "另一设备正文").edited("remote")))
        val recovered = repository.loadDraft()!!
        repository.commitDraft(recovered.draft, recovered.id)

        assertNull(repository.loadDraft())
        assertEquals(setOf("本机未保存正文", "另一设备正文"), repository.snapshot().map { it.content }.toSet())
    }

    @Test
    fun failedSaveKeepsDraftAndDiscardLeavesOriginalUntouched() = runBlocking {
        val repository = repository()
        val base = note("original")
        repository.merge(listOf(base))
        val opened = EditorSession.open(base)
        val invalid = opened.copy(draft = opened.draft.copy(title = "x".repeat(501)))
        repository.writeDraft(invalid)
        assertThrows(NoteInputException::class.java) { runBlocking { repository.commitDraft(invalid.draft, invalid.id) } }
        assertEquals(invalid, repository.loadDraft())
        repository.discardDraft(invalid.id)
        assertNull(repository.loadDraft())
        assertEquals(listOf(base), repository.snapshot())
    }

    @Test
    fun unchangedSaveClearsOnlyItsOwnDraftWithoutCreatingARevision() = runBlocking {
        val repository = repository()
        val base = note("original")
        repository.merge(listOf(base))
        val opened = EditorSession.open(base)
        repository.writeDraft(opened)
        repository.commitDraft(opened.draft, "another-session")
        assertEquals(opened, repository.loadDraft())
        repository.commitDraft(opened.draft, opened.id)
        assertNull(repository.loadDraft())
        assertEquals(listOf(base), repository.snapshot())
    }

    @Test
    fun whiteboardLayersAndAnImageAwaitingPlacementSurviveRestart() = runBlocking {
        val repository = repository()
        val attachment = NoteAttachment.create("image.png", "image/png", byteArrayOf(1, 2, 3))
        val image = WhiteboardImage("image", attachment.id, 1f, 2f, 100f, 100f)
        val board = Whiteboard(
            strokes = listOf(WhiteboardStroke(points = listOf(WhiteboardPoint(5f, 6f)))),
            texts = listOf(WhiteboardText("text", "未保存白板文字", 10f, 10f, 300f)),
        )
        val opened = EditorSession.open(null, whiteboard = true)
        val session = opened.copy(draft = opened.draft.copy(whiteboard = board, attachments = listOf(attachment)),
            pendingImage = image, inlineImageIds = listOf(attachment.id))
        repository.writeDraft(session)
        assertEquals(session, repository.loadDraft())
        assertTrue(repository.snapshot().isEmpty())
    }

    @Test
    fun textOnlyWhiteboardCanBeCommittedFromRecovery() = runBlocking {
        val repository = repository()
        val opened = EditorSession.open(null, whiteboard = true)
        val session = opened.copy(draft = opened.draft.copy(whiteboard = Whiteboard(
            texts = listOf(WhiteboardText("text", "只有文字的白板", 10f, 10f, 300f)),
        )))
        repository.writeDraft(session)
        repository.commitDraft(repository.loadDraft()!!.draft, session.id)
        assertNull(repository.loadDraft())
        assertEquals(session.draft.whiteboard, repository.snapshot().single().whiteboard)
    }

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

    @Test
    fun hiddenNotesAreAbsentFromEverySearchAndAllDirectOperations() = runBlocking {
        val repository = repository()
        val hidden = note("private-id").copy(title = "private-title", content = "private-body")
        repository.merge(listOf(hidden, note("visible"), note("archived").copy(isArchived = true),
            note("trash").copy(deletedAt = 10), note("board").copy(whiteboard = Whiteboard())))
        repository.setAiVisible("visible", true)
        for (status in listOf("active", "archived", "trash", "all")) {
            val result = repository.execute(AiCommand.ListNotes("", status, 0, 50)).toString()
            assertFalse(result.contains("private"))
            assertFalse(result.contains("\"id\":\"archived\""))
            assertFalse(result.contains("\"board\""))
            assertEquals(0, repository.execute(AiCommand.ListNotes("private", status, 0, 50)).jsonObject["notes"]!!.jsonArray.size)
        }
        for (id in listOf(hidden.id, "missing-id", "archived", "trash", "board")) {
            val commands = listOf(AiCommand.Read(id), AiCommand.Update(id, hidden.revision, "stolen", null, null)) +
                listOf("pin", "unpin", "archive", "unarchive", "trash", "restore").map { AiCommand.Manage(id, hidden.revision, it) }
            commands.forEach { command ->
                val error = assertThrows(AiNoteException::class.java) { runBlocking { repository.execute(command) } }
                assertEquals(AiNoteException.Reason.UNAVAILABLE, error.reason)
                assertEquals("Note unavailable.", error.message)
            }
        }
        assertEquals(hidden, repository.snapshot().single { it.id == hidden.id })
    }

    @Test
    fun revocationIsImmediateAndStaleDraftsImportsAndConflictCopiesCannotGrantAccess() = runBlocking {
        val repository = repository()
        val original = note("shared")
        repository.merge(listOf(original))
        repository.setAiVisible(original.id, true)
        assertEquals(original.revision, repository.snapshot().single().revision)
        repository.execute(AiCommand.Read(original.id))
        repository.setAiVisible(original.id, false)
        assertThrows(AiNoteException::class.java) { runBlocking { repository.execute(AiCommand.Update(original.id, original.revision, "AI edit", null, null)) } }
        repository.save(NoteDraft(baseNote = original, title = "user draft", newAiVisible = true))
        repository.merge(listOf(original.copy(title = "remote edit").edited("remote")))
        assertTrue(repository.snapshot().size > 1)
        assertTrue(repository.aiVisibleIds.first().isEmpty())
        assertEquals(0, repository.execute(AiCommand.ListNotes("", "all", 0, 50)).jsonObject["notes"]!!.jsonArray.size)
        repository.setAiVisible(original.id, true)
        val backup = SyncJson.encode(SyncDocument(notes = repository.snapshot()))
        assertFalse(backup.contains("aiVisible"))
        assertFalse(backup.contains("ai_note_grants"))
        val otherDevice = repository()
        otherDevice.merge(SyncJson.decode(backup).notes)
        assertTrue(otherDevice.aiVisibleIds.first().isEmpty())
    }

    @Test
    fun aiCreatesVisibleNotesAndRequiresTheCurrentRevisionForWrites() = runBlocking {
        val repository = repository()
        val manual = repository.save(NoteDraft(title = "manual"))
        assertFalse(manual.id in repository.aiVisibleIds.first())
        val created = repository.execute(AiCommand.Create("AI title", "body", NoteColor.MINT)).jsonObject
        val id = created["id"]!!.jsonPrimitive.content
        val firstRevision = created["revision"]!!.jsonPrimitive.content
        assertTrue(id in repository.aiVisibleIds.first())
        val updated = repository.execute(AiCommand.Update(id, firstRevision, null, "edited", null)).jsonObject
        assertEquals("edited", updated["content"]!!.jsonPrimitive.content)
        val failure = assertThrows(AiNoteException::class.java) {
            runBlocking { repository.execute(AiCommand.Manage(id, firstRevision, "trash")) }
        }
        assertEquals(AiNoteException.Reason.CONFLICT, failure.reason)
        val current = repository.snapshot().single { it.id == id }
        repository.execute(AiCommand.Manage(id, current.revision, "trash"))
        val trashed = repository.snapshot().single { it.id == id }
        assertEquals(current.updatedAt, trashed.updatedAt)
        assertThrows(AiNoteException::class.java) {
            runBlocking { repository.execute(AiCommand.Update(id, trashed.revision, "blocked", null, null)) }
        }
        repository.execute(AiCommand.Manage(id, trashed.revision, "restore"))
        assertEquals(current.updatedAt, repository.snapshot().single { it.id == id }.updatedAt)
        repository.trash(id)
        repository.purge(id)
        assertFalse(id in repository.aiVisibleIds.first())
    }

    @Test
    fun visibleBoardMetadataCanBeUpdatedWithoutLosingAttachmentsOrExposingTheirBytes() = runBlocking {
        val repository = repository()
        val attachment = NoteAttachment.create("safe.txt", "text/plain", "private bytes".toByteArray())
        val board = note("board").copy(whiteboard = Whiteboard(), attachments = listOf(attachment))
        repository.merge(listOf(board))
        repository.setAiVisible(board.id, true)
        val read = repository.execute(AiCommand.Read(board.id)).toString()
        assertFalse(read.contains(attachment.dataBase64))
        assertFalse(read.contains("deviceId"))
        assertFalse(read.contains("ancestorRevisions"))
        repository.execute(AiCommand.Update(board.id, board.revision, "new title", null, NoteColor.SAND))
        val updated = repository.snapshot().single()
        assertEquals(board.attachments, updated.attachments)
        assertEquals(board.whiteboard, updated.whiteboard)
        assertEquals("new title", updated.title)
    }

    @Test
    fun paginationCountsOnlyVisibleNotesAndExplicitNewDraftGrantIsAppliedOnCreation() = runBlocking {
        val repository = repository()
        repository.merge((1..6).map { note("note-$it") })
        listOf(2, 4, 6).forEach { repository.setAiVisible("note-$it", true) }
        val first = repository.execute(AiCommand.ListNotes("", "active", 0, 2)).jsonObject
        assertEquals(listOf("note-2", "note-4"), first["notes"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content })
        assertEquals(2, first["nextOffset"]!!.jsonPrimitive.int)
        val last = repository.execute(AiCommand.ListNotes("", "active", 2, 2)).jsonObject
        assertEquals(1, last["notes"]!!.jsonArray.size)
        assertFalse("nextOffset" in last)
        val manual = repository.save(NoteDraft(title = "explicit grant", newAiVisible = true))
        assertTrue(manual.id in repository.aiVisibleIds.first())
    }

    @Test
    fun revocationAndAiWritesCannotInterleaveBetweenAuthorizationAndCommit() = runBlocking<Unit> {
        val database = MemoryNoteDatabase()
        val repository = repository(database)
        val original = note("shared")
        repository.merge(listOf(original))
        repository.setAiVisible(original.id, true)
        val checked = CountDownLatch(1)
        val release = CountDownLatch(1)
        val revokeStarted = CountDownLatch(1)
        val revokeFinished = CountDownLatch(1)
        database.onGrantChecked = {
            checked.countDown()
            check(release.await(5, TimeUnit.SECONDS))
        }
        val write = async(Dispatchers.IO) {
            repository.execute(AiCommand.Update(original.id, original.revision, "authorized edit", null, null))
        }
        try {
            assertTrue(checked.await(5, TimeUnit.SECONDS))
            val revoke = async(Dispatchers.IO) {
                revokeStarted.countDown()
                repository.setAiVisible(original.id, false)
                revokeFinished.countDown()
            }
            assertTrue(revokeStarted.await(5, TimeUnit.SECONDS))
            // A check outside the mutation lock would allow revocation to finish in this gap.
            assertFalse(revokeFinished.await(200, TimeUnit.MILLISECONDS))
            release.countDown()
            write.await()
            revoke.await()
            database.onGrantChecked = {}
            assertTrue(repository.aiVisibleIds.first().isEmpty())
            assertThrows(AiNoteException::class.java) { runBlocking { repository.execute(AiCommand.Read(original.id)) } }
        } finally { release.countDown() }
    }

    private fun repository(database: MemoryNoteDatabase = MemoryNoteDatabase()) = NoteRepository(
        database, "device", AttachmentStore(temporary.newFolder()),
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
        private var draft: EditorDraftEntity? = null
        private val draftDao = object : EditorDraftDao {
            override fun load() = draft
            override fun upsert(draft: EditorDraftEntity) { this@MemoryNoteDatabase.draft = draft }
            override fun delete(id: String) { if (draft?.id == id) draft = null }
        }
        override fun drafts() = draftDao
        var onGrantChecked: () -> Unit = {}
        private val rows = MutableStateFlow<List<NoteEntity>>(emptyList())
        private val grants = MutableStateFlow<List<String>>(emptyList())
        private val grantDao = object : AiNoteGrantDao {
            override fun observeIds() = grants
            override fun isGranted(id: String): Boolean {
                val granted = id in grants.value
                onGrantChecked()
                return granted
            }
            override fun grantedNotes() = rows.value.filter { it.id in grants.value }
            override fun grant(value: AiNoteGrant) { grants.value = (grants.value + value.noteId).distinct() }
            override fun revoke(id: String) { grants.value = grants.value - id }
        }
        private val dao = object : NoteDao {
            override fun observeAll() = rows
            override fun getAll() = rows.value
            override fun find(id: String) = rows.value.firstOrNull { it.id == id }
            override fun upsert(notes: List<NoteEntity>) {
                rows.value = (rows.value.associateBy(NoteEntity::id) + notes.associateBy(NoteEntity::id)).values.toList()
            }
        }

        override fun notes() = dao
        override fun aiGrants() = grantDao
        override fun runInTransaction(body: Runnable) = body.run()
        override fun <V> runInTransaction(body: Callable<V>): V = body.call()
        override fun clearAllTables() { rows.value = emptyList(); grants.value = emptyList() }
        override fun createInvalidationTracker(): InvalidationTracker = error("SQLite is not used in this test")
    }
}
