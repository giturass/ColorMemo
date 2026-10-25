package dev.colormemo.core.mcp

import kotlinx.serialization.json.JsonNull
import kotlinx.coroutines.awaitCancellation
import org.junit.Assert.*
import org.junit.Test
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class LocalMcpServerTest {
    @Test
    fun realHttpRoundTripSupportsInitializeDiscoveryAndNotification() {
        server { server, calls ->
            val initialized = send(server, body = """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18"}}""")
            assertEquals(200, initialized.status)
            assertTrue(initialized.body.contains("ColorMemo"))
            assertEquals(200, send(server).status)
            assertEquals(202, send(server, body = """{"jsonrpc":"2.0","method":"notifications/initialized"}""").status)
            assertEquals(405, send(server, method = "GET").status)
            assertEquals(405, send(server, method = "DELETE").status)
            assertEquals(0, calls.get())
            assertEquals(200, send(server, body = CREATE).status)
            assertEquals(1, calls.get())
        }
    }

    @Test
    fun tokenHostOriginAndVersionAreEnforcedBeforeAnyToolRuns() {
        server { server, calls ->
            assertEquals(401, send(server, token = "").status)
            assertEquals(401, send(server, token = "wrong").status)
            assertEquals(403, send(server, host = "evil.example:${server.port}").status)
            assertEquals(403, send(server, extra = "Origin: https://evil.example\r\n").status)
            assertEquals(403, send(server, extra = "Origin: null\r\n").status)
            assertEquals(403, send(server, extra = "Origin: http://localhost:${server.port}\r\n").status)
            assertEquals(400, send(server, extra = "MCP-Protocol-Version: 2099-01-01\r\n").status)
            assertEquals(404, send(server, path = "/mcp?token=${server.token}").status)
            assertEquals(404, send(server, path = "/backup").status)
            assertEquals(0, calls.get())
            assertEquals(200, send(server, body = CREATE, host = "localhost:${server.port}").status)
        }
    }

    @Test
    fun malformedAmbiguousAndOversizedHttpRequestsAreRejected() {
        server { server, calls ->
            assertEquals(400, send(server, body = CREATE, extra = "Content-Length: 2\r\n").status)
            assertEquals(400, send(server, body = CREATE, extra = "Authorization: Bearer ${server.token}\r\n").status)
            assertEquals(400, send(server, body = CREATE, extra = "Transfer-Encoding: chunked\r\n").status)
            assertEquals(400, send(server, body = CREATE, extra = "Content-Encoding: gzip\r\n").status)
            assertEquals(415, send(server, contentType = "text/plain").status)
            assertEquals(406, send(server, accept = "text/html").status)
            assertEquals(413, send(server, length = LocalMcpServer.MAX_REQUEST_BYTES + 1).status)
            assertEquals(431, send(server, extra = (1..40).joinToString("") { "X-Test-$it: a\r\n" }).status)
            assertEquals(400, raw(server, headers(server, 2) + byteArrayOf(0xc3.toByte(), 0x28)).status)
            assertEquals(400, send(server, body = "[]").status)
            assertEquals(400, send(server, body = """{"jsonrpc":"2.0","id":1,"result":{}}""").status)
            assertEquals(0, calls.get())
        }
    }

    @Test
    fun stoppingClosesTheListenerAndRestartAcceptsTheSavedToken() {
        val first = LocalMcpServer(McpProtocol { JsonNull }, 0)
        first.start()
        val port = first.port
        val token = first.token
        first.close()
        assertThrows(java.io.IOException::class.java) { Socket("127.0.0.1", port).close() }
        LocalMcpServer(McpProtocol { JsonNull }, port, token = token).use { next ->
            next.start()
            assertEquals(200, send(next, token = token).status)
        }
    }

    @Test
    fun resettingTheTokenRejectsTheOldCredential() {
        val token = LocalMcpServer.generateToken()
        LocalMcpServer(McpProtocol { JsonNull }, 0, token = token).use { first ->
            first.start()
            assertEquals(200, send(first, token = token).status)
        }
        LocalMcpServer(McpProtocol { JsonNull }, 0, token = LocalMcpServer.generateToken()).use { next ->
            next.start()
            assertNotEquals(token, next.token)
            assertEquals(401, send(next, token = token).status)
            assertEquals(200, send(next).status)
        }
    }

    @Test
    fun invalidSavedCredentialsCannotStartAServer() {
        for (token in listOf("", "short", "a".repeat(42) + "\n", "a".repeat(44))) {
            assertThrows(IllegalArgumentException::class.java) {
                LocalMcpServer(McpProtocol { JsonNull }, 0, token = token).close()
            }
        }
    }

    @Test
    fun requestDeadlineCancelsAnOperationWaitingForTheRepository() {
        val entered = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        LocalMcpServer(McpProtocol {
            entered.countDown()
            try { awaitCancellation() } finally { cancelled.countDown() }
        }, 0, requestTimeoutMillis = 500).use { server ->
            server.start()
            Socket("127.0.0.1", server.port).use { socket ->
                socket.soTimeout = 3_000
                socket.getOutputStream().apply { write(headers(server, CREATE.toByteArray().size) + CREATE.toByteArray()); flush() }
                assertTrue(entered.await(2, TimeUnit.SECONDS))
                assertEquals(-1, socket.getInputStream().read())
                assertTrue(cancelled.await(2, TimeUnit.SECONDS))
            }
        }
    }

    private fun server(test: (LocalMcpServer, AtomicInteger) -> Unit) {
        val calls = AtomicInteger()
        LocalMcpServer(McpProtocol { calls.incrementAndGet(); JsonNull }, 0).use {
            it.start()
            test(it, calls)
        }
    }

    private fun send(server: LocalMcpServer, body: String = LIST, token: String = server.token,
                     host: String = "127.0.0.1:${server.port}", method: String = "POST", path: String = "/mcp",
                     contentType: String = "application/json", accept: String = "application/json, text/event-stream",
                     extra: String = "", length: Int = body.toByteArray().size): Response {
        val header = "$method $path HTTP/1.1\r\nHost: $host\r\nAuthorization: Bearer $token\r\n" +
            "Content-Type: $contentType\r\nAccept: $accept\r\nContent-Length: $length\r\n$extra\r\n"
        return raw(server, header.toByteArray() + body.toByteArray())
    }

    private fun headers(server: LocalMcpServer, length: Int) =
        ("POST /mcp HTTP/1.1\r\nHost: 127.0.0.1:${server.port}\r\nAuthorization: Bearer ${server.token}\r\n" +
            "Content-Type: application/json\r\nAccept: application/json, text/event-stream\r\nContent-Length: $length\r\n\r\n").toByteArray()

    private fun raw(server: LocalMcpServer, bytes: ByteArray): Response = Socket("127.0.0.1", server.port).use { socket ->
        socket.soTimeout = 3_000
        socket.getOutputStream().apply { write(bytes); flush() }
        val response = socket.getInputStream().readBytes().toString(Charsets.UTF_8)
        assertTrue(response.contains("Cache-Control: no-store"))
        assertFalse(response.contains("Access-Control-Allow-Origin"))
        Response(response.substringBefore("\r\n").split(' ')[1].toInt(), response.substringAfter("\r\n\r\n"))
    }

    private data class Response(val status: Int, val body: String)

    companion object {
        private const val LIST = """{"jsonrpc":"2.0","id":1,"method":"tools/list"}"""
        private const val CREATE = """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"create_note","arguments":{"title":"x"}}}"""
    }
}
