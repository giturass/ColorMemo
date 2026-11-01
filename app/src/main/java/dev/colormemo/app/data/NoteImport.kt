package dev.colormemo.app.data

import dev.colormemo.core.model.Note
import dev.colormemo.core.model.NoteAttachment
import dev.colormemo.core.sync.SyncDocument
import dev.colormemo.core.sync.SyncJson
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.URLConnection
import java.net.URLDecoder
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Locale
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

internal const val MAX_IMPORT_FILE_BYTES = 64 * 1024 * 1024
private const val MAX_EXPANDED_ARCHIVE_BYTES = 128L * 1024 * 1024
private const val MAX_ARCHIVE_ENTRIES = 100_000
private const val MAX_MARKDOWN_BYTES = 512 * 1024

internal class NoteImportException(message: String) : IllegalArgumentException(message)

internal data class ImportedNotes(val notes: List<Note>, val unresolvedLinks: Int = 0)

/** Detect the contents, since document providers do not always report the right MIME type. */
internal fun readNoteImport(file: File, now: Long = System.currentTimeMillis()): ImportedNotes {
    checkImport(file.length() <= MAX_IMPORT_FILE_BYTES, "导入文件最多 64 MiB，请拆分后重试")
    val isZip = file.inputStream().use { it.read() == 'P'.code && it.read() == 'K'.code }
    if (isZip) return readMarkdownArchive(file, now)
    val bytes = file.inputStream().use { it.readImportBytes(SyncJson.MAX_DOCUMENT_BYTES) }
    val withoutBom = if (bytes.size >= 3 && bytes[0] == 0xef.toByte() &&
        bytes[1] == 0xbb.toByte() && bytes[2] == 0xbf.toByte()
    ) bytes.copyOfRange(3, bytes.size) else bytes
    return ImportedNotes(SyncJson.decodeDocument(withoutBom).notes)
}

/** No ZIP entry is ever extracted as a filesystem path. ZipFile also checks the central directory. */
private fun readMarkdownArchive(file: File, now: Long): ImportedNotes = ZipFile(file).use { zip ->
    val archive = MarkdownImportArchive(zip)
    archive.nativeDocument()?.let { entry ->
        return@use ImportedNotes(SyncJson.decodeDocument(archive.read(entry, SyncJson.MAX_DOCUMENT_BYTES)).notes)
    }
    val markdown = archive.paths.filter { it.isMarkdownPath() && !it.isArchiveJunk() }.sorted()
    checkImport(markdown.isNotEmpty(), "ZIP 中没有 Markdown 笔记，请选择包含 .md 或 .markdown 文件的 ZIP")
    checkImport(markdown.size <= SyncJson.MAX_NOTES, "ZIP 中的笔记超过 10,000 条，请拆分后导入")
    val attachmentCache = mutableMapOf<String, NoteAttachment>()
    var unresolvedLinks = 0
    val envelopeBytes = SyncJson.encodeDocument(SyncDocument()).size
    var encodedBytes = envelopeBytes.toLong()
    val notes = markdown.mapIndexed { index, path ->
        val source = decodeMarkdownUtf8(archive.read(path, MAX_MARKDOWN_BYTES))
        val attachments = linkedMapOf<String, NoteAttachment>()
        val missing = mutableSetOf<String>()
        val migrated = migrateMarkdown(source, path.substringAfterLast('/').substringBeforeLast('.')) { destination, wiki ->
            if (!isLocalMarkdownDestination(destination)) return@migrateMarkdown null
            val target = archive.resolve(path, destination, wiki)
            if (target == null) {
                missing += destination
                return@migrateMarkdown null
            }
            // Keep links between notes in their original form; they are not attached file copies.
            if (target.isMarkdownPath()) return@migrateMarkdown null
            attachments.getOrPut(target) {
                checkImport(attachments.size < NoteAttachment.MAX_PER_NOTE, "单条笔记最多 20 个附件，请拆分后导入")
                checkImport(attachments.values.sumOf { it.sizeBytes.toLong() } + archive.sizeOf(target) <= NoteAttachment.MAX_NOTE_BYTES,
                    "单条笔记的附件合计超过 8 MiB，请拆分后导入")
                attachmentCache.getOrPut(target) {
                    val bytes = archive.read(target, NoteAttachment.MAX_FILE_BYTES)
                    NoteAttachment.create(target.substringAfterLast('/'), importedMimeType(target, bytes), bytes)
                }
            }
        }
        unresolvedLinks += missing.size
        val entryTime = archive.modifiedAt(path)?.takeIf { it >= 0 } ?: now
        val created = migrated.createdAt ?: entryTime
        Note(
            title = migrated.title,
            content = migrated.content,
            createdAt = created,
            updatedAt = migrated.updatedAt ?: maxOf(created, entryTime),
            isArchived = path.substringBeforeLast('/', "").substringAfterLast('/') == "archive" &&
                Regex("[0-9]{4,}-.+").matches(path.substringAfterLast('/')),
            attachments = attachments.values.toList(),
        ).also { note ->
            // Stop as soon as this migration exceeds the encoded limit, rather than
            // retaining the entire decompressed library in memory before validation.
            encodedBytes += SyncJson.encodeDocument(SyncDocument(notes = listOf(note))).size - envelopeBytes +
                if (index == 0) 0 else 1
            checkImport(encodedBytes <= SyncJson.MAX_DOCUMENT_BYTES, "导入笔记超过 16 MiB，请拆分后重试")
        }
    }
    // Validate the entire migration, including encoded size, before any repository write.
    SyncJson.encodeDocument(SyncDocument(notes = notes))
    ImportedNotes(notes, unresolvedLinks)
}

