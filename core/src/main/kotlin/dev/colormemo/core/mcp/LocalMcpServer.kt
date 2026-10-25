package dev.colormemo.core.mcp

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Job
import kotlinx.serialization.json.JsonNull
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Small, bounded HTTP/1.1 endpoint. One request per connection; all responses are non-cacheable. */
class LocalMcpServer(
    private val protocol: McpProtocol,
    requestedPort: Int = PORT,
    private val requestTimeoutMillis: Long = 10_000,
    val token: String = generateToken(),
) : Closeable {
    private val authorization = "Bearer $token".toByteArray(Charsets.UTF_8)
    private val active = AtomicBoolean(false)
    private val listener = ServerSocket()
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()
    private val workers = ThreadPoolExecutor(4, 4, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(4),
        { runnable -> Thread(runnable, "colormemo-mcp-request").apply { isDaemon = true } })
    private val deadlines = ScheduledThreadPoolExecutor(1) { runnable ->
        Thread(runnable, "colormemo-mcp-timeouts").apply { isDaemon = true }
    }.apply { removeOnCancelPolicy = true }
    private val listenerThread = Thread(::acceptConnections, "colormemo-mcp-listener").apply { isDaemon = true }
    val port: Int get() = listener.localPort

    init {
        try {
            require(token.matches(Regex("[A-Za-z0-9_-]{43}"))) { "Invalid MCP token" }
            require(requestTimeoutMillis in 1L..10_000L)
            listener.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), requestedPort), 8)
        } catch (error: Exception) {
            listener.close()
            workers.shutdownNow()
            deadlines.shutdownNow()
            throw error
        }
    }

    fun start() {
        check(!listener.isClosed && active.compareAndSet(false, true))
        listenerThread.start()
    }

    private fun acceptConnections() {
        while (active.get()) {
            val socket = try { listener.accept() } catch (_: Exception) { break }
            if (sockets.size >= 8) { socket.close(); continue }
            sockets.add(socket)
            val requestJob = Job()
            var deadline: ScheduledFuture<*>? = null
            try {
                // Covers slow headers, bodies AND clients that stop reading responses.
                deadline = deadlines.schedule({
                    requestJob.cancel()
                    runCatching { socket.close() }
                }, requestTimeoutMillis, TimeUnit.MILLISECONDS)
                workers.execute {
                    try { socket.use { serve(it, requestJob) } } catch (_: Exception) {
                        // Never log request bodies, credentials, note IDs, titles or errors with data.
                    } finally {
                        deadline?.cancel(false)
                        requestJob.cancel()
                        sockets.remove(socket)
                    }
                }
            } catch (_: RejectedExecutionException) {
                deadline?.cancel(false)
                requestJob.cancel()
                sockets.remove(socket)
                socket.close()
            }
        }
    }

    private fun serve(socket: Socket, requestJob: Job) {
        if (!active.get() || !requestJob.isActive || !socket.inetAddress.isLoopbackAddress) return
        socket.soTimeout = 5_000
        val input = BufferedInputStream(socket.getInputStream())
        try {
            val requestLine = line(input).split(' ')
            if (requestLine.size != 3 || requestLine[2] != "HTTP/1.1") throw HttpFailure(400)
            val headers = mutableMapOf<String, String>()
            var headerBytes = 0
            while (true) {
                val line = line(input)
                headerBytes += line.length + 2
                if (headerBytes > 16_384) throw HttpFailure(431)
                if (line.isEmpty()) break
                if (headers.size >= 32) throw HttpFailure(431)
                val colon = line.indexOf(':')
                if (colon <= 0 || line.substring(0, colon).any { !it.isLetterOrDigit() && it != '-' }) throw HttpFailure(400)
                val name = line.substring(0, colon).lowercase(Locale.ROOT)
                if (headers.put(name, line.substring(colon + 1).trim()) != null) throw HttpFailure(400)
            }
            if (headers["host"] !in setOf("127.0.0.1:$port", "localhost:$port") || "origin" in headers) throw HttpFailure(403)
            val provided = headers["authorization"].orEmpty().toByteArray(Charsets.UTF_8)
            if (!MessageDigest.isEqual(authorization, provided)) throw HttpFailure(401)
            if (requestLine[1] != "/mcp") throw HttpFailure(404)
            if (headers["mcp-protocol-version"]?.let { it !in McpProtocol.VERSIONS } == true) throw HttpFailure(400)
            if (requestLine[0] != "POST") throw HttpFailure(405)
            if (headers["content-type"]?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT) != "application/json") throw HttpFailure(415)
            val accepts = headers["accept"].orEmpty().split(',').map { it.trim().substringBefore(';').lowercase(Locale.ROOT) }
            if ("application/json" !in accepts || "text/event-stream" !in accepts) throw HttpFailure(406)
            if ("transfer-encoding" in headers || "content-encoding" in headers || "expect" in headers) throw HttpFailure(400)
            val lengthHeader = headers["content-length"] ?: throw HttpFailure(411)
            if (lengthHeader.isEmpty() || lengthHeader.any { it !in '0'..'9' }) throw HttpFailure(400)
            val length = lengthHeader.toLongOrNull() ?: throw HttpFailure(413)
            if (length !in 1..MAX_REQUEST_BYTES.toLong()) throw HttpFailure(413)
            val bytes = ByteArray(length.toInt())
            var offset = 0
            while (offset < bytes.size) {
                val read = input.read(bytes, offset, bytes.size - offset)
                if (read < 0) throw HttpFailure(400)
                offset += read
            }
            val body = try {
                Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
            } catch (_: Exception) { throw HttpFailure(400) }
            if (!active.get() || socket.isClosed) return
            val result = runBlocking(requestJob) { protocol.handle(body) }
            if (!active.get() || socket.isClosed) return
            val response = result?.toString()?.toByteArray(Charsets.UTF_8) ?: byteArrayOf()
            if (response.size > 2 * 1024 * 1024) throw HttpFailure(500)
            val status = when {
                result == null -> 202
                result["id"] == JsonNull && result["error"] != null -> 400
                else -> 200
            }
            respond(socket, status, response)
        } catch (error: HttpFailure) { respond(socket, error.status) }
    }

    private fun line(input: BufferedInputStream): String {
        val bytes = ByteArrayOutputStream()
        while (bytes.size() <= 8_192) {
            val value = input.read()
            if (value == '\r'.code) {
                if (input.read() != '\n'.code) throw HttpFailure(400)
                return bytes.toString("US-ASCII")
            }
            if (value !in 32..126) throw HttpFailure(400)
            bytes.write(value)
        }
        throw HttpFailure(431)
    }

    private fun respond(socket: Socket, status: Int, bytes: ByteArray = byteArrayOf()) {
        val extra = when (status) {
            401 -> "WWW-Authenticate: Bearer realm=\"ColorMemo\"\r\n"
            405 -> "Allow: POST\r\n"
            else -> ""
        }
        val header = "HTTP/1.1 $status ${if (status < 400) "OK" else "Error"}\r\n" +
            "Content-Type: application/json\r\nContent-Length: ${bytes.size}\r\n" +
            "Cache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\nConnection: close\r\n$extra\r\n"
        socket.getOutputStream().apply { write(header.toByteArray(Charsets.US_ASCII)); write(bytes); flush() }
    }

    override fun close() {
        active.set(false)
        runCatching { listener.close() }
        // close() wakes accept(), but the native listener can remain bound until that thread exits.
        // Drain it before returning so an immediate restart cannot race the old listener or sockets.
        if (Thread.currentThread() != listenerThread) {
            try { listenerThread.join() }
            catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        }
        sockets.forEach { runCatching { it.close() } }
        sockets.clear()
        workers.shutdownNow()
        deadlines.shutdownNow()
    }

    private class HttpFailure(val status: Int) : Exception()

    companion object {
        const val PORT = 39451
        const val URL = "http://127.0.0.1:$PORT/mcp"
        const val MAX_REQUEST_BYTES = 1024 * 1024

        fun generateToken(): String = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) })
    }
}
