package dev.colormemo.app.data

import dev.colormemo.core.model.Note
import dev.colormemo.core.model.NoteAttachment
import dev.colormemo.core.model.WhiteboardBrush
import dev.colormemo.core.model.Whiteboard
import dev.colormemo.core.model.WhiteboardPoint
import dev.colormemo.core.model.WhiteboardStroke
import dev.colormemo.core.model.WhiteboardImage
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

class MarkdownArchiveTest {
    @Test
    fun whiteboardExportEmbedsImageBelowInkWithoutAddingItToTheNoteText() {
        val attachment = NoteAttachment.create("照片.png", "image/png", byteArrayOf(1, 2, 3))
        val image = WhiteboardImage("image", attachment.id, 100f, 1200f, 800f, 400f)
        val board = Whiteboard(
            images = listOf(image),
            strokes = listOf(WhiteboardStroke(points = listOf(WhiteboardPoint(200f, 1300f)))),
        )
        val note = Note(title = "图片白板", content = "说明", whiteboard = board, attachments = listOf(attachment))
        val dataUrl = "data:image/png;base64,${attachment.dataBase64}"
        val entries = archiveEntries(listOf(note)) { dataUrl }
        val svg = entries.getValue("notes/0001-图片白板.svg").toString(Charsets.UTF_8)
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(svg.byteInputStream())
        val embedded = document.getElementsByTagName("image").item(0) as Element
        assertEquals("100.0", embedded.getAttribute("x"))
        assertEquals("1200.0", embedded.getAttribute("y"))
        assertEquals("800.0", embedded.getAttribute("width"))
        assertEquals("400.0", embedded.getAttribute("height"))
        assertEquals(dataUrl, embedded.getAttribute("href"))
        assertTrue(svg.indexOf("<image") < svg.indexOf("<circle"))
        assertEquals("# 图片白板\n\n说明\n\n![白板](<0001-图片白板.svg>)\n", entries.getValue("notes/0001-图片白板.md").toString(Charsets.UTF_8))
        org.junit.Assert.assertArrayEquals(attachment.bytes(), entries.getValue("notes/0001-图片白板-attachments/1-照片.png"))
    }

    @Test
    fun svgPreservesTapColorWidthAndRoundPolyline() {
        val whiteboard = Whiteboard(
            strokes = listOf(
                WhiteboardStroke(0xff0234abL, 6f, listOf(WhiteboardPoint(10.5f, 20f))),
                WhiteboardStroke(0xffff0000L, 12f, listOf(WhiteboardPoint(0f, 0f), WhiteboardPoint(1000f, 2000f))),
            ),
        )
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val document = factory.newDocumentBuilder().parse(whiteboardSvg(whiteboard).byteInputStream())
        val svg = document.documentElement
        assertEquals("http://www.w3.org/2000/svg", svg.namespaceURI)
        assertEquals("0 0 1000 2500", svg.getAttribute("viewBox"))
        assertEquals("1000", svg.getAttribute("width"))
        assertEquals("2500", svg.getAttribute("height"))
        val background = svg.getElementsByTagName("rect").item(0) as org.w3c.dom.Element
        assertEquals("1000", background.getAttribute("width"))
        assertEquals("2500", background.getAttribute("height"))
        val circle = document.getElementsByTagName("circle").item(0) as Element
        assertEquals("10.5", circle.getAttribute("cx"))
        assertEquals("20.0", circle.getAttribute("cy"))
        assertEquals("3.0", circle.getAttribute("r"))
        assertEquals("#0234ab", circle.getAttribute("fill"))
        val path = document.getElementsByTagName("path").item(0) as Element
        assertEquals("M 0.0 0.0 L 1000.0 2000.0", path.getAttribute("d"))
        assertEquals("#ff0000", path.getAttribute("stroke"))
        assertEquals("12.0", path.getAttribute("stroke-width"))
        assertEquals("round", path.getAttribute("stroke-linecap"))
        assertEquals("round", path.getAttribute("stroke-linejoin"))
    }

