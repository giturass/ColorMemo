package dev.colormemo.app.data

import dev.colormemo.core.model.NoteAttachment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownAttachmentsTest {
    private val image = NoteAttachment.create("照片 [1].png", "image/png", byteArrayOf(1))

    @Test
    fun parsesInlineAndReferenceImagesAndLinksButIgnoresCodeAndEscapes() {
        val markdown = """
            ![内联](<${image.markdownPath}>)
            ![引用][photo]
            [下载](attachments/document.txt)
            `![代码](attachments/inline.png)`
            \![转义](attachments/escaped.png)

            ```markdown
            ![代码块](attachments/fenced.png)
            ```

                ![缩进代码](attachments/indented.png)

            [photo]: attachments/reference.png
        """.trimIndent()
        assertEquals(setOf(image.markdownPath, "attachments/reference.png"), markdownAttachmentReferences(markdown, imagesOnly = true))
        assertEquals(setOf(image.markdownPath, "attachments/reference.png", "attachments/document.txt", "attachments/escaped.png"), markdownAttachmentReferences(markdown))
    }

    @Test
    fun removingLastReferenceDropsManagedImageButKeepsLegacyAttachments() {
        val legacy = NoteAttachment.create("旧照片.png", "image/png", byteArrayOf(2))
        val document = NoteAttachment.create("说明.txt", "text/plain", byteArrayOf(3))
        val attachments = listOf(image, legacy, document)
        val remaining = retainMarkdownAttachments(attachments, listOf(image.id), emptySet())
        assertEquals(listOf(legacy, document), remaining)
        // Reinstating the Markdown before saving restores the retained draft attachment.
        assertEquals(attachments, retainMarkdownAttachments(attachments, listOf(image.id), setOf(image.markdownPath)))
    }

    @Test
    fun imageIsRetainedWhileAnyReferenceRemains() {
        val markdown = "![再次引用](<${image.markdownPath}>)"
        assertEquals(listOf(image), retainMarkdownAttachments(listOf(image), listOf(image.id), markdownAttachmentReferences(markdown)))
    }

    @Test
    fun generatedPathsArePortableAndDisambiguateDifferentContents() {
        val unsafeName = NoteAttachment.create("../照片<>?#%.png", "image/png", byteArrayOf(1))
        assertTrue(unsafeName.markdownPath.startsWith("attachments/"))
        assertFalse(unsafeName.markdownPath.contains("../"))
        assertFalse(unsafeName.markdownPath.contains('<'))
        assertFalse(unsafeName.markdownPath.contains('%'))
        val different = NoteAttachment.create(unsafeName.fileName, "image/png", byteArrayOf(2))
        assertFalse(unsafeName.markdownPath == different.markdownPath)
    }
}
