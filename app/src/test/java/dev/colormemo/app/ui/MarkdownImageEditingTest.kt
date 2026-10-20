package dev.colormemo.app.ui

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import dev.colormemo.app.data.markdownPath
import dev.colormemo.core.model.NoteAttachment
import org.commonmark.node.Image
import org.commonmark.node.Paragraph
import org.commonmark.node.Text
import org.commonmark.parser.Parser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownImageEditingTest {
    private val attachment = NoteAttachment.create("照片 [1]*_&.png", "image/png", byteArrayOf(1, 2))

    @Test
    fun remoteUrlKeepsQueryEntitiesParenthesesAndEncodedCharacters() {
        val url = "https://example.com/照片(1).png?x=1&amp;name=%E4%B8%AD&copy;=2"
        val result = TextFieldValue("前后", TextRange(1)).insertMarkdownImage(url)
        val image = Parser.builder().build().parse(result.text).firstChild.next.firstChild as Image
        assertEquals(url, image.destination)
        assertEquals("图片", (image.firstChild as Text).literal)
        assertEquals(result.text.indexOf("后"), result.selection.start)
    }

    @Test
    fun insertsAtCursorBetweenParagraphsAndPlacesCursorAfterImage() {
        val result = TextFieldValue("前文后文", TextRange(2)).insertMarkdownImage(attachment)
        val document = Parser.builder().build().parse(result.text)
        assertEquals("前文", (document.firstChild.firstChild as Text).literal)
        val imageParagraph = document.firstChild.next as Paragraph
        val image = imageParagraph.firstChild as Image
        assertEquals(attachment.fileName, (image.firstChild as Text).literal)
        assertEquals(attachment.markdownPath, image.destination)
        assertEquals("后文", (imageParagraph.next.firstChild as Text).literal)
        assertEquals(result.text.indexOf("后文"), result.selection.start)
        assertTrue(result.selection.collapsed)
    }

    @Test
    fun reversedSelectionBecomesLiteralImageDescription() {
        val result = TextFieldValue("前*说明*后", TextRange(5, 1)).insertMarkdownImage(attachment)
        val image = Parser.builder().build().parse(result.text).firstChild.next.firstChild as Image
        assertEquals("*说明*", (image.firstChild as Text).literal)
        assertTrue(result.text.startsWith("前\n\n!["))
        assertTrue(result.text.endsWith(")\n\n后"))
    }

    @Test
    fun usesExistingBlankLinesAndMovesCursorPastThem() {
        val result = TextFieldValue("前\n\n\n\n后", TextRange(3)).insertMarkdownImage(attachment)
        assertTrue(result.text.startsWith("前\n\n!["))
        assertTrue(result.text.endsWith(")\n\n后"))
        assertEquals(result.text.indexOf("后"), result.selection.start)
    }

    @Test
    fun emptyNoteHasImageAndSpaceToContinueWriting() {
        val result = TextFieldValue().insertMarkdownImage(attachment)
        assertTrue(result.text.startsWith("!["))
        assertTrue(result.text.endsWith("\n\n"))
        assertEquals(result.text.length, result.selection.start)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsImageInsertionThatWouldExceedContentLimit() {
        TextFieldValue("完整正文", TextRange(4)).insertMarkdownImage(attachment, maxLength = 4)
    }
}
