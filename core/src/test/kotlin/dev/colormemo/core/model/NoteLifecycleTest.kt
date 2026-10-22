package dev.colormemo.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteLifecycleTest {
    @Test
    fun lifecycleEditsCanKeepTheOriginalListTimestamp() {
        val original = Note(
            id = "note",
            isPinned = true,
            isArchived = true,
            createdAt = 100,
            updatedAt = 100,
            revision = "original",
            deviceId = "device",
        )
        val trashed = original.copy(deletedAt = 200).edited(
            deviceId = "device",
            now = 200,
            newRevision = "trashed",
            preserveUpdatedAt = true,
        )
        val restored = trashed.copy(deletedAt = null).edited(
            deviceId = "device",
            now = 300,
            newRevision = "restored",
            preserveUpdatedAt = true,
        )

        assertEquals(100L, trashed.updatedAt)
        assertEquals(100L, restored.updatedAt)
        assertEquals(true, restored.isPinned)
        assertEquals(true, restored.isArchived)
        assertEquals("trashed", restored.parentRevision)
        assertTrue("original" in restored.ancestorRevisions)
        assertTrue("trashed" in restored.ancestorRevisions)
    }

    @Test
    fun contentEditsStillAdvanceTimeEvenWhenTheClockMovesBackwards() {
        val original = Note(updatedAt = 100, revision = "original")
        val edited = original.copy(content = "new content").edited("device", 50, "edited")
        assertEquals(101L, edited.updatedAt)
    }
}
