package dev.colormemo.app.data

import dev.colormemo.core.model.NoteAttachment
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AttachmentStoreTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun largePayloadSurvivesReopeningFromSmallMetadata() {
        val root = temporary.newFolder()
        val store = AttachmentStore(root)
        val file = NoteAttachment.create("large.bin", "application/octet-stream", ByteArray(3 * 1024 * 1024) { (it % 251).toByte() })
        store.persist(file)
        store.persist(file)
        val metadata = file.copy(dataBase64 = "")
        assertEquals(file, AttachmentStore(root).hydrate(metadata))
        assertEquals(1, root.listFiles()!!.size)
    }

    @Test fun corruptedPayloadAndTraversalNeverReturnFileContents() {
        val root = temporary.newFolder()
        val store = AttachmentStore(root)
        val file = NoteAttachment.create("a.txt", "text/plain", "abc".toByteArray())
        store.persist(file)
        File(root, file.sha256).writeText("bad")
        assertThrows(IllegalStateException::class.java) { store.hydrate(file.copy(dataBase64 = "")) }
        assertThrows(IllegalArgumentException::class.java) { store.hydrate(file.copy(sha256 = "../secret", dataBase64 = "")) }
    }

    @Test fun zeroByteFileSurvivesReopening() {
        val store = AttachmentStore(temporary.newFolder())
        val file = NoteAttachment.create("empty.txt", "text/plain", byteArrayOf())
        store.persist(file)
        assertEquals(file, store.hydrate(file.copy(dataBase64 = "")))
    }
}
