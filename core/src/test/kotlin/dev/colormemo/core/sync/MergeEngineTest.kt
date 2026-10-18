package dev.colormemo.core.sync

import dev.colormemo.core.model.Note
import dev.colormemo.core.model.NoteColor
import dev.colormemo.core.model.Whiteboard
import dev.colormemo.core.model.WhiteboardPoint
import dev.colormemo.core.model.WhiteboardStroke
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MergeEngineTest {
    private val base = Note(
        id = "note", title = "旅行", content = "原始版本", createdAt = 100, updatedAt = 100,
        revision = "base", deviceId = "device-a",
    )

    @Test
    fun multipleOfflineEditsSupersedeOlderAncestorEvenWithClockSkew() {
        val first = base.copy(content = "第一版").edited("a", 101, "one")
        val last = first.copy(content = "第二版").edited("a", 102, "two")
        val olderWithFastClock = base.copy(updatedAt = 900_000)
        val result = MergeEngine.merge(listOf(olderWithFastClock), listOf(last))
        assertEquals(1, result.notes.size)
        assertEquals("第二版", result.notes.single().content)
        assertEquals(setOf("base", "one"), result.notes.single().ancestorRevisions)
        assertEquals(0, result.conflictCount)
    }

    @Test
    fun divergentEditsArePreservedAndConvergeWithoutDuplicateCopies() {
        val left = base.copy(content = "手机离线修改").edited("a", 200, "left")
        val right = base.copy(content = "平板离线修改").edited("b", 300, "right")
        val forward = MergeEngine.merge(listOf(left), listOf(right))
        val backward = MergeEngine.merge(listOf(right), listOf(left))
        assertEquals(forward, backward)
        assertEquals(setOf("手机离线修改", "平板离线修改"), forward.notes.map { it.content }.toSet())
        assertEquals(1, forward.conflictCount)
        val copy = forward.notes.single { it.id != base.id }
        assertEquals(base.id, copy.conflictOf)
        assertEquals("left", copy.conflictRevision)
        assertNull(copy.reminderAt)
        val repeated = MergeEngine.merge(forward.notes, listOf(left))
        assertEquals(forward.notes, repeated.notes)
        assertEquals(0, repeated.conflictCount)
    }

    @Test
    fun threeDevicesRetainEveryDistinctConcurrentEdit() {
        val a = base.copy(content = "A").edited("a", 101, "a1")
        val b = base.copy(content = "B").edited("b", 102, "b1")
        val c = base.copy(content = "C").edited("c", 103, "c1")
        val ab = MergeEngine.merge(listOf(a), listOf(b)).notes
        val bc = MergeEngine.merge(listOf(b), listOf(c)).notes
        val combined = MergeEngine.merge(ab, bc).notes
        assertEquals(setOf("A", "B", "C"), combined.map { it.content }.toSet())
        assertEquals(combined, MergeEngine.merge(combined, ab).notes)
        assertEquals(combined, MergeEngine.merge(bc, ab).notes)
    }

    @Test
    fun deletedConflictCopyDoesNotReappearAfterOldDeviceSyncs() {
        val left = base.copy(content = "左边").edited("a", 200, "left")
        val right = base.copy(content = "右边").edited("b", 300, "right")
        val merged = MergeEngine.merge(listOf(left), listOf(right)).notes
        val copy = merged.single { it.conflictOf != null }
        val deleted = copy.copy(deletedAt = 400).edited("a", 400, "deleted-copy")
        val local = merged.map { if (it.id == copy.id) deleted else it }
        val result = MergeEngine.merge(local, listOf(left))
        assertEquals(2, result.notes.size)
        assertNotNull(result.notes.single { it.id == copy.id }.deletedAt)
        assertEquals(0, result.conflictCount)
    }

    @Test
    fun deletionTombstoneStopsOldOfflineCopyFromResurrecting() {
        val deleted = base.copy(deletedAt = 200, reminderAt = 999).edited("a", 200, "deleted")
        assertNull(deleted.reminderAt)
        val result = MergeEngine.merge(listOf(deleted), listOf(base))
        assertEquals(listOf(deleted), result.notes)
        assertEquals(0, result.conflictCount)
    }

    @Test
    fun simultaneousDeleteAndEditKeepsTombstoneAndRecoverableEdit() {
        val deleted = base.copy(deletedAt = 200).edited("a", 200, "deleted")
        val edited = base.copy(content = "未同步的重要修改", reminderAt = 1000).edited("b", 300, "edit")
        val result = MergeEngine.merge(listOf(deleted), listOf(edited))
        assertNotNull(result.notes.single { it.id == base.id }.deletedAt)
        val savedEdit = result.notes.single { it.conflictOf != null }
        assertEquals("未同步的重要修改", savedEdit.content)
        assertNull(savedEdit.deletedAt)
        assertNull(savedEdit.reminderAt)
    }

    @Test
    fun purgeRemovesContentButRetainsDeletionHistory() {
        val purged = base.copy(isPurged = true, reminderAt = 1000).edited("a", 200, "purged")
        assertEquals("", purged.title)
        assertEquals("", purged.content)
        assertNull(purged.reminderAt)
        assertNotNull(purged.deletedAt)
        val result = MergeEngine.merge(listOf(purged), listOf(base))
        assertEquals(listOf(purged), result.notes)
        assertTrue(result.notes.single().isPurged)
    }

    @Test
    fun explicitRestoreIsANewDescendantOfTheDeletion() {
        val deleted = base.copy(deletedAt = 200).edited("a", 200, "deleted")
        val restored = deleted.copy(deletedAt = null).edited("a", 300, "restored")
        val result = MergeEngine.merge(listOf(deleted), listOf(restored))
        assertEquals(listOf(restored), result.notes)
        assertNull(result.notes.single().deletedAt)
    }

    @Test
    fun identicalParallelSavesDoNotProduceConflictCopy() {
        val a = base.copy(color = NoteColor.MINT).edited("a", 200, "a")
        val b = base.copy(color = NoteColor.MINT).edited("b", 200, "b")
        val result = MergeEngine.merge(listOf(a), listOf(b))
        assertEquals(1, result.notes.size)
        assertEquals(0, result.conflictCount)
        assertTrue("a" in result.notes.single().ancestorRevisions)
    }

    @Test
    fun divergentDrawingsSurviveSyncAndRepeatedMerges() {
        val original = base.copy(whiteboard = Whiteboard())
        val aDrawing = Whiteboard(listOf(WhiteboardStroke(points = listOf(WhiteboardPoint(10f, 20f)))))
        val bDrawing = Whiteboard(listOf(WhiteboardStroke(points = listOf(WhiteboardPoint(30f, 40f)))))
        val left = original.copy(whiteboard = aDrawing).edited("a", 200, "left")
        val right = original.copy(whiteboard = bDrawing).edited("b", 300, "right")
        val merged = MergeEngine.merge(listOf(left), listOf(right))
        assertEquals(1, merged.conflictCount)
        assertEquals(setOf(aDrawing, bDrawing), merged.notes.map { it.whiteboard }.toSet())
        assertEquals(merged, MergeEngine.merge(listOf(right), listOf(left)))
        val restored = SyncJson.decode(SyncJson.encode(SyncDocument(notes = merged.notes))).notes
        assertEquals(merged.notes, restored)
        assertEquals(merged.notes, MergeEngine.merge(restored, listOf(left)).notes)
        assertEquals(0, MergeEngine.merge(restored, listOf(left)).conflictCount)
    }

    @Test
    fun identicalConcurrentDrawingsDoNotCreateConflictCopies() {
        val drawing = Whiteboard(listOf(WhiteboardStroke(points = listOf(WhiteboardPoint(10f, 20f)))))
        val left = base.copy(whiteboard = drawing).edited("a", 200, "left")
        val right = base.copy(whiteboard = drawing).edited("b", 300, "right")
        val result = MergeEngine.merge(listOf(left), listOf(right))
        assertEquals(1, result.notes.size)
        assertEquals(0, result.conflictCount)
        assertEquals(drawing, result.notes.single().whiteboard)
    }

    @Test
    fun clearedWhiteboardRemainsDistinctFromConcurrentTextNote() {
        val drawing = base.copy(whiteboard = Whiteboard()).edited("a", 200, "drawing")
        val text = base.edited("b", 300, "text")
        val result = MergeEngine.merge(listOf(drawing), listOf(text))
        assertEquals(1, result.conflictCount)
        assertEquals(setOf(null, Whiteboard()), result.notes.map { it.whiteboard }.toSet())
    }

    @Test
    fun atomicMergeAfterNetworkPreservesEditsMadeDuringSync() {
        val remote = base.copy(color = NoteColor.CORAL).edited("b", 200, "remote")
        val uploaded = MergeEngine.merge(listOf(base), listOf(remote)).notes
        val localWhileNetworkWasRunning = base.copy(content = "网络等待期间继续输入").edited("a", 300, "newer-local")
        val committed = MergeEngine.merge(listOf(localWhileNetworkWasRunning), uploaded).notes
        assertTrue(committed.any { it.content == "网络等待期间继续输入" })
        assertTrue(committed.any { it.color == NoteColor.CORAL })
        assertFalse(committed == uploaded)
    }

    @Test(expected = SyncProtocolException::class)
    fun inconsistentContentForTheSameRevisionFailsClosed() {
        MergeEngine.merge(listOf(base), listOf(base.copy(content = "损坏的同版本内容")))
    }
}
