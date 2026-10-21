package dev.colormemo.app.ui

import androidx.compose.ui.text.input.TextFieldValue
import dev.colormemo.app.data.markdownPath
import dev.colormemo.core.model.NoteAttachment
import org.junit.Assert.*
import org.junit.Test

class RichMarkdownDocumentTest {
    @Test
    fun recognizesSupportedEntrySyntaxIncludingNestedFences() {
        listOf("中文 ${'$'}x${'$'}", "\\(x\\)", "\\[x\\]", "```mermaid\ngraph TD", "~~~math\nx", ">   ```latex\nx", "  - ```MERMAID\ngraph TD")
            .forEach { assertTrue(it, needsRichMarkdown(it)) }
        assertFalse(needsRichMarkdown("# 普通笔记\n- [ ] 待办\nmermaid 是一种图表语法"))
    }

    @Test
    fun taskPositionsPreserveOriginalNewlinesAndUnicode() {
        val source = "😀 ${'$'}x${'$'}\r\n\r\n- [ ] 同名\r\n- [ ] 同名\r> 1. [X] 子项"
        val document = RichMarkdownDocument(source, emptyList())
        assertEquals(listOf(2, 3, 4), document.tasks.map { it.line })
        val second = document.tasks[1].marker
        assertEquals(source.replaceRange(second.offset, second.offset + 1, "x"), TextFieldValue(source).toggleMarkdownTask(second).text)
        assertNull(document.task(second.offset, true))
        assertNull(document.task(0, false))
        assertEquals(second, document.task(second.offset, false))
        assertNotEquals(document.id, RichMarkdownDocument(source, emptyList()).id)
    }

    @Test
    fun codeEscapesReferencesAndTableCellsDoNotBecomeNativeActions() {
        val source = """
            ```mermaid
            graph TD
            A-->B
            ```
            ```markdown
            - [ ] example
            ```
            - \[ ] escaped
            - [x] reference

            [x]: https://example.com

            | a | b |
            | - | - |
            | - [ ] cell | text |

            - [ ] actual
        """.trimIndent()
        val document = RichMarkdownDocument(source, emptyList())
        assertEquals(1, document.tasks.size)
        assertEquals(source.lastIndexOf("[ ]") + 1, document.tasks.single().marker.offset)
    }

    @Test
    fun imageManifestOnlyExposesReferencedAttachmentsAndValidHttpsImages() {
        val image = NoteAttachment.create("测试 图片.png", "image/png", byteArrayOf(1, 2, 3))
        val source = """
            ![local](<${image.markdownPath}>)
            ![remote](https://example.com/a.png)
            ![file](file:///data/private.png)
            ![content](content://private/image)
            ![http](http://example.com/image.png)
            ![credentials](https://user:secret@example.com/image.png)
            `![example](https://example.com/unused.png)`
        """.trimIndent()
        val doc = RichMarkdownDocument(source, listOf(image))
        assertEquals(2, doc.images.size)
        assertEquals(image, doc.images.first().attachment)
        assertEquals("https://example.com/a.png", doc.images.last().source)
        assertTrue(doc.imageUrl(0).startsWith("$MARKDOWN_ORIGIN/note/${doc.id}/image/"))
    }
}
