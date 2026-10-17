package dev.colormemo.core.sync

import dev.colormemo.core.model.Note
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class SyncEngineTest {
    private lateinit var server: MockWebServer
    private val base = Note(id = "base", title = "记事", revision = "v1", deviceId = "a", createdAt = 1, updatedAt = 1)

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After fun tearDown() {
        server.shutdown()
    }

    @Test
    fun webDavMergesRemoteBeforeConditionalWrite() = runBlocking {
        val remote = base.copy(id = "remote", revision = "remote-version", content = "远端记事")
        server.enqueue(snapshot(listOf(remote), "\"remote-etag\""))
        server.enqueue(MockResponse().setResponseCode(204))
        val result = dav().sync(listOf(base))
        assertTrue(result.uploaded)
        assertEquals(setOf("base", "remote"), result.notes.map { it.id }.toSet())
        val get = request()
        assertEquals("GET", get.method)
        assertEquals("/dav/notes/colormemo-v1.json", get.path)
        assertEquals(Credentials.basic("alice", "secret", Charsets.UTF_8), get.getHeader("Authorization"))
        val put = request()
        assertEquals("PUT", put.method)
        assertEquals("\"remote-etag\"", put.getHeader("If-Match"))
        assertNull(put.getHeader("If-None-Match"))
        assertEquals(result.notes, SyncJson.decode(put.body.readByteArray()).notes)
    }

    @Test
    fun firstSnapshotUsesCreateOnlyConditionalWrite() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(MockResponse().setResponseCode(201))
        dav().sync(listOf(base))
        request()
        val put = request()
        assertEquals("*", put.getHeader("If-None-Match"))
        assertNull(put.getHeader("If-Match"))
    }

    @Test
    fun preconditionRaceRefetchesAndPreservesEveryDevice() = runBlocking {
        val one = base.copy(id = "remote-one", revision = "one")
        val two = base.copy(id = "remote-two", revision = "two")
        server.enqueue(snapshot(listOf(one), "\"etag-one\""))
        server.enqueue(MockResponse().setResponseCode(412))
        server.enqueue(snapshot(listOf(one, two), "\"etag-two\""))
        server.enqueue(MockResponse().setResponseCode(204))
        val result = dav().sync(listOf(base))
        assertEquals(setOf("base", "remote-one", "remote-two"), result.notes.map { it.id }.toSet())
        assertEquals("GET", request().method)
        assertEquals("\"etag-one\"", request().getHeader("If-Match"))
        assertEquals("GET", request().method)
        val retry = request()
        assertEquals("\"etag-two\"", retry.getHeader("If-Match"))
        assertEquals(result.notes, SyncJson.decode(retry.body.readByteArray()).notes)
    }

    @Test
    fun repeatedWriteContentionStopsWithoutUnconditionalFallback() = runBlocking {
        repeat(4) {
            server.enqueue(snapshot(emptyList(), "\"etag-$it\""))
            server.enqueue(MockResponse().setResponseCode(412))
        }
        val error = expectFailure { dav().sync(listOf(base)) }
        assertTrue(error.isRetryable)
        assertEquals(8, server.requestCount)
        repeat(4) {
            assertEquals("GET", request().method)
            assertTrue(request().getHeader("If-Match") != null)
        }
    }

    @Test
    fun readOnlyConnectionTestNeverWritesOrCreatesObjects() = runBlocking {
        server.enqueue(snapshot(listOf(base), "\"v1\""))
        val existing = dav().testConnection()
        assertTrue(existing.remoteExists)
        assertEquals(1, existing.noteCount)
        assertEquals("GET", request().method)
        server.enqueue(MockResponse().setResponseCode(404))
        val absent = dav().testConnection()
        assertFalse(absent.remoteExists)
        assertEquals(0, absent.noteCount)
        assertEquals("GET", request().method)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun alreadySynchronizedNotesDoNotCauseAnUnnecessaryPut() = runBlocking {
        server.enqueue(snapshot(listOf(base), "\"same\""))
        val result = dav().sync(listOf(base))
        assertFalse(result.uploaded)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun missingOrWeakEtagsRefuseToOverwrite() = runBlocking {
        listOf<String?>(null, "W/\"weak\"", "unquoted").forEach { etag ->
            val response = MockResponse().setBody(SyncJson.encode(SyncDocument(notes = listOf(base))))
            if (etag != null) response.setHeader("ETag", etag)
            server.enqueue(response)
            val error = expectFailure { dav().sync(listOf(base.copy(id = "local"))) }
            assertTrue(error is SyncProtocolException)
            assertEquals("GET", request().method)
        }
        assertEquals(3, server.requestCount)
    }

    @Test
    fun malformedOrFutureDocumentsAreNotOverwritten() = runBlocking {
        listOf("not json", "{\"schemaVersion\":999,\"notes\":[]}", "{}").forEach { body ->
            server.enqueue(MockResponse().setHeader("ETag", "\"v\"").setBody(body))
            assertTrue(expectFailure { dav().sync(listOf(base)) } is SyncProtocolException)
        }
        assertEquals(3, server.requestCount)
        repeat(3) { assertEquals("GET", request().method) }
    }

    @Test
    fun authenticationFailureDoesNotExposeResponseSecretsOrWrite() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(403).setBody("sensitive-provider-message"))
        val error = expectFailure { dav().sync(listOf(base)) }
        assertFalse(error.message.orEmpty().contains("sensitive-provider-message"))
        assertTrue(error.message.orEmpty().contains("403"))
        assertFalse(error.isRetryable)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun redirectsAreNotFollowedWithCredentialsEvenForCustomClient() = runBlocking {
        val other = MockWebServer()
        other.start()
        try {
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", other.url("/capture")))
            val engine = SyncEngine(davConfig(), OkHttpClient.Builder().followRedirects(true).build())
            assertTrue(expectFailure { engine.testConnection() }.message.orEmpty().contains("重定向"))
            assertEquals(0, other.requestCount)
            assertEquals(1, server.requestCount)
        } finally {
            other.shutdown()
        }
    }

    @Test
    fun r2UsesEncodedPathStyleUrlSignedPayloadAndAtomicCreate() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(MockResponse().setResponseCode(200))
        val config = RemoteConfig.S3(
            endpoint = server.url("/").toString(), bucket = "notes-bucket", accessKeyId = "test-access",
            secretAccessKey = "test-secret", region = "auto", prefix = "笔记/ideas : +", sessionToken = "session-token",
        )
        val result = SyncEngine(config).sync(listOf(base))
        assertTrue(result.uploaded)
        val get = request()
        assertEquals("/notes-bucket/%E7%AC%94%E8%AE%B0/ideas%20%3A%20%2B/colormemo-v1.json", get.path)
        assertTrue(get.getHeader("Authorization")!!.contains("/auto/s3/aws4_request"))
        val put = request()
        assertEquals("*", put.getHeader("If-None-Match"))
        assertEquals("session-token", put.getHeader("x-amz-security-token"))
        assertTrue(put.getHeader("Authorization")!!.contains("content-type;host;if-none-match;"))
        val payload = put.body.readByteArray()
        val hash = MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { "%02x".format(it) }
        assertEquals(hash, put.getHeader("x-amz-content-sha256"))
        assertEquals(result.notes, SyncJson.decode(payload).notes)
        assertFalse(config.toString().contains("test-secret"))
        assertFalse(davConfig().toString().contains("secret"))
    }

    @Test
    fun cancellationStopsTheInFlightHttpCall() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val client = OkHttpClient()
        val operation = async { SyncEngine(davConfig(), client).testConnection() }
        // Yield to async first; takeRequest itself is blocking.
        withTimeout(3_000) {
            while (server.requestCount == 0) delay(10)
        }
        operation.cancelAndJoin()
        withTimeout(3_000) {
            while (client.dispatcher.runningCallsCount() > 0) delay(10)
        }
        assertTrue(operation.isCancelled)
    }

    @Test
    fun productionHttpAndEmbeddedUrlCredentialsAreRejected() {
        assertThrows(SyncConfigurationException::class.java) {
            SyncEngine(RemoteConfig.WebDav("http://dav.example.com/notes/"))
        }
        assertThrows(SyncConfigurationException::class.java) {
            SyncEngine(RemoteConfig.WebDav("https://user:password@dav.example.com/notes/"))
        }
        assertThrows(SyncConfigurationException::class.java) {
            SyncEngine(RemoteConfig.WebDav("https://dav.example.com/notes/?token=secret"))
        }
    }

    private fun davConfig() = RemoteConfig.WebDav(server.url("/dav/notes/").toString(), "alice", "secret")
    private fun dav() = SyncEngine(davConfig())

    private fun snapshot(notes: List<Note>, etag: String) = MockResponse()
        .setResponseCode(200).setHeader("ETag", etag)
        .setBody(SyncJson.encode(SyncDocument(notes = notes)))

    private fun request(): RecordedRequest = server.takeRequest(2, TimeUnit.SECONDS)
        ?: throw AssertionError("Expected HTTP request was not sent")

    private suspend fun expectFailure(block: suspend () -> Any?): SyncException {
        try {
            block()
        } catch (error: SyncException) {
            return error
        }
        throw AssertionError("Expected a safe sync failure")
    }
}