private class MarkdownImportArchive(private val zip: ZipFile) {
    private val entries = linkedMapOf<String, ZipEntry>()
    val paths: Set<String> get() = entries.keys
    private val root: String
    private val byLowercasePath: Map<String, List<String>>
    private val byFilename: Map<String, List<String>>
    private var readBytes = 0L

    init {
        var count = 0
        var expandedBytes = 0L
        val allPaths = mutableSetOf<String>()
        val enumeration = zip.entries()
        while (enumeration.hasMoreElements()) {
            val entry = enumeration.nextElement()
            checkImport(++count <= MAX_ARCHIVE_ENTRIES, "ZIP 中的文件过多，请拆分后导入")
            val path = safeArchiveEntryPath(entry.name)
            if (path.isEmpty() && entry.isDirectory) continue
            checkImport(allPaths.add(path), "ZIP 包含重复的文件路径，无法确定应导入的内容")
            checkImport(entry.size >= 0 && entry.size <= MAX_EXPANDED_ARCHIVE_BYTES, "ZIP 文件大小无效或超过限制")
            expandedBytes += entry.size
            checkImport(expandedBytes <= MAX_EXPANDED_ARCHIVE_BYTES, "ZIP 解压后超过 128 MiB，请拆分后导入")
            if (!entry.isDirectory) entries[path] = entry
        }
        val meaningful = paths.filterNot(String::isArchiveJunk)
        val firstParent = meaningful.firstOrNull()?.substringBeforeLast('/', "").orEmpty()
        root = firstParent.split('/').filter(String::isNotEmpty).runningFold("") { prefix, part -> prefix + part + "/" }
            .lastOrNull { prefix -> meaningful.all { it.startsWith(prefix) } }.orEmpty()
        byLowercasePath = paths.groupBy { it.lowercase(Locale.ROOT) }
        byFilename = paths.groupBy { it.substringAfterLast('/').lowercase(Locale.ROOT) }
    }

    fun nativeDocument(): String? {
        if (MARKDOWN_ARCHIVE_DOCUMENT in entries) return MARKDOWN_ARCHIVE_DOCUMENT
        return (root + MARKDOWN_ARCHIVE_DOCUMENT).takeIf { it in entries }
    }

    fun modifiedAt(path: String): Long? = entries[path]?.time
    fun sizeOf(path: String): Long = entries.getValue(path).size

    fun read(path: String, limit: Int): ByteArray {
        val entry = entries.getValue(path)
        checkImport(entry.size <= limit, "文件「" + path.substringAfterLast('/') + "」超过导入大小限制")
        val bytes = zip.getInputStream(entry).use { it.readImportBytes(limit) }
        readBytes += bytes.size
        checkImport(readBytes <= MAX_EXPANDED_ARCHIVE_BYTES, "导入内容超过大小限制，请拆分后重试")
        val crc = CRC32().apply { update(bytes) }.value
        checkImport(bytes.size.toLong() == entry.size && crc == entry.crc, "ZIP 中的文件已损坏，未导入任何笔记")
        return bytes
    }

