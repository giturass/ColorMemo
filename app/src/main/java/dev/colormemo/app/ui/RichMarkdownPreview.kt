package dev.colormemo.app.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.annotation.Keep
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.colormemo.app.data.decodeNoteImage
import dev.colormemo.app.data.remoteImageDownloader
import dev.colormemo.app.data.remoteImageUrl
import dev.colormemo.core.model.NoteAttachment
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Composable
internal fun RichMarkdownPreview(
    markdown: String,
    modifier: Modifier,
    attachments: List<NoteAttachment>,
    onOpenImage: (NoteAttachment) -> Unit,
    onToggleTask: ((MarkdownTaskMarker) -> Unit)?,
) {
    val document = remember(markdown, attachments) { RichMarkdownDocument(markdown, attachments) }
    val currentDocument = rememberUpdatedState(document)
    val currentOnOpen = rememberUpdatedState(onOpenImage)
    val currentOnToggle = rememberUpdatedState(onToggleTask)
    val parentScope = rememberCoroutineScope()
    val images = remember(attachments, parentScope) { MarkdownWebImages(parentScope) }
    DisposableEffect(images) { onDispose { images.close() } }
    var height by remember { mutableIntStateOf(32) }
    val foreground = MaterialTheme.colorScheme.onSurface
    val link = MaterialTheme.colorScheme.primary
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val fontScale = LocalDensity.current.fontScale
    val payload = remember(document, foreground, link, dark, fontScale, onToggleTask != null) {
        buildJsonObject {
            put("id", document.id)
            put("markdown", markdown)
            put("dark", dark)
            put("foreground", "#%06x".format(foreground.toArgb() and 0xffffff))
            put("linkColor", "#%06x".format(link.toArgb() and 0xffffff))
            put("fontScale", fontScale)
            put("editable", onToggleTask != null)
            put("tasks", buildJsonArray {
                document.tasks.forEach { task -> add(buildJsonObject {
                    put("line", task.line)
                    put("offset", task.marker.offset)
                    put("checked", task.marker.checked)
                }) }
            })
            put("images", buildJsonArray {
                document.images.forEachIndexed { index, image -> add(buildJsonObject {
                    put("source", image.source)
                    put("url", document.imageUrl(index))
                    put("local", image.attachment != null)
                    put("index", index)
                }) }
            })
        }.toString()
    }
    AndroidView(
        modifier = modifier.fillMaxWidth().height(height.dp),
        factory = { context ->
            MarkdownWebView(context,
                onHeight = { id, value -> if (id == currentDocument.value.id) height = value.coerceIn(24, 60_000) },
                onTask = { doc, marker -> if (doc === currentDocument.value) currentOnToggle.value?.invoke(marker) },
                onImage = { doc, image -> if (doc === currentDocument.value) currentOnOpen.value(image) },
            )
        },
        update = { view -> view.show(document, images, payload, onToggleTask != null) },
        onRelease = { view -> view.release() },
    )
}

/** Only explicitly referenced images use the existing bounded HTTPS downloader and EXIF decoder. */
private class MarkdownWebImages(parent: CoroutineScope) {
    private val scope = CoroutineScope(parent.coroutineContext + Job(parent.coroutineContext[Job]))
    private val cache = ConcurrentHashMap<String, Deferred<ByteArray?>>()
    private val slots = Semaphore(2)

    fun load(image: RichMarkdownImage): ByteArray? = runBlocking {
        cache.computeIfAbsent(image.attachment?.sha256 ?: image.source) {
            scope.async(Dispatchers.IO) {
                slots.withPermit {
                    try {
                        val bytes = image.attachment?.bytes()
                            ?: remoteImageDownloader.download(remoteImageUrl(image.source))
                        val bitmap = decodeNoteImage(bytes, 768) ?: return@withPermit null
                        try {
                            ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
                        } finally { bitmap.recycle() }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) { null }
                }
            }
        }.await()
    }

    fun close() { scope.cancel(); cache.clear() }
}

@Keep
private class MarkdownSizeBridge(private val resize: (String, Int) -> Unit) {
    @JavascriptInterface
    fun resize(id: String, height: Int) = resize.invoke(id, height)
}

