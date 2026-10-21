package dev.colormemo.app.ui

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.Layout
import android.text.Selection
import android.text.Spannable
import android.text.Spanned
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.TextView
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.MarkwonVisitor
import io.noties.markwon.ext.tasklist.TaskListDrawable
import io.noties.markwon.ext.tasklist.TaskListSpan
import org.commonmark.parser.IncludeSourceSpans
import org.commonmark.parser.Parser
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

internal class MarkdownTasksPlugin(
    private val checkedColor: Int,
    private val uncheckedColor: Int,
    private val checkMarkColor: Int,
    private val onToggle: ((String, MarkdownTaskMarker) -> Unit)?,
) : AbstractMarkwonPlugin() {
    private var source = ""

    override fun processMarkdown(markdown: String): String {
        source = markdown
        return markdown
    }

    override fun configureParser(builder: Parser.Builder) {
        builder.includeSourceSpans(IncludeSourceSpans.BLOCKS)
            .postProcessor { replaceMarkdownTaskItems(it, source) }
    }

    override fun configureVisitor(builder: MarkwonVisitor.Builder) {
        builder.on(MarkdownTaskItem::class.java) { visitor, item ->
            val start = visitor.length()
            val renderedSource = source
            visitor.visitChildren(item)
            // Empty tasks still need a span range so their checkbox is drawn and accessible.
            if (visitor.length() == start) visitor.builder().append('\u00a0')
            val drawable = TaskListDrawable(checkedColor, uncheckedColor, checkMarkColor)
            val click = onToggle?.let { toggle -> TaskCheckboxClick { toggle(renderedSource, item.marker) } }
            val checkbox = object : TaskListSpan(visitor.configuration().theme(), drawable, item.marker.checked) {
                override fun drawLeadingMargin(
                    canvas: Canvas, paint: Paint, x: Int, dir: Int, top: Int, baseline: Int,
                    bottom: Int, text: CharSequence, start: Int, end: Int, first: Boolean, layout: Layout,
                ) {
                    super.drawLeadingMargin(canvas, paint, x, dir, top, baseline, bottom, text, start, end, first, layout)
                    if (first && start == (text as Spanned).getSpanStart(this)) {
                        val edge = x + dir * getLeadingMargin(true)
                        click?.bounds?.set(min(x, edge).toFloat(), top.toFloat(), max(x, edge).toFloat(), bottom.toFloat())
                    }
                }
            }
            visitor.setSpans(start, checkbox)
            if (click != null) {
                // A clickable label gives keyboard and accessibility users a named action.
                // Touch handling below restricts toggling to the checkbox margin.
                var end = start
                while (end < visitor.length() && visitor.builder()[end] != '\n') end++
                visitor.builder().setSpan(click, start, max(start + 1, end))
            }
            if (visitor.hasNext(item)) visitor.ensureNewLine()
        }
    }
}

private class TaskCheckboxClick(private val toggle: () -> Unit) : ClickableSpan() {
    val bounds = RectF()
    override fun onClick(widget: View) = toggle()
    override fun updateDrawState(ds: TextPaint) = Unit
}

/** Keeps links/images clickable and lets the parent scroll without toggling a dragged checkbox. */
internal class MarkdownTaskMovementMethod : LinkMovementMethod() {
    private var pressed: ClickableSpan? = null
    private var downX = 0f
    private var downY = 0f

    override fun onTouchEvent(widget: TextView, buffer: Spannable, event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                pressed = hit(widget, buffer, event)
                return pressed != null
            }
            MotionEvent.ACTION_MOVE -> {
                val slop = ViewConfiguration.get(widget.context).scaledTouchSlop
                if (abs(event.x - downX) > slop || abs(event.y - downY) > slop) pressed = null
            }
            MotionEvent.ACTION_UP -> {
                val target = pressed
                pressed = null
                if (target != null && target === hit(widget, buffer, event) &&
                    event.eventTime - event.downTime < ViewConfiguration.getLongPressTimeout()
                ) {
                    Selection.removeSelection(buffer)
                    target.onClick(widget)
                    return true
                }
            }
            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> pressed = null
        }
        return pressed != null
    }

    private fun hit(widget: TextView, buffer: Spannable, event: MotionEvent): ClickableSpan? {
        val x = event.x - widget.totalPaddingLeft + widget.scrollX
        val y = event.y - widget.totalPaddingTop + widget.scrollY
        buffer.getSpans(0, buffer.length, TaskCheckboxClick::class.java)
            .firstOrNull { it.bounds.contains(x, y) }?.let { return it }
        val layout = widget.layout ?: return null
        if (y < 0 || y >= layout.height) return null
        val line = layout.getLineForVertical(y.toInt())
        if (x < layout.getLineLeft(line) || x > layout.getLineRight(line)) return null
        val offset = layout.getOffsetForHorizontal(line, x)
        return buffer.getSpans(offset, offset, ClickableSpan::class.java)
            .firstOrNull { it !is TaskCheckboxClick }
    }
}