    fun resolve(notePath: String, destination: String, wiki: Boolean): String? {
        val path = destination.substringBefore('#').substringBefore('?')
        val decoded = runCatching { URLDecoder.decode(path.replace("+", "%2B"), "UTF-8") }.getOrDefault(path)
        val variants = listOf(decoded, path).distinct()
        val requested = if (wiki) variants.flatMap { listOf(it, it + ".md", it + ".markdown") } else variants
        val candidates = requested.flatMap { variant ->
            listOfNotNull(
                resolveArchivePath(notePath.substringBeforeLast('/', ""), variant),
                resolveArchivePath(root.trimEnd('/'), variant.removePrefix("/")),
                resolveArchivePath("", variant),
            )
        }.distinct()
        candidates.firstOrNull { it in entries }?.let { return it }
        candidates.forEach { candidate ->
            byLowercasePath[candidate.lowercase(Locale.ROOT)]?.singleOrNull()?.let { return it }
        }
        // Obsidian vaults commonly use a bare filename or a path relative to the vault root.
        if (wiki || variants.all { '/' !in it && '\\' !in it }) {
            return requested.flatMap { byFilename[it.substringAfterLast('/').lowercase(Locale.ROOT)].orEmpty() }
                .distinct().singleOrNull()
        }
        return null
    }
}

private fun safeArchiveEntryPath(name: String): String {
    val path = name.replace('\\', '/')
    checkImport(path.length <= 4096 && !path.startsWith('/') && !Regex("^[A-Za-z]:").containsMatchIn(path) &&
        path.none(Char::isISOControl) && path.split('/').none { it == ".." }, "ZIP 包含不安全的文件路径")
    return path.split('/').filter { it.isNotEmpty() && it != "." }.joinToString("/")
}

private fun resolveArchivePath(parent: String, target: String): String? {
    val normalized = target.replace('\\', '/')
    val parts = if (normalized.startsWith('/')) mutableListOf() else parent.split('/').filter(String::isNotEmpty).toMutableList()
    for (part in normalized.split('/')) when (part) {
        "", "." -> Unit
        ".." -> if (parts.isEmpty()) return null else parts.removeAt(parts.lastIndex)
        else -> {
            if (part.any(Char::isISOControl)) return null
            parts += part
        }
    }
    return parts.joinToString("/")
}

internal fun isLocalMarkdownDestination(destination: String): Boolean =
    destination.isNotBlank() && !destination.startsWith('#') && !destination.startsWith("//") &&
        !Regex("^[A-Za-z][A-Za-z0-9+.-]*:").containsMatchIn(destination)

private fun String.isMarkdownPath() = substringAfterLast('.', "").lowercase(Locale.ROOT) in setOf("md", "markdown", "mdown", "mkd")

private fun String.isArchiveJunk() = split('/').any { it == "__MACOSX" || it.startsWith('.') }

private fun importedMimeType(path: String, bytes: ByteArray): String =
    URLConnection.guessContentTypeFromStream(bytes.inputStream()) ?: when (path.substringAfterLast('.').lowercase(Locale.ROOT)) {
    "png" -> "image/png"
    "jpg", "jpeg" -> "image/jpeg"
    "gif" -> "image/gif"
    "webp" -> "image/webp"
    "svg" -> "image/svg+xml"
    "heic", "heif" -> "image/heif"
    "avif" -> "image/avif"
    "pdf" -> "application/pdf"
    else -> URLConnection.guessContentTypeFromName(path) ?: "application/octet-stream"
}

private fun decodeMarkdownUtf8(bytes: ByteArray): String = try {
    Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")
} catch (_: java.nio.charset.CharacterCodingException) {
    throw NoteImportException("Markdown 不是有效的 UTF-8 文本，请在原应用中导出 UTF-8 文件")
}

internal fun InputStream.copyImportBytes(output: OutputStream, limit: Int) {
    val buffer = ByteArray(8192)
    var total = 0L
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        total += count
        checkImport(total <= limit, "文件超过导入大小限制，请拆分后重试")
        output.write(buffer, 0, count)
    }
}

internal fun InputStream.readImportBytes(limit: Int): ByteArray =
    ByteArrayOutputStream().also { copyImportBytes(it, limit) }.toByteArray()

private fun checkImport(condition: Boolean, message: String) {
    if (!condition) throw NoteImportException(message)
}