// Created by Compose only. Scripts are bundled assets; note HTML and external requests are blocked.
@SuppressLint("SetJavaScriptEnabled", "ViewConstructor")
private class MarkdownWebView(
    context: Context,
    onHeight: (String, Int) -> Unit,
    private val onTask: (RichMarkdownDocument, MarkdownTaskMarker) -> Unit,
    private val onImage: (RichMarkdownDocument, NoteAttachment) -> Unit,
) : WebView(context) {
    @Volatile private var document: RichMarkdownDocument? = null
    @Volatile private var images: MarkdownWebImages? = null
    private var editable = false
    private var ready = false
    private var payload = ""
    private var released = false

    init {
        setBackgroundColor(android.graphics.Color.TRANSPARENT)
        isVerticalScrollBarEnabled = false
        settings.apply {
            javaScriptEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            domStorageEnabled = false
            blockNetworkLoads = true
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
            textZoom = 100
        }
        // The bridge can report size only. Task/image actions require a main-frame user gesture.
        addJavascriptInterface(MarkdownSizeBridge { id, height ->
            post { if (!released) onHeight(id, height) }
        }, "ColorMemoSize")
        webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                val uri = request.url
                if (request.method != "GET" || uri.scheme != "https" || uri.authority != "appassets.androidplatform.net") return missing()
                val path = uri.path.orEmpty()
                if (path.startsWith("/markdown/") && path.none { it == '\\' } && ".." !in path) {
                    val type = when (path.substringAfterLast('.')) {
                        "html" -> "text/html"
                        "js" -> "application/javascript"
                        "css" -> "text/css"
                        "woff2" -> "font/woff2"
                        "woff" -> "font/woff"
                        "ttf" -> "font/ttf"
                        else -> return missing()
                    }
                    return runCatching { WebResourceResponse(type, "UTF-8", context.assets.open(path.drop(1))) }.getOrElse { missing() }
                }
                val doc = document ?: return missing()
                val index = doc.images.indices.firstOrNull { doc.imageUrl(it) == uri.toString() } ?: return missing()
                return try {
                    val bytes = images?.load(doc.images[index]) ?: return missing()
                    WebResourceResponse("image/png", null, ByteArrayInputStream(bytes))
                } catch (_: Exception) { missing() }
            }

            override fun onPageFinished(view: WebView, url: String) {
                if (url == MARKDOWN_PAGE && !released) { ready = true; render() }
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (!request.isForMainFrame || !request.hasGesture()) return true
                val uri = request.url
                val doc = document ?: return true
                if (uri.scheme == "https" && uri.authority == "appassets.androidplatform.net") {
                    if (uri.getQueryParameter("id") != doc.id) return true
                    when (uri.path) {
                        "/action/task" -> if (editable) {
                            val offset = uri.getQueryParameter("offset")?.toIntOrNull() ?: return true
                            val checked = uri.getQueryParameter("checked")?.toBooleanStrictOrNull() ?: return true
                            doc.task(offset, checked)?.let { onTask(doc, it) }
                        }
                        "/action/image" -> {
                            val index = uri.getQueryParameter("index")?.toIntOrNull() ?: return true
                            doc.images.getOrNull(index)?.attachment?.let { onImage(doc, it) }
                        }
                    }
                } else if (uri.scheme in setOf("https", "http", "mailto")) {
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }
                return true
            }
        }
        loadUrl(MARKDOWN_PAGE)
    }

    fun show(doc: RichMarkdownDocument, imageLoader: MarkdownWebImages, data: String, canEdit: Boolean) {
        document = doc
        images = imageLoader
        editable = canEdit
        if (payload != data) { payload = data; render() }
    }

    private fun render() {
        if (ready && payload.isNotEmpty() && !released) evaluateJavascript(
            """(function(data) {
                if (window.ColorMemoMarkdown) { window.ColorMemoMarkdown.render(data); return; }
                var content = document.getElementById('content');
                content.style.whiteSpace = 'pre-wrap';
                content.textContent = '预览组件无法加载，请更新 Android System WebView。\n\n' + data.markdown;
                window.ColorMemoSize.resize(data.id, Math.ceil(content.getBoundingClientRect().height));
            })($payload)""".trimIndent(), null,
        )
    }

    fun release() {
        released = true
        stopLoading()
        removeJavascriptInterface("ColorMemoSize")
        document = null
        images = null
        destroy()
    }

    private fun missing() = WebResourceResponse("text/plain", "UTF-8", 404, "Not Found", emptyMap(), ByteArrayInputStream(byteArrayOf()))
}