    @Test
    fun zipPreservesMarkdownAndReferencesSafeSvgNamesIncludingArchivedBoards() {
        val notes = listOf(
            Note(id = "plain", title = "文字", content = "原有正文", createdAt = 1),
            Note(id = "board", title = "../草图 <一>", content = "说明", createdAt = 2, whiteboard = Whiteboard()),
            Note(id = "archive", title = "../草图 <一>", createdAt = 3, isArchived = true, whiteboard = Whiteboard()),
            Note(id = "deleted", deletedAt = 1, createdAt = 4, whiteboard = Whiteboard()),
            Note(id = "purged", isPurged = true, createdAt = 5),
        )
        val output = ByteArrayOutputStream()
        writeMarkdownArchive(notes, output)
        val entries = linkedMapOf<String, String>()
        ZipInputStream(output.toByteArray().inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries[entry.name] = zip.readBytes().toString(Charsets.UTF_8)
            }
        }
        assertEquals(5, entries.size)
        assertEquals("# 文字\n\n原有正文\n", entries["notes/0001-文字.md"])
        assertFalse(entries.keys.any { it.contains("../") })
        assertFalse(entries.keys.any { it.endsWith("文字.svg") })
        val boardMarkdown = entries.getValue("notes/0002-_草图 _一_.md")
        assertTrue(boardMarkdown.contains("说明\n\n![白板](<0002-_草图 _一_.svg>)"))
        assertTrue(entries.getValue("notes/0002-_草图 _一_.svg").contains("<svg"))
        assertTrue(entries.getValue("archive/0003-_草图 _一_.md").contains("![白板](<0003-_草图 _一_.svg>)"))
        assertTrue(entries.getValue("archive/0003-_草图 _一_.svg").contains("<svg"))
    }
    @Test
    fun svgPreservesWatercolorLayersAndSquareMarkerTips() {
        val board = Whiteboard(listOf(
            WhiteboardStroke(points = listOf(WhiteboardPoint(10f, 20f)), width = 40f, brush = WhiteboardBrush.WATERCOLOR),
            WhiteboardStroke(points = listOf(WhiteboardPoint(50f, 60f)), width = 24f, brush = WhiteboardBrush.MARKER),
            WhiteboardStroke(points = listOf(WhiteboardPoint(0f, 0f), WhiteboardPoint(100f, 100f)), brush = WhiteboardBrush.MARKER),
        ))
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(whiteboardSvg(board).byteInputStream())
        assertEquals(3, document.getElementsByTagName("circle").length)
        val outer = document.getElementsByTagName("circle").item(0) as Element
        assertEquals("20.0", outer.getAttribute("r"))
        assertEquals("0.12", outer.getAttribute("fill-opacity"))
        val marker = document.getElementsByTagName("rect").item(1) as Element
        assertEquals("38.0", marker.getAttribute("x"))
        assertEquals("48.0", marker.getAttribute("y"))
        assertEquals("24.0", marker.getAttribute("width"))
        assertEquals("0.7", marker.getAttribute("fill-opacity"))
        val path = document.getElementsByTagName("path").item(0) as Element
        assertEquals("square", path.getAttribute("stroke-linecap"))
        assertEquals("bevel", path.getAttribute("stroke-linejoin"))
    }

    @Test
    fun zipContainsExactAttachmentBytesWithPortableSafeRelativeLinks() {
        val bytes = ByteArray(256) { it.toByte() }
        val attachment = NoteAttachment.create("../照片[1].bin", "application/octet-stream", bytes)
        val note = Note(id = "attachment", title = "附件笔记", attachments = listOf(attachment))
        val output = ByteArrayOutputStream()
        writeMarkdownArchive(listOf(note), output)
        val entries = linkedMapOf<String, ByteArray>()
        ZipInputStream(output.toByteArray().inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries[entry.name] = zip.readBytes()
            }
        }
        assertEquals(2, entries.size)
        assertFalse(entries.keys.any { it.contains("../") || it.startsWith("/") })
        val filePath = entries.keys.single { it.endsWith(".bin") }
        org.junit.Assert.assertArrayEquals(bytes, entries.getValue(filePath))
        val markdown = entries.getValue("notes/0001-附件笔记.md").toString(Charsets.UTF_8)
        assertTrue(markdown.contains("](<${filePath.removePrefix("notes/")}>)"))
    }

    @Test
    fun zipEmbedsImagesAndPreservesLegacyDocumentLinks() {
        val imageBytes = java.util.Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aRXsAAAAASUVORK5CYII=",
        )
        val image = NoteAttachment.create("照片[1].png", "image/png", imageBytes)
        val document = NoteAttachment.create("说明.txt", "text/plain", "文字".toByteArray())
        val note = Note(id = "images", title = "图片笔记", content = "正文", attachments = listOf(image, document))
        val output = ByteArrayOutputStream()
        writeMarkdownArchive(listOf(note), output)
        val entries = linkedMapOf<String, ByteArray>()
        ZipInputStream(output.toByteArray().inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries[entry.name] = zip.readBytes()
            }
        }
        assertEquals(3, entries.size)
        val markdown = entries.getValue("notes/0001-图片笔记.md").toString(Charsets.UTF_8)
        assertTrue(markdown.contains("![照片\\[1\\].png](<0001-图片笔记-attachments/1-照片_1_.png>)"))
        assertTrue(markdown.contains("\n[说明.txt](<0001-图片笔记-attachments/2-说明.txt>)"))
        assertFalse(markdown.contains("![说明.txt]"))
        org.junit.Assert.assertArrayEquals(imageBytes, entries.getValue("notes/0001-图片笔记-attachments/1-照片_1_.png"))
        org.junit.Assert.assertArrayEquals(document.bytes(), entries.getValue("notes/0001-图片笔记-attachments/2-说明.txt"))
    }

    @Test
    fun zipKeepsInlineImagePositionsAndRepeatedReferencesWithoutAppendingDuplicates() {
        val image = NoteAttachment.create("照片 [1].png", "image/png", byteArrayOf(1, 2, 3))
        val content = "前文\n\n![说明](<${image.markdownPath}>)\n\n中间文字\n\n![再次引用][photo]\n\n后文\n\n[photo]: <${image.markdownPath}>\n"
        val entries = archiveEntries(listOf(Note(title = "图文", content = content, attachments = listOf(image))))
        assertEquals("# 图文\n\n$content", entries.getValue("notes/0001-图文.md").toString(Charsets.UTF_8))
        assertEquals(2, entries.size)
        org.junit.Assert.assertArrayEquals(image.bytes(), entries.getValue("notes/${image.markdownPath}"))
    }

    @Test
    fun zipDeduplicatesSharedImagesAndKeepsSameFilenameWithDifferentBytes() {
        val first = NoteAttachment.create("照片.png", "image/png", byteArrayOf(1))
        val second = NoteAttachment.create("照片.png", "image/png", byteArrayOf(2))
        val notes = listOf(first, first.copy(id = java.util.UUID.randomUUID().toString()), second).mapIndexed { index, image ->
            Note(id = "note-$index", createdAt = index.toLong(), content = "![图片](<${image.markdownPath}>)", attachments = listOf(image))
        }
        val entries = archiveEntries(notes)
        assertEquals(5, entries.size)
        org.junit.Assert.assertArrayEquals(first.bytes(), entries.getValue("notes/${first.markdownPath}"))
        org.junit.Assert.assertArrayEquals(second.bytes(), entries.getValue("notes/${second.markdownPath}"))
    }

    @Test
    fun archivedInlineImagesResolveBesideTheirMarkdownAndCodeExamplesRemainUnchanged() {
        val image = NoteAttachment.create("照片.png", "image/png", byteArrayOf(1))
        val example = "`![示例](<${image.markdownPath}>)`"
        val archived = Note(id = "archived", title = "归档图文", isArchived = true, createdAt = 1,
            content = "![图片](<${image.markdownPath}>)", attachments = listOf(image))
        val code = Note(id = "code", title = "代码", createdAt = 2, content = example, attachments = listOf(image))
        val entries = archiveEntries(listOf(archived, code))
        assertTrue(entries.containsKey("archive/${image.markdownPath}"))
        val codeMarkdown = entries.getValue("notes/0002-代码.md").toString(Charsets.UTF_8)
        assertTrue(codeMarkdown.startsWith("# 代码\n\n$example\n"))
        assertTrue(codeMarkdown.contains("![照片.png](<0002-代码-attachments/1-照片.png>)"))
    }

    private fun archiveEntries(notes: List<Note>, imageDataUrl: (NoteAttachment) -> String = ::whiteboardImageDataUrl): Map<String, ByteArray> {
        val output = ByteArrayOutputStream()
        writeMarkdownArchive(notes, output, imageDataUrl)
        val entries = linkedMapOf<String, ByteArray>()
        ZipInputStream(output.toByteArray().inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries[entry.name] = zip.readBytes()
            }
        }
        return entries
    }
}
