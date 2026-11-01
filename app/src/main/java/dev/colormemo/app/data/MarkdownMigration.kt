package dev.colormemo.app.data

import dev.colormemo.core.model.NoteAttachment
import dev.colormemo.core.sync.SyncJson
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Locale
import org.commonmark.node.AbstractVisitor
import org.commonmark.node.Code
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Heading
import org.commonmark.node.Image
import org.commonmark.node.Link
import org.commonmark.node.LinkReferenceDefinition
import org.commonmark.node.Node
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.Text
import org.commonmark.parser.IncludeSourceSpans
import org.commonmark.parser.Parser

private val migrationParser = Parser.builder().includeSourceSpans(IncludeSourceSpans.BLOCKS_AND_INLINES).build()
private val wikiLink = Regex("""!?\[\[([^\]\r\n]+)]]""")

internal data class MigratedMarkdown(
    val title: String,
    val content: String,
    val createdAt: Long?,
    val updatedAt: Long?,
)

/** Edit only parsed link destinations, leaving tasks, tables, code, math and diagrams intact. */
internal fun migrateMarkdown(
    source: String,
    fallbackTitle: String,
    attachment: (destination: String, wiki: Boolean) -> NoteAttachment?,
): MigratedMarkdown {
    val frontMatter = readFrontMatter(source)
    val suffix = source.substring(frontMatter.end)
    val heading = (migrationParser.parse(suffix).firstChild as? Heading)?.takeIf { it.level == 1 }
    val headingTitle = heading?.let(::plainHeading)?.takeIf(String::isNotBlank)
    val title = frontMatter.properties["title"]?.takeIf(String::isNotBlank) ?: headingTitle ?: fallbackTitle
    val headingRange = heading?.let { MarkdownSource(suffix).range(it) }
    var headingHasLinks = headingRange?.let { wikiLink.containsMatchIn(suffix.substring(it)) } == true
    heading?.accept(object : AbstractVisitor() {
        override fun visit(image: Image) { headingHasLinks = true }
        override fun visit(link: Link) { headingHasLinks = true }
    })
    val content = if (headingRange != null && headingTitle == title && !headingHasLinks) {
        source.substring(0, frontMatter.end) + suffix.substring(headingRange.last + 1).trimStart('\r', '\n')
    } else source
    val original = MarkdownSource(content)
    val document = migrationParser.parse(content)
    val destinations = linkedSetOf<String>()
    document.accept(object : AbstractVisitor() {
        override fun visit(image: Image) { destinations += image.destination }
        override fun visit(link: Link) { destinations += link.destination; visitChildren(link) }
    })
    val replacements = linkedMapOf<IntRange, String>()
    val localFiles = destinations.mapNotNull { destination ->
        attachment(destination, false)?.let { destination to it }
    }.toMap()

    fun rewriteDestination(node: Node, destination: String, definition: Boolean = false) {
        val file = localFiles[destination] ?: return
        val range = original.range(node) ?: return
        val location = linkDestinationRange(content, range, definition)
        if (location != null) {
            replacements[location] = "<" + file.markdownPath + ">"
        } else if (!definition) {
            // CommonMark 0.16.1 loses definition spans. Normalize the actual reference
            // use to an inline link, preserving its label/title and leaving code alone.
            val endOfLabel = closingLinkLabel(content, range) ?: return
            if (endOfLabel < range.last && content[endOfLabel + 1] == '(') return
            val linkTitle = when (node) {
                is Image -> node.title
                is Link -> node.title
                else -> null
            }
            val quotedTitle = linkTitle?.let {
                " \"" + it.replace("&", "&amp;").replace("\\", "\\\\").replace("\"", "\\\"") + "\""
            }.orEmpty()
            replacements[(endOfLabel + 1)..range.last] = "(<" + file.markdownPath + ">" + quotedTitle + ")"
        }
    }
    document.accept(object : AbstractVisitor() {
        override fun visit(image: Image) = rewriteDestination(image, image.destination)
        override fun visit(link: Link) {
            rewriteDestination(link, link.destination)
            visitChildren(link)
        }
        override fun visit(definition: LinkReferenceDefinition) =
            rewriteDestination(definition, definition.destination, definition = true)

        override fun visit(text: Text) {
            if (generateSequence(text.parent) { it.parent }.any { it is Link || it is Image }) return
            val range = original.range(text) ?: return
            val raw = content.substring(range)
            for (match in wikiLink.findAll(raw)) {
                val start = range.first + match.range.first
                if (isEscaped(content, start)) continue
                val wikiContent = match.groupValues[1].replace("\\|", "|")
                val target = wikiContent.substringBefore('|').trim()
                val file = attachment(target, true) ?: continue
                val alias = wikiContent.substringAfter('|', "").trim()
                val image = match.value.startsWith('!')
                val label = alias.takeUnless { it.isEmpty() || image && it.matches(Regex("[0-9]+(?:x[0-9]+)?")) } ?: file.fileName
                replacements[start..(range.first + match.range.last)] =
                    (if (image) "!" else "") + "[" + markdownLabel(label) + "](<" + file.markdownPath + ">)"
            }
        }
    })
    val rewritten = StringBuilder(content)
    replacements.entries.sortedByDescending { it.key.first }.forEach { (range, value) ->
        rewritten.replace(range.first, range.last + 1, value)
    }
    return MigratedMarkdown(
        title = title.ifBlank { "未命名笔记" },
        content = rewritten.toString(),
        createdAt = frontMatter.timestamp("created", "created_at", "created_time", "date"),
        updatedAt = frontMatter.timestamp("updated", "updated_at", "updated_time", "modified", "modified_at"),
    )
}

