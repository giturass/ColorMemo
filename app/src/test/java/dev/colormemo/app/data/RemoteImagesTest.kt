package dev.colormemo.app.data

import dev.colormemo.core.model.NoteAttachment
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteImagesTest {
    @Test
    fun acceptsHttpsAndRejectsUnsupportedSchemesCredentialsAndLineBreaks() {
        assertEquals("https://example.com/image.png?a=1&b=2", remoteImageUrl("  https://example.com/image.png?a=1&b=2  ").toString())
        listOf("", "image.png", "http://example.com/a.png", "file:///image.png", "content://image/1", "data:image/png;base64,a",
            "https://user:password@example.com/a.png", "https://example.com/a\nb.png").forEach { address ->
            assertThrows(IllegalArgumentException::class.java) { remoteImageUrl(address) }
        }
    }

    @Test
    fun downloadsBytesAndClosesBodyWithoutSendingCredentials() = runBlocking {
        val body = TrackingBody(byteArrayOf(1, 2, 3))
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            assertEquals("image/*", chain.request().header("Accept"))
            assertEquals(null, chain.request().header("Authorization"))
            assertEquals(null, chain.request().header("Cookie"))
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(body).build()
        }.build()
        assertArrayEquals(byteArrayOf(1, 2, 3), RemoteImageDownloader(client).download(remoteImageUrl("https://example.com/a.png")))
        assertTrue(body.closed)
    }

    @Test
    fun rejectsHttpErrorsAndBothDeclaredAndStreamingOversizeBodies() {
        val maximum = NoteAttachment.MAX_FILE_BYTES
        for ((code, body) in listOf(
            404 to TrackingBody(byteArrayOf(1)),
            200 to TrackingBody(byteArrayOf(1), maximum + 1L),
            200 to TrackingBody(ByteArray(maximum + 1), -1),
        )) {
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("Test").body(body).build()
            }.build()
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { RemoteImageDownloader(client).download(remoteImageUrl("https://example.com/a.png")) }
            }
            assertTrue(body.closed)
        }
    }

    @Test
    fun coroutineCancellationCancelsTheHttpCall() = runBlocking {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        var canceled = false
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            started.countDown()
            try {
                check(release.await(5, TimeUnit.SECONDS))
                canceled = chain.call().isCanceled()
                throw IOException("Canceled")
            } finally {
                finished.countDown()
            }
        }.build()
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            RemoteImageDownloader(client).download(remoteImageUrl("https://example.com/a.png"))
        }
        try {
            assertTrue(started.await(5, TimeUnit.SECONDS))
            job.cancelAndJoin()
            assertFalse(job.isActive)
        } finally {
            release.countDown()
        }
        assertTrue(finished.await(5, TimeUnit.SECONDS))
        assertTrue(canceled)
    }

    private class TrackingBody(bytes: ByteArray, private val length: Long = bytes.size.toLong()) : ResponseBody() {
        private val buffer = Buffer().write(bytes)
        var closed = false
        override fun contentType() = null
        override fun contentLength() = length
        override fun source(): BufferedSource = buffer
        override fun close() { closed = true; super.close() }
    }
}
