package dev.colormemo.app.ui

import dev.colormemo.core.model.Note
import dev.colormemo.core.model.Whiteboard
import org.junit.Assert.assertEquals
import org.junit.Test

class NoteSectionsTest {
    @Test fun textNotesAndBoardsWithOrWithoutTextHaveDisjointHomeSections() {
        val notes = listOf(
            Note(id = "text", content = "便签内容"),
            Note(id = "pinned-text", isPinned = true),
            Note(id = "empty-board", whiteboard = Whiteboard()),
            Note(id = "described-board", title = "白板标题", content = "白板说明", whiteboard = Whiteboard(), isPinned = true),
            Note(id = "archived", isArchived = true),
            Note(id = "trashed-board", deletedAt = 1, whiteboard = Whiteboard()),
            Note(id = "purged", deletedAt = 1, isPurged = true),
        )
        assertEquals(setOf("text", "pinned-text"), notes.idsIn(MemoSection.NOTES))
        assertEquals(setOf("empty-board", "described-board"), notes.idsIn(MemoSection.WHITEBOARDS))
    }

    @Test fun archiveAndTrashStillIncludeBothKindsAndExcludePurgedRecords() {
        val notes = listOf(
            Note(id = "archived-text", isArchived = true),
            Note(id = "archived-board", isArchived = true, whiteboard = Whiteboard()),
            Note(id = "trashed-text", deletedAt = 1),
            Note(id = "trashed-board", deletedAt = 1, whiteboard = Whiteboard()),
            Note(id = "purged", deletedAt = 1, isPurged = true),
        )
        assertEquals(setOf("archived-text", "archived-board"), notes.idsIn(MemoSection.ARCHIVE))
        assertEquals(setOf("trashed-text", "trashed-board"), notes.idsIn(MemoSection.TRASH))
        assertEquals(emptySet<String>(), notes.idsIn(MemoSection.NOTES))
        assertEquals(emptySet<String>(), notes.idsIn(MemoSection.WHITEBOARDS))
    }

    private fun List<Note>.idsIn(section: MemoSection) = filter { it.inSection(section) }.map { it.id }.toSet()
}
