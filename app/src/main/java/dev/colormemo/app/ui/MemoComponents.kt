package dev.colormemo.app.ui

import android.util.TypedValue
import android.widget.TextView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.colormemo.app.ui.theme.containerColor
import dev.colormemo.app.ui.theme.displayName
import dev.colormemo.core.model.NoteColor
import dev.colormemo.core.model.NoteAttachment
import io.noties.markwon.html.HtmlPlugin
import io.noties.markwon.html.tag.UnderlineHandler
import io.noties.markwon.Markwon
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TablePlugin
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel

@Composable
internal fun MemoBrandMark(modifier: Modifier = Modifier, size: Dp = 38.dp) {
    val ink = MaterialTheme.colorScheme.primary
    val mint = NoteColor.MINT.containerColor()
    val peach = NoteColor.PEACH.containerColor()
    Canvas(modifier.size(size)) {
        val width = this.size.width
        val height = this.size.height
        rotate(-13f, Offset(width * 0.5f, height * 0.55f)) {
            drawRoundRect(
                color = peach,
                topLeft = Offset(width * 0.12f, height * 0.18f),
                size = Size(width * 0.62f, height * 0.71f),
                cornerRadius = CornerRadius(width * 0.1f),
            )
        }
        rotate(8f, Offset(width * 0.55f, height * 0.5f)) {
            drawRoundRect(
                color = mint,
                topLeft = Offset(width * 0.28f, height * 0.08f),
                size = Size(width * 0.6f, height * 0.72f),
                cornerRadius = CornerRadius(width * 0.1f),
            )
            drawLine(ink, Offset(width * 0.4f, height * 0.31f), Offset(width * 0.74f, height * 0.31f), width * 0.035f)
            drawLine(ink, Offset(width * 0.4f, height * 0.43f), Offset(width * 0.69f, height * 0.43f), width * 0.035f)
            drawLine(ink, Offset(width * 0.4f, height * 0.55f), Offset(width * 0.61f, height * 0.55f), width * 0.035f)
        }
    }
}

@Composable
internal fun ColorPalette(
    selected: NoteColor,
    onSelect: (NoteColor) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyRow(modifier = modifier, contentPadding = PaddingValues(horizontal = 12.dp)) {
        items(NoteColor.entries, key = { it.name }) { color ->
            val isSelected = color == selected
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .selectable(selected = isSelected, role = Role.RadioButton, onClick = { onSelect(color) })
                    .semantics { contentDescription = "${color.displayName}卡片" }
                    .padding(5.dp)
                    .border(
                        width = if (isSelected) 2.dp else 1.dp,
                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                        shape = CircleShape,
                    )
                    .padding(3.dp)
                    .background(color.containerColor(), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                if (isSelected) {
                    Icon(Icons.Outlined.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

@Composable
internal fun MarkdownPreview(
    markdown: String,
    modifier: Modifier = Modifier,
    attachments: List<NoteAttachment> = emptyList(),
    onOpenImage: (NoteAttachment) -> Unit = {},
    onToggleTask: ((MarkdownTaskMarker) -> Unit)? = null,
) {
    val context = LocalContext.current
    val foreground = MaterialTheme.colorScheme.onSurface.toArgb()
    val linkColor = MaterialTheme.colorScheme.primary.toArgb()
    val checkMarkColor = MaterialTheme.colorScheme.onPrimary.toArgb()
    val scope = rememberCoroutineScope()
    // Toggling tasks keeps the same image cache for this preview's lifetime.
    val imageScope = remember(attachments, scope) {
        CoroutineScope(scope.coroutineContext + Job(scope.coroutineContext[Job]))
    }
    DisposableEffect(imageScope) {
        onDispose { imageScope.cancel() }
    }
    val currentOnOpen = rememberUpdatedState(onOpenImage)
    val currentMarkdown = rememberUpdatedState(markdown)
    val currentOnToggle = rememberUpdatedState(onToggleTask)
    val tasksEnabled = onToggleTask != null
    val markwon = remember(context, attachments, imageScope, tasksEnabled, linkColor, foreground, checkMarkColor) {
        Markwon.builder(context)
            .usePlugin(HtmlPlugin.create().excludeDefaults(true).addHandler(UnderlineHandler()))
            .usePlugin(StrikethroughPlugin.create())
            .usePlugin(TablePlugin.create(context))
            .usePlugin(MarkdownTasksPlugin(linkColor, foreground, checkMarkColor,
                if (tasksEnabled) { source, marker ->
                    if (currentMarkdown.value == source) currentOnToggle.value?.invoke(marker)
                } else null,
            ))
            .usePlugin(MarkdownImagesPlugin(context.resources, attachments, imageScope) { currentOnOpen.value(it) })
            .build()
    }
    val renderKey = remember(markdown, markwon) { markdown to markwon }
    AndroidView(
        modifier = modifier.fillMaxWidth(),
        factory = { androidContext ->
            TextView(androidContext).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                setLineSpacing(0f, 1.4f)
                setTextIsSelectable(true)
                movementMethod = MarkdownTaskMovementMethod()
                setPadding(0, 0, 0, 0)
            }
        },
        update = { textView ->
            textView.setTextColor(foreground)
            textView.setLinkTextColor(linkColor)
            // Refresh spans when attachments change, even if the text is unchanged.
            if (textView.tag != renderKey) {
                markwon.setMarkdown(textView, markdown)
                textView.tag = renderKey
            }
        },
    )
}

internal fun formatReminder(timestamp: Long): String {
    val time = Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault())
    val day = when (time.toLocalDate()) {
        LocalDate.now() -> "今天"
        LocalDate.now().plusDays(1) -> "明天"
        else -> time.format(DateTimeFormatter.ofPattern("M月d日", Locale.SIMPLIFIED_CHINESE))
    }
    return "$day ${time.format(DateTimeFormatter.ofPattern("HH:mm"))}"
}

internal fun formatModified(timestamp: Long): String =
    Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("yyyy年M月d日 HH:mm", Locale.SIMPLIFIED_CHINESE))

internal fun markdownSummary(content: String): String = content.take(1_000)
    .replace(Regex("(?m)^\\s*[-*+] \\[ \\]\\s*"), "☐ ")
    .replace(Regex("(?m)^\\s*[-*+] \\[[xX]\\]\\s*"), "☑ ")
    .replace(Regex("(?m)^\\s{0,3}#{1,6}\\s+"), "")
    .replace(Regex("!?\\[([^]\\n]*)]\\([^\\n)]*\\)"), "$1")
    .replace(Regex("(?m)^\\s*```[^\\n]*"), "")
    .replace("**", "")
    .replace("__", "")
    .replace("~~", "")
    .replace(Regex("(?i)</?u>"), "")
    .trim()
