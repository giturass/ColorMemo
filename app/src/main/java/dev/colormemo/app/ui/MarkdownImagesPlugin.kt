package dev.colormemo.app.ui

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.text.Spanned
import android.text.TextPaint
import android.text.style.ClickableSpan
import android.view.View
import android.widget.TextView
import dev.colormemo.app.data.decodeNoteImage
import dev.colormemo.app.data.isImage
import dev.colormemo.app.data.markdownPath
import dev.colormemo.core.model.NoteAttachment
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.MarkwonConfiguration
import io.noties.markwon.MarkwonSpansFactory
import io.noties.markwon.image.AsyncDrawable
import io.noties.markwon.image.AsyncDrawableLoader
import io.noties.markwon.image.AsyncDrawableScheduler
import io.noties.markwon.image.AsyncDrawableSpan
import io.noties.markwon.image.ImageProps
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import org.commonmark.node.Image

/** Resolves only images carried by this note; never opens URLs or arbitrary files. */
internal class MarkdownImagesPlugin(
    resources: Resources,
    attachments: List<NoteAttachment>,
    scope: CoroutineScope,
    private val onOpen: (NoteAttachment) -> Unit,
) : AbstractMarkwonPlugin() {
    private val images = attachments.filter { it.isImage }.associateBy { it.markdownPath }
    private val loader = object : AsyncDrawableLoader() {
        private val jobs = mutableMapOf<AsyncDrawable, Job>()
        private val bitmaps = mutableMapOf<String, Deferred<Bitmap?>>()
        private val decodeDispatcher = Dispatchers.Default.limitedParallelism(2)

        override fun load(drawable: AsyncDrawable) {
            val attachment = images[drawable.destination] ?: return
            cancel(drawable)
            val job = scope.launch(start = CoroutineStart.LAZY) {
                try {
                    // Multiple Markdown references share one bounded bitmap, not one decode per span.
                    val bitmap = bitmaps.getOrPut(attachment.sha256) {
                        scope.async(decodeDispatcher) {
                            try {
                                decodeNoteImage(attachment.bytes(), 768)
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                null
                            }
                        }
                    }.await()
                    if (bitmap != null && drawable.isAttached) {
                        val result = BitmapDrawable(resources, bitmap).apply {
                            // Markwon's size resolver reads bounds, not intrinsic dimensions.
                            setBounds(0, 0, intrinsicWidth, intrinsicHeight)
                        }
                        drawable.setResult(result)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // The alt text stays visible if an image cannot be decoded.
                } finally {
                    if (jobs[drawable] == coroutineContext[Job]) jobs.remove(drawable)
                }
            }
            jobs[drawable] = job
            job.start()
        }

        override fun cancel(drawable: AsyncDrawable) {
            jobs.remove(drawable)?.cancel()
        }

        override fun placeholder(drawable: AsyncDrawable): Drawable? = null
    }

    override fun configureConfiguration(builder: MarkwonConfiguration.Builder) {
        builder.asyncDrawableLoader(loader)
    }

    override fun configureSpansFactory(builder: MarkwonSpansFactory.Builder) {
        builder.setFactory(Image::class.java) { configuration, props ->
            val destination = ImageProps.DESTINATION.require(props)
            val attachment = images[destination]
            if (attachment == null) null else {
                val span = AsyncDrawableSpan(
                    configuration.theme(),
                    AsyncDrawable(destination, loader, configuration.imageSizeResolver(), null),
                    AsyncDrawableSpan.ALIGN_BOTTOM,
                    false,
                )
                if (ImageProps.REPLACEMENT_TEXT_IS_LINK.get(props) == true) span
                else arrayOf<Any>(span, object : ClickableSpan() {
                    override fun onClick(widget: View) = onOpen(attachment)
                    override fun updateDrawState(ds: TextPaint) { ds.isUnderlineText = false }
                })
            }
        }
    }

    override fun beforeSetText(textView: TextView, markdown: Spanned) {
        AsyncDrawableScheduler.unschedule(textView)
    }

    override fun afterSetText(textView: TextView) {
        AsyncDrawableScheduler.schedule(textView)
    }
}
