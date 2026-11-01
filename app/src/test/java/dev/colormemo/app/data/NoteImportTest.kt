package dev.colormemo.app.data

import dev.colormemo.core.model.Note
import dev.colormemo.core.model.NoteAttachment
import dev.colormemo.core.model.NoteColor
import dev.colormemo.core.model.Whiteboard
import dev.colormemo.core.model.WhiteboardImage
import dev.colormemo.core.model.WhiteboardPoint
import dev.colormemo.core.model.WhiteboardStroke
import dev.colormemo.core.model.WhiteboardText
import dev.colormemo.core.sync.MergeEngine
import dev.colormemo.core.sync.SyncDocument
import dev.colormemo.core.sync.SyncJson
import dev.colormemo.core.sync.SyncProtocolException
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Instant
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NoteImportTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun exportedZipAndJsonRestoreTheSameSnapshotIncludingEditableBoardsTrashAndRevisions() {
        val image = NoteAttachment.create("照片.png", "image/png", byteArrayOf(1, 2, 3))
        val original = Note(
            id = "board", title = "白板", content = "说明", color = NoteColor.MINT,
            isPinned = true, isArchived = true, reminderAt = 1900000000000,
            whiteboard = Whiteboard(
                strokes = listOf(WhiteboardStroke(points = listOf(WhiteboardPoint(1f, 2f)))),
                images = listOf(WhiteboardImage("image", image.id, 0f, 0f, 100f, 100f)),
                texts = listOf(WhiteboardText("text", "可编辑文字", 0f, 0f, 300f)),
            ),
            attachments = listOf(image),
        ).edited("source-device")
        val trash = Note(id = "trash", title = "回收站", deletedAt = 100)
        val purged = Note(id = "purged", isPurged = true, deletedAt = 100)
        val notes = listOf(original, trash, purged).sortedBy(Note::id)
        val encoded = SyncJson.encodeDocument(SyncDocument(notes = notes))
        val json = file(encoded)
        val zip = temporary.newFile()
        zip.outputStream().use { writeMarkdownArchive(notes, it) { "data:image/png;base64," + it.dataBase64 } }
        assertEquals(notes, readNoteImport(json).notes)
        assertEquals(notes, readNoteImport(zip).notes)
        assertEquals(notes, MergeEngine.merge(readNoteImport(json).notes, readNoteImport(zip).notes).notes)
        assertEquals(0, readNoteImport(zip).unresolvedLinks)
    }

    @Test
    fun nativeArchiveInsideOneEnclosingFolderDoesNotAlsoImportItsMarkdownAsDuplicates() {
        val note = Note(id = "native", title = "原始笔记")
        val zip = archive(
            "export/colormemo.json" to SyncJson.encodeDocument(SyncDocument(notes = listOf(note))),
            "export/notes/0001-原始笔记.md" to "# 原始笔记\n".toByteArray(),
            "__MACOSX/._export" to byteArrayOf(0),
        )
        assertEquals(listOf(note), readNoteImport(zip).notes)
    }

    @Test
    fun jsonDetectionAcceptsBomAndDoesNotDependOnTheFilename() {
        val note = Note(id = "original", title = "正文")
        val bytes = byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte()) +
            SyncJson.encodeDocument(SyncDocument(notes = listOf(note)))
        assertEquals(listOf(note), readNoteImport(file(bytes)).notes)
        assertThrows(SyncProtocolException::class.java) {
            readNoteImport(file("""{"schemaVersion":2,"notes":[]}""".toByteArray()))
        }
        assertThrows(SyncProtocolException::class.java) {
            readNoteImport(file("""{"notes":[{"title":"缺失身份信息"}]}""".toByteArray()))
        }
    }

    @Test
    fun nestedMarkdownUsesFrontMatterHeadingOrFilenameWithoutDroppingMetadataOrFormatting() {
        val yaml = "---\r\ntitle: '迁移标题'\r\ncreated: 2024-01-02T03:04:05Z\r\nupdated: 2024-02-03T04:05:06Z\r\ntags: [one, two]\r\n---\r\n"
        val body = "- [x] 清单\r\n\r\n| A | B |\r\n| - | - |\r\n| 1 | 2 |\r\n"
        val result = readNoteImport(archive(
            "Notebook/one.MD" to ("\uFEFF" + yaml + "# 迁移标题\r\n\r\n" + body).toByteArray(),
            "Notebook/子目录/two.markdown" to "# **第二条**\n\n正文\n".toByteArray(),
            "Other/文件名.md" to "没有一级标题\n".toByteArray(),
            "__MACOSX/._ghost.md" to byteArrayOf(0),
            ".obsidian/hidden.md" to "# 配置\n".toByteArray(),
        ))
        assertEquals(3, result.notes.size)
        val note = result.notes.single { it.title == "迁移标题" }
        assertEquals(yaml + body, note.content)
        assertEquals(Instant.parse("2024-01-02T03:04:05Z").toEpochMilli(), note.createdAt)
        assertEquals(Instant.parse("2024-02-03T04:05:06Z").toEpochMilli(), note.updatedAt)
        assertEquals("正文\n", result.notes.single { it.title == "第二条" }.content)
        assertEquals("没有一级标题\n", result.notes.single { it.title == "文件名" }.content)
    }

    @Test
    fun legacyColorMemoArchiveImportsArchivedNotesAndTheirAttachments() {
        val bytes = byteArrayOf(2, 4, 6)
        val notes = readNoteImport(archive(
            "archive/0001-旧笔记.md" to "# 旧笔记\n\n![照片](<attachments/photo.png>)\n".toByteArray(),
            "archive/attachments/photo.png" to bytes,
            "notes/0002-未归档.md" to "# 未归档\n\n正文\n".toByteArray(),
        )).notes
        val archived = notes.single { it.title == "旧笔记" }
        assertTrue(archived.isArchived)
        assertFalse(notes.single { it.title == "未归档" }.isArchived)
        assertArrayEquals(bytes, archived.attachments.single().bytes())
        assertTrue(archived.content.contains(archived.attachments.single().markdownPath))
    }

    @Test
    fun notionStylePathsImagesReferencesAndFilesAreRewrittenWithoutTouchingCodeOrRemoteLinks() {
        val ticks = "\u0060"
        val code = ticks + "![example](../assets/photo%20(1).png)" + ticks
        val fence = ticks.repeat(3) + "markdown\n![example](../assets/photo%20(1).png)\n" + ticks.repeat(3)
        val formula = "$" + "$\nx^2 + y^2\n$" + "$"
        val content = "# 标题\n\n前文 ![照片](<../assets/photo%20(1).png> \"标题\") 后文\n\n" +
            "![再看][photo]\n\n[photo]: ../assets/photo%20(1).png \"引用标题\"\n\n" +
            "[文件](../assets/file+name.pdf#page=2)\n\n![远程](https://example.org/image.png)\n\n" +
            code + "\n\n" + fence + "\n\n" + formula + "\n"
        val photo = byteArrayOf(1, 3, 5, 7)
        val pdf = "%PDF-1.4".toByteArray()
        val note = readNoteImport(archive(
            "Export/pages/标题 abcdef.md" to content.toByteArray(),
            "Export/assets/photo (1).png" to photo,
            "Export/assets/file+name.pdf" to pdf,
        )).notes.single()
        assertEquals(2, note.attachments.size)
        val image = note.attachments.single { it.isImage }
        val document = note.attachments.single { !it.isImage }
        assertArrayEquals(photo, image.bytes())
        assertArrayEquals(pdf, document.bytes())
        assertTrue(note.content.contains("![照片](<" + image.markdownPath + "> \"标题\")"))
        assertTrue(note.content, note.content.contains("![再看](<" + image.markdownPath + "> \"引用标题\")"))
        assertTrue(note.content.contains("[文件](<" + document.markdownPath + ">)"))
        assertTrue(note.content.contains(code))
        assertTrue(note.content.contains(fence))
        assertTrue(note.content.contains(formula))
        assertTrue(note.content.contains("https://example.org/image.png"))
        assertEquals(setOf(image.markdownPath, "https://example.org/image.png"),
            markdownAttachmentReferences(note.content, imagesOnly = true))
    }

    @Test
    fun obsidianWikiAttachmentsResolveAcrossTheVaultAndLeaveNoteLinksAndCodeAlone() {
        val ticks = "\u0060"
        val content = "![[photo.png|320]]\n\n[[files/report.pdf|报告]]\n\n[[Another note]]\n\n" +
            "[[files/report.pdf|2024]]\n\n![[photo.png\\|320]]\n\n" +
            ticks + "![[photo.png]]" + ticks + "\n\n\\![[photo.png]]\n"
        val imported = readNoteImport(archive(
            "Vault/notes/one.md" to content.toByteArray(),
            "Vault/Another note.md" to "# Another note\n\n正文".toByteArray(),
            "Vault/assets/photo.png" to byteArrayOf(1),
            "Vault/files/report.pdf" to byteArrayOf(2),
        ))
        val note = imported.notes.single { it.title == "one" }
        assertEquals(2, note.attachments.size)
        assertTrue(note.content.contains("![photo\\.png](<" + note.attachments.single { it.isImage }.markdownPath + ">)"))
        assertTrue(note.content.contains("[报告](<" + note.attachments.single { !it.isImage }.markdownPath + ">)"))
        assertTrue(note.content.contains("[2024](<" + note.attachments.single { !it.isImage }.markdownPath + ">)"))
        assertFalse(note.content.contains("![[photo.png\\|320]]"))
        assertTrue(note.content.contains("[[Another note]]"))
        assertTrue(note.content.contains(ticks + "![[photo.png]]" + ticks))
        assertTrue(note.content.contains("\\![[photo.png]]"))
        assertEquals(0, imported.unresolvedLinks)
    }

    @Test
    fun differentFoldersKeepSameNamedNotesAndImagesSeparate() {
        val result = readNoteImport(archive(
            "a/note.md" to "![图](photo.png)".toByteArray(),
            "a/photo.png" to byteArrayOf(1),
            "b/note.md" to "![图](photo.png)".toByteArray(),
            "b/photo.png" to byteArrayOf(2),
        ))
        assertEquals(2, result.notes.size)
        assertEquals(2, result.notes.map(Note::id).toSet().size)
        assertEquals(2, result.notes.flatMap(Note::attachments).map { it.sha256 }.toSet().size)
        result.notes.forEach { assertTrue(it.content.contains(it.attachments.single().markdownPath)) }
    }

    @Test
    fun shortcutCollapsedAndNestedReferenceLinksKeepLabelsAndResolveTheImportedImage() {
        val source = "![photo]\n\n![photo][]\n\n[![photo][photo]][download]\n\n" +
            "> ![quoted][photo]\n\n[photo]: <assets/photo.png> \"title\"\n[download]: <assets/file.pdf>\n"
        val note = readNoteImport(archive(
            "note.md" to source.toByteArray(),
            "assets/photo.png" to byteArrayOf(1),
            "assets/file.pdf" to byteArrayOf(2),
        )).notes.single()
        val photo = note.attachments.single { it.isImage }
        val pdf = note.attachments.single { !it.isImage }
        assertEquals(setOf(photo.markdownPath), markdownAttachmentReferences(note.content, imagesOnly = true))
        assertEquals(setOf(photo.markdownPath, pdf.markdownPath), markdownAttachmentReferences(note.content))
        assertTrue(note.content.contains("> ![quoted](<" + photo.markdownPath + "> \"title\")"))
        assertTrue(note.content.contains("[![photo](<" + photo.markdownPath + "> \"title\")](<" + pdf.markdownPath + ">)"))
    }

    @Test
    fun headingImagesAndLinksAreNotDiscardedWhenDerivingTheTitle() {
        val note = readNoteImport(archive(
            "note.md" to "# [主页](https://example.org) ![标志](logo.png)\n\n正文".toByteArray(),
            "logo.png" to byteArrayOf(1),
        )).notes.single()
        assertEquals("主页 标志", note.title)
        assertTrue(note.content.startsWith("# [主页](https://example.org) ![标志](<"))
        assertTrue(note.content.contains(note.attachments.single().markdownPath))
    }

    @Test
    fun extensionlessImageResourcesAreDetectedFromTheirContents() {
        val png = java.util.Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aRXsAAAAASUVORK5CYII=",
        )
        val note = readNoteImport(archive("note.md" to "![](resources/abcd)".toByteArray(), "resources/abcd" to png)).notes.single()
        assertEquals("image/png", note.attachments.single().mimeType)
        assertArrayEquals(png, note.attachments.single().bytes())
    }

    @Test
    fun wikiImageInAHeadingRemainsInTheBodyAndKeepsItsAttachment() {
        val note = readNoteImport(archive(
            "note.md" to "# Logo ![[logo.png]]\n\n正文".toByteArray(),
            "logo.png" to byteArrayOf(1),
        )).notes.single()
        assertTrue(note.content.startsWith("# Logo !["))
        assertEquals(1, note.attachments.size)
        assertTrue(note.content.contains(note.attachments.single().markdownPath))
    }

    @Test
    fun missingAssetsAreReportedAndTheirOriginalReferencesArePreserved() {
        val content = "![一](missing.png)\n\n![二](missing.png)\n\n[笔记](other.md)\n\n" +
            "![远程](https://example.org/photo.png)\n\n[file](file:///private/file)\n"
        val result = readNoteImport(archive(
            "note.md" to content.toByteArray(),
            "other.md" to "另一条".toByteArray(),
        ))
        assertEquals(1, result.unresolvedLinks)
        assertEquals(content, result.notes.single { it.title == "note" }.content)
        assertTrue(result.notes.all { it.attachments.isEmpty() })
    }

    @Test
    fun malformedNativeMetadataCannotFallBackToMarkdownAndLoseNoteState() {
        assertThrows(SyncProtocolException::class.java) {
            readNoteImport(archive(
                "colormemo.json" to """{"schemaVersion":100,"notes":[]}""".toByteArray(),
                "notes/readable.md" to "# 正文".toByteArray(),
            ))
        }
    }

    @Test
    fun emptyAndNonMarkdownArchivesDoNotSucceedSilently() {
        assertThrows(NoteImportException::class.java) { readNoteImport(archive()) }
        assertThrows(NoteImportException::class.java) {
            readNoteImport(archive("image.png" to byteArrayOf(1), "__MACOSX/ignored.md" to "# 隐藏".toByteArray()))
        }
    }

    @Test
    fun traversalAbsoluteAndDuplicateNormalizedPathsAreRejected() {
        listOf("../escape.md", "/absolute.md", "C:\\note.md", "safe/../escape.md").forEach { path ->
            assertThrows(NoteImportException::class.java) { readNoteImport(archive(path to "# 不安全".toByteArray())) }
        }
        assertThrows(NoteImportException::class.java) {
            readNoteImport(archive("notes/one.md" to "一".toByteArray(), "notes/./one.md" to "二".toByteArray()))
        }
    }

    @Test
    fun invalidUtf8ExcessiveContentAndAttachmentCountsAreRejected() {
        assertThrows(NoteImportException::class.java) {
            readNoteImport(archive("note.md" to byteArrayOf(0xc3.toByte(), 0x28)))
        }
        assertThrows(SyncProtocolException::class.java) {
            readNoteImport(archive("note.md" to "x".repeat(SyncJson.MAX_CONTENT_CHARACTERS + 1).toByteArray()))
        }
        val images = (0..NoteAttachment.MAX_PER_NOTE).map { "image" + it + ".png" to byteArrayOf(it.toByte()) }
        val content = images.joinToString("\n\n") { "![](" + it.first + ")" }
        assertThrows(NoteImportException::class.java) {
            readNoteImport(archive(*(images + ("note.md" to content.toByteArray())).toTypedArray()))
        }
    }

    @Test
    fun highlyCompressedOversizedAttachmentsAreRejectedBeforeAllocation() {
        val oversized = ByteArray(NoteAttachment.MAX_FILE_BYTES + 1)
        val zip = archive("note.md" to "![](image.png)".toByteArray(), "image.png" to oversized)
        assertTrue(zip.length() < 100_000)
        assertThrows(NoteImportException::class.java) { readNoteImport(zip) }
    }

    @Test
    fun truncatedArchivesAndIncorrectCrcNeverProduceAPartialImport() {
        val valid = archive("first.md" to "第一条".toByteArray(), "second.md" to "第二条".toByteArray())
        assertThrows(ZipException::class.java) { readNoteImport(file(valid.readBytes().dropLast(22).toByteArray())) }
        val data = "unique-corruption-target".toByteArray()
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("note.md").apply {
                method = ZipEntry.STORED
                size = data.size.toLong()
                crc = CRC32().apply { update(data) }.value
            })
            zip.write(data)
            zip.closeEntry()
        }
        val corrupted = output.toByteArray()
        val start = corrupted.indices.first { index ->
            index + data.size <= corrupted.size && data.indices.all { corrupted[index + it] == data[it] }
        }
        corrupted[start] = 0
        assertThrows(NoteImportException::class.java) { readNoteImport(file(corrupted)) }
    }

    private fun file(bytes: ByteArray): File = temporary.newFile().apply { writeBytes(bytes) }

    private fun archive(vararg entries: Pair<String, ByteArray>): File = temporary.newFile().apply {
        ZipOutputStream(outputStream()).use { zip ->
            entries.forEach { (path, bytes) ->
                zip.putNextEntry(ZipEntry(path).apply { time = 1700000000000L })
                zip.write(bytes)
                zip.closeEntry()
            }
        }
    }
}