private class MarkdownSource(private val text: String) {
    private val starts = listOf(0) + Regex("\r\n|\r|\n").findAll(text).map { it.range.last + 1 }.toList()

    fun range(node: Node): IntRange? {
        val first = node.sourceSpans.firstOrNull() ?: return null
        val last = node.sourceSpans.last()
        val start = starts.getOrNull(first.lineIndex)?.plus(first.columnIndex) ?: return null
        val end = starts.getOrNull(last.lineIndex)?.plus(last.columnIndex + last.length) ?: return null
        return (start until end).takeIf { start < end && end <= text.length }
    }
}

private fun plainHeading(heading: Heading): String = buildString {
    heading.accept(object : AbstractVisitor() {
        override fun visit(text: Text) { append(text.literal) }
        override fun visit(code: Code) { append(code.literal) }
        override fun visit(softLineBreak: SoftLineBreak) { append(' ') }
        override fun visit(hardLineBreak: HardLineBreak) { append(' ') }
    })
}.trim()

private fun closingLinkLabel(text: String, range: IntRange): Int? {
    var index = range.first
    if (text[index] == '!') index++
    if (text.getOrNull(index) != '[') return null
    var depth = 1
    index++
    while (index <= range.last && depth > 0) {
        when (text[index]) {
            '\\' -> index++
            '\u0060' -> {
                val run = text.substring(index).takeWhile { it == '\u0060' }.length
                val closing = text.indexOf("\u0060".repeat(run), index + run)
                if (closing in (index + run)..range.last) index = closing + run - 1
            }
            '[' -> depth++
            ']' -> depth--
        }
        index++
    }
    return (index - 1).takeIf { depth == 0 }
}

private fun linkDestinationRange(text: String, range: IntRange, definition: Boolean): IntRange? {
    var index = (closingLinkLabel(text, range) ?: return null) + 1
    if (index > range.last || text[index] != if (definition) ':' else '(') return null
    index++
    while (index <= range.last && text[index].isWhitespace()) index++
    val start = index
    if (text.getOrNull(index) == '<') {
        index++
        while (index <= range.last) {
            if (text[index] == '\\') index++
            else if (text[index] == '>') return start..index
            index++
        }
    } else {
        var depth = 0
        while (index <= range.last) {
            when {
                text[index] == '\\' -> index++
                text[index] == '(' -> depth++
                text[index] == ')' -> if (depth == 0) break else depth--
                text[index].isWhitespace() -> break
            }
            index++
        }
        if (index > start) return start until index
    }
    return null
}

private fun isEscaped(text: String, offset: Int): Boolean {
    var index = offset - 1
    while (index >= 0 && text[index] == '\\') index--
    return (offset - index - 1) % 2 == 1
}

private data class FrontMatter(val end: Int = 0, val properties: Map<String, String> = emptyMap()) {
    fun timestamp(vararg keys: String): Long? = keys.firstNotNullOfOrNull { key ->
        properties[key]?.let(::parseImportTime)
    }
}

/** Read common scalar metadata without discarding YAML tags or other application-specific fields. */
private fun readFrontMatter(source: String): FrontMatter {
    val opening = Regex("""\A---[ \t]*(?:\r\n|\n|\r)""").find(source) ?: return FrontMatter()
    val closing = Regex("""(?m)^(?:---|\.\.\.)[ \t]*(?:\r\n|\n|\r|$)""").find(source, opening.range.last + 1)
        ?: return FrontMatter()
    val raw = source.substring(opening.range.last + 1, closing.range.first)
    val properties = Regex("""(?m)^([A-Za-z_][A-Za-z0-9_-]*):[ \t]*(.*)$""").findAll(raw).mapNotNull { match ->
        val value = match.groupValues[2].trim()
        val scalar = when {
            value.startsWith('"') && value.endsWith('"') -> runCatching { SyncJson.json.decodeFromString<String>(value) }.getOrNull()
            value.startsWith('\'') && value.endsWith('\'') && value.length >= 2 -> value.drop(1).dropLast(1).replace("''", "'")
            value in setOf("|", ">", "null", "~", "") || value.startsWith('[') || value.startsWith('{') -> null
            else -> value.substringBefore(" #").trimEnd()
        }
        scalar?.let { match.groupValues[1].lowercase(Locale.ROOT) to it }
    }.toMap()
    return FrontMatter(closing.range.last + 1, properties)
}

private fun parseImportTime(value: String): Long? {
    val number = value.toLongOrNull()
    if (number != null) {
        if (number < 0 || number == Long.MAX_VALUE) return null
        return if (number < 10_000_000_000L) number * 1000 else number
    }
    val iso = value.replaceFirst(' ', 'T')
    return listOf<() -> Long>(
        { Instant.parse(iso).toEpochMilli() },
        { OffsetDateTime.parse(iso).toInstant().toEpochMilli() },
        { LocalDateTime.parse(iso).toInstant(ZoneOffset.UTC).toEpochMilli() },
        { LocalDate.parse(iso).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli() },
    ).firstNotNullOfOrNull { parse -> runCatching(parse).getOrNull()?.takeIf { it >= 0 && it < Long.MAX_VALUE } }
}
