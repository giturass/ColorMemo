package dev.colormemo.app.data

import android.content.Context
import dev.colormemo.core.model.NoteAttachment
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

internal fun remoteImageUrl(value: String): HttpUrl {
    val text = value.trim()
    val url = text.toHttpUrlOrNull()
    require(text.isNotEmpty() && text.none { it.isISOControl() } && url != null && url.isHttps &&
        url.username.isEmpty() && url.password.isEmpty()) { "请输入有效的 HTTPS 图片地址。" }
    return url
}

/** Image requests use their own client, without sync credentials or cookies. */
internal class RemoteImageDownloader(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(40, TimeUnit.SECONDS)
        .followSslRedirects(false)
        .build(),
) {
    suspend fun download(url: HttpUrl): ByteArray = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(Request.Builder().url(url).header("Accept", "image/*").build())
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    val bytes = response.use {
                        require(it.isSuccessful) { "无法下载图片（HTTP ${it.code}）。" }
                        val body = requireNotNull(it.body) { "图片内容为空。" }
                        require(body.contentLength() <= NoteAttachment.MAX_FILE_BYTES) { "单张图片最多 5 MiB。" }
                        body.byteStream().use { input ->
                            val output = ByteArrayOutputStream()
                            val buffer = ByteArray(8192)
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                require(output.size().toLong() + count <= NoteAttachment.MAX_FILE_BYTES) { "单张图片最多 5 MiB。" }
                                output.write(buffer, 0, count)
                            }
                            output.toByteArray()
                        }
                    }
                    continuation.resume(bytes)
                } catch (error: Exception) {
                    continuation.resumeWithException(error)
                }
            }
        })
    }
}

internal val remoteImageDownloader = RemoteImageDownloader()

internal suspend fun readRemoteImageAttachment(context: Context, address: String): NoteAttachment {
    val url = remoteImageUrl(address)
    val bytes = remoteImageDownloader.download(url)
    val mimeType = imageMimeType(bytes)
    val preview = requireNotNull(decodeNoteImage(bytes, 384)) { "地址返回的内容无法解码为图片。" }
    preview.recycle()
    val name = url.pathSegments.lastOrNull()?.takeIf { it.isNotBlank() } ?: "远程图片"
    return NoteAttachment.create(name, mimeType, bytes).also { attachmentStore(context).persist(it) }
}
