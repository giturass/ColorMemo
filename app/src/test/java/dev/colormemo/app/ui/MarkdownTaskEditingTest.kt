package dev.colormemo.app.ui

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.commonmark.ext.gfm.strikethrough.Strikethrough
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.node.Code
import org.commonmark.node.Image
import org.commonmark.node.Link
import org.commonmark.node.Node
import org.commonmark.node.StrongEmphasis
import org.commonmark.parser.IncludeSourceSpans
import org.commonmark.parser.Parser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownTaskEditingTest {
    private val parser = Parser.builder()
        .extensions(listOf(TablesExtension.create(), StrikethroughExtension.create()))
        .includeSourceSpans(IncludeSourceSpans.BLOCKS)
        .build()

    private fun parse(markdown: String) = replaceMarkdownTaskItems(parser.parse(markdown), markdown)

    private fun descendants(node: Node): List<Node> = buildList {
        var child = node.firstChild
        while (child != null) {
            add(child)
            addAll(descendants(child))
            child = child.next
        }
    }

    private fun tasks(markdown: String) = descendants(parse(markdown)).filterIsInstance<MarkdownTaskItem>()

    @Test
    fun togglesOnlyTheSelectedDuplicateAndPreservesSelection() {
        val markdown = "标题😀\n\n- [ ] 同名\n- [ ] 同名\n- [x] 同名"
        val before = TextFieldValue(markdown, TextRange(markdown.length, 2))
        val result = before.toggleMarkdownTask(tasks(markdown)[1].marker)
        assertEquals("标题😀\n\n- [ ] 同名\n- [x] 同名\n- [x] 同名", result.text)
        assertEquals(before.selection, result.selection)
        val undone = result.toggleMarkdownTask(tasks(result.text)[1].marker)
        assertEquals(before, undone)
    }

    @Test
    fun handlesNestedQuotedOrderedAndMixedBulletTasks() {
        val markdown = "> - [ ] 外层\n>   1. [X] 子项\n>      + [ ] 孙项\n\n* [x] 最后"
        val tasks = tasks(markdown)
        assertEquals(listOf(false, true, false, true), tasks.map { it.marker.checked })
        val result = TextFieldValue(markdown).toggleMarkdownTask(tasks[1].marker)
        assertEquals(markdown.replace("[X]", "[ ]"), result.text)
    }

    @Test
    fun preservesCrLfCrTabsAndTrailingWhitespace() {
        val markdown = "前文\r\n\r\n-\t[ ]\t第一项  \r\n\t续行\r\n\r\n+ [X] 第二项\r* [ ] 第三项\r"
        val tasks = tasks(markdown)
        assertEquals(3, tasks.size)
        assertEquals(markdown.indexOf("[X]") + 1, tasks[1].marker.offset)
        assertEquals(markdown.lastIndexOf("[ ]") + 1, tasks[2].marker.offset)
        assertEquals(markdown.replace("[X]", "[ ]"), TextFieldValue(markdown).toggleMarkdownTask(tasks[1].marker).text)
    }

    @Test
    fun codeExamplesEscapesEntitiesAndPlainTextAreNotTasks() {
        val markdown = """
            ```markdown
            - [ ] fenced example
            ```

                - [ ] indented example

            [ ] plain text

            - `[ ] inline code`
            - \[ ] escaped
            - &#91; &#93; entity
            - [ ] actual
        """.trimIndent()
        val tasks = tasks(markdown)
        assertEquals(1, tasks.size)
        assertEquals(markdown.lastIndexOf("[ ]") + 1, tasks.single().marker.offset)
    }

    @Test
    fun referenceLinksAreNotTurnedIntoCheckboxes() {
        val markdown = "- [x] reference\n- [x] [ref]\n- [ ] actual\n\n[x]: https://example.com\n[ref]: https://example.org"
        assertEquals(1, tasks(markdown).size)
        val destinations = descendants(parse(markdown)).filterIsInstance<Link>().map { it.destination }
        assertTrue("https://example.com" in destinations)
        assertTrue("https://example.org" in destinations)
    }

    @Test
    fun keepsFormattingImagesLinksAndCodeWithinTaskLabels() {
        val markdown = "- [ ] **加粗** ~~删除~~ [链接](https://example.com) ![图片](attachments/a.png) `代码`"
        val nodes = descendants(parse(markdown))
        assertEquals(1, nodes.filterIsInstance<MarkdownTaskItem>().size)
        assertEquals(1, nodes.filterIsInstance<StrongEmphasis>().size)
        assertEquals(1, nodes.filterIsInstance<Strikethrough>().size)
        assertEquals("https://example.com", nodes.filterIsInstance<Link>().single().destination)
        assertEquals("attachments/a.png", nodes.filterIsInstance<Image>().single().destination)
        assertEquals("代码", nodes.filterIsInstance<Code>().single().literal)
    }

    @Test
    fun tablesRemainTablesAndDoNotCreateTasksFromCells() {
        val markdown = "| 任务 | 状态 |\n| --- | --- |\n| - [ ] 表格示例 | ~~文字~~ |\n\n- [ ] 真正待办"
        val nodes = descendants(parse(markdown))
        assertEquals(1, nodes.filterIsInstance<TableBlock>().size)
        assertEquals(1, nodes.filterIsInstance<Strikethrough>().size)
        assertEquals(1, nodes.filterIsInstance<MarkdownTaskItem>().size)
    }

    @Test
    fun acceptsEmptyTasksButRequiresWhitespaceBeforeTaskText() {
        val markdown = "- [ ]\n- [x] \n- [ ]**无空格**\n- [ ] 有空格"
        assertEquals(listOf(false, true, false), tasks(markdown).map { it.marker.checked })
    }

    @Test
    fun nestedTasksInsideOrdinaryListItemsKeepTheirSourcePositions() {
        val markdown = "- 普通项目\n\n  - [ ] 嵌套待办\n\n    续段 [x] 文字\n\n- [ ] 下一项"
        val tasks = tasks(markdown)
        assertEquals(2, tasks.size)
        assertEquals(markdown.indexOf("[ ]") + 1, tasks.first().marker.offset)
        assertEquals(markdown.lastIndexOf("[ ]") + 1, tasks.last().marker.offset)
    }

    @Test
    fun invalidOrOutdatedMarkersDoNotChangeTheDraft() {
        val value = TextFieldValue("- [ ] 待办")
        assertSame(value, value.toggleMarkdownTask(MarkdownTaskMarker(-1, false)))
        assertSame(value, value.toggleMarkdownTask(MarkdownTaskMarker(Int.MAX_VALUE, false)))
        assertSame(value, value.toggleMarkdownTask(MarkdownTaskMarker(2, false)))
        assertSame(value, value.toggleMarkdownTask(MarkdownTaskMarker(3, true)))
        assertFalse(tasks(value.text).single().marker.checked)
        assertTrue(tasks(value.toggleMarkdownTask(MarkdownTaskMarker(3, false)).text).single().marker.checked)
    }
}
