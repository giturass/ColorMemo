package dev.colormemo.app.data

import dev.colormemo.app.ui.NoteDraft
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DraftControllerTest {
    @Test
    fun continuousTypingWritesImmediatelyAndCoalescesToLatestSnapshot() = runTest {
        val disk = Disk()
        val controller = DraftController(disk, backgroundScope)
        runCurrent()
        controller.open(null)
        val session = controller.state.value.session!!
        controller.update(session.copy(draft = session.draft.copy(content = "一")))
        runCurrent()
        assertEquals("一", disk.saved!!.draft.content)
        repeat(100) { controller.update(session.copy(draft = session.draft.copy(content = "文本$it"))) }
        runCurrent()
        assertEquals("文本99", disk.saved!!.draft.content)
        assertEquals(2, disk.writes)
        assertFalse(controller.state.value.saving)

        // No activity lifecycle callback or saved-state Bundle participates in recovery.
        val restarted = DraftController(disk, backgroundScope)
        runCurrent()
        assertEquals(disk.saved, restarted.state.value.session)
        assertTrue(restarted.state.value.recovered)
    }

    @Test
    fun aNewEditDuringDiskWriteIsNotMarkedSavedOrDropped() = runTest {
        val disk = Disk()
        val controller = DraftController(disk, backgroundScope)
        runCurrent()
        controller.open(null)
        val session = controller.state.value.session!!
        disk.writeGate = CompletableDeferred()
        controller.update(session.copy(draft = session.draft.copy(content = "第一版")))
        runCurrent()
        controller.update(session.copy(draft = session.draft.copy(content = "最后一版")))
        disk.writeGate!!.complete(Unit)
        runCurrent()
        assertEquals("最后一版", disk.saved!!.draft.content)
        assertFalse(controller.state.value.saving)
    }

    @Test
    fun failedWritePreservesLastDurableSnapshotAndCanBeRetried() = runTest {
        val disk = Disk()
        val controller = DraftController(disk, backgroundScope)
        runCurrent()
        controller.open(null)
        val session = controller.state.value.session!!
        controller.update(session.copy(draft = session.draft.copy(content = "已落盘")))
        runCurrent()
        disk.fail = true
        controller.update(session.copy(draft = session.draft.copy(content = "新内容")))
        runCurrent()
        assertEquals("已落盘", disk.saved!!.draft.content)
        assertNotNull(controller.state.value.error)
        disk.fail = false
        controller.flush()
        runCurrent()
        assertEquals("新内容", disk.saved!!.draft.content)
        assertNull(controller.state.value.error)
    }

    @Test
    fun saveSurvivesCallerCancellationAndQueuedCheckpointsCannotResurrectIt() = runTest {
        val disk = Disk()
        val controller = DraftController(disk, backgroundScope)
        runCurrent()
        controller.open(null)
        val session = controller.state.value.session!!
        val edited = session.copy(draft = session.draft.copy(content = "待保存"))
        controller.update(edited)
        runCurrent()
        disk.commitGate = CompletableDeferred()
        val caller = async { controller.save(edited.draft) }
        runCurrent()
        caller.cancel()
        controller.update(edited.copy(draft = edited.draft.copy(content = "过时回调")))
        controller.flush()
        disk.commitGate!!.complete(Unit)
        runCurrent()
        assertEquals(edited.draft, disk.committed)
        assertNull(disk.saved)
        assertNull(controller.state.value.session)
    }

    @Test
    fun saveProtectsLatestDraftBeforeWaitingForTheNoteTransaction() = runTest {
        val disk = Disk()
        val controller = DraftController(disk, backgroundScope)
        runCurrent()
        controller.open(null)
        val session = controller.state.value.session!!
        val edited = session.copy(draft = session.draft.copy(content = "最后的输入"))
        disk.fail = true
        controller.update(edited)
        runCurrent()
        assertNull(disk.saved)
        disk.fail = false
        disk.commitGate = CompletableDeferred()
        val saving = async { controller.save(edited.draft) }
        runCurrent()
        // A process death while waiting for sync/import to release the note writer
        // can now recover this durable snapshot, without waiting for the final commit.
        assertFalse(saving.isCompleted)
        assertEquals(edited, disk.saved)
        disk.commitGate!!.complete(Unit)
        assertTrue(saving.await())
        assertNull(disk.saved)
    }

    @Test
    fun discardWaitsForInflightWriteAndFailureKeepsEditorOpen() = runTest {
        val disk = Disk()
        val controller = DraftController(disk, backgroundScope)
        runCurrent()
        controller.open(null)
        val session = controller.state.value.session!!
        disk.writeGate = CompletableDeferred()
        controller.update(session.copy(draft = session.draft.copy(content = "未完成")))
        runCurrent()
        val discard = async { controller.discard() }
        runCurrent()
        assertFalse(discard.isCompleted)
        disk.fail = true
        disk.writeGate!!.complete(Unit)
        runCurrent()
        assertFalse(discard.await())
        assertNotNull(controller.state.value.session)
        disk.fail = false
        assertTrue(controller.discard())
        controller.update(session)
        controller.flush()
        runCurrent()
        assertNull(disk.saved)
        assertNull(controller.state.value.session)
    }

    @Test
    fun unreadableRecoveryCannotBeOverwrittenByOpeningAnotherEditor() = runTest {
        val disk = Disk().apply { fail = true }
        val controller = DraftController(disk, backgroundScope)
        runCurrent()
        controller.open(null)
        assertFalse(controller.state.value.loaded)
        assertNull(controller.state.value.session)
        assertNotNull(controller.state.value.error)
        assertEquals(0, disk.writes)
    }

    private class Disk : DraftPersistence {
        var saved: EditorSession? = null
        var committed: NoteDraft? = null
        var writes = 0
        var fail = false
        var writeGate: CompletableDeferred<Unit>? = null
        var commitGate: CompletableDeferred<Unit>? = null
        override suspend fun loadDraft(): EditorSession? { check(!fail); return saved }
        override suspend fun writeDraft(session: EditorSession) {
            writeGate?.await()
            check(!fail)
            saved = session
            writes++
        }
        override suspend fun discardDraft(id: String) { check(!fail); if (saved?.id == id) saved = null }
        override suspend fun commitDraft(draft: NoteDraft, sessionId: String) {
            commitGate?.await()
            check(!fail)
            committed = draft
            discardDraft(sessionId)
        }
    }
}
