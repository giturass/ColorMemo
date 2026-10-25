package dev.colormemo.core.mcp

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class McpProtocolTest {
    @Test
    fun initializationNegotiatesVersionAndListsOnlyNarrowTools() = runBlocking {
        val protocol = McpProtocol { error("No data access during discovery") }
        val result = protocol.handle(request("initialize", """{"protocolVersion":"2099-01-01"}"""))!!["result"]!!.jsonObject
        assertEquals(McpProtocol.VERSION, result["protocolVersion"]!!.jsonPrimitive.content)
        assertEquals(setOf("tools"), result["capabilities"]!!.jsonObject.keys)
        val tools = protocol.handle(request("tools/list"))!!["result"]!!.jsonObject["tools"]!!.jsonArray
        assertEquals(setOf("list_notes", "read_note", "create_note", "update_note", "manage_note"), tools.map { it.jsonObject["name"]!!.jsonPrimitive.content }.toSet())
        assertFalse(tools.toString().contains("purge"))
        assertFalse(tools.toString().contains("set_visibility"))
    }

    @Test
    fun unknownFieldsAndMalformedArgumentsCannotReachTheStore() = runBlocking {
        var called = false
        val protocol = McpProtocol { called = true; JsonNull }
        val invalid = listOf(
            "create_note" to """{"title":"x","id":"hidden-id"}""",
            "create_note" to """{"title":"x","aiVisible":true}""",
            "create_note" to """{"title":12}""",
            "create_note" to """{"title":"  ","content":""}""",
            "create_note" to """{"title":"x","whiteboard":{}}""",
            "update_note" to """{"id":"a","title":"overwrite"}""",
            "update_note" to """{"id":"a","expectedRevision":"r","content":null}""",
            "update_note" to """{"id":"a","expectedRevision":"r","attachments":[]}""",
            "list_notes" to """{"limit":999999}""",
            "list_notes" to """{"offset":-1}""",
            "list_notes" to """{"limit":"10"}""",
            "manage_note" to """{"id":"a","expectedRevision":"r","action":"purge"}""",
            "set_visibility" to """{"id":"a","visible":true}""",
        )
        invalid.forEach { (name, args) ->
            assertEquals(-32602, protocol.handle(call(name, args))!!["error"]!!.jsonObject["code"]!!.jsonPrimitive.int)
        }
        assertFalse(called)
    }

    @Test
    fun notificationsNeverMutateAndInvalidEnvelopesNeverReachTools() = runBlocking {
        var called = false
        val protocol = McpProtocol { called = true; JsonNull }
        assertNull(protocol.handle("""{"jsonrpc":"2.0","method":"tools/call","params":{"name":"create_note","arguments":{"title":"x"}}}"""))
        assertNull(protocol.handle("""{"jsonrpc":"2.0","method":"notifications/initialized"}"""))
        for (body in listOf("[]", "{}", """{"jsonrpc":"2.0","id":null,"method":"ping"}""",
            """{"jsonrpc":"2.0","id":true,"method":"ping"}""", "[".repeat(10000))) {
            assertNotNull(protocol.handle(body)!!["error"])
        }
        assertFalse(called)
    }

    @Test
    fun errorsNeverEchoPrivateExceptionDetails() = runBlocking {
        val unavailable = McpProtocol { throw AiNoteException(AiNoteException.Reason.UNAVAILABLE) }
        val result = unavailable.handle(call("read_note", """{"id":"hidden"}"""))!!["result"]!!.jsonObject
        assertTrue(result["isError"]!!.jsonPrimitive.boolean)
        assertTrue(result.toString().contains("Note unavailable."))
        assertFalse(result.toString().contains("hidden"))
        val broken = McpProtocol { throw IllegalStateException("secret token, database path, private title") }
        val response = broken.handle(call("read_note", """{"id":"any"}"""))!!.toString()
        assertTrue(response.contains("Operation failed."))
        assertFalse(response.contains("secret"))
    }

    @Test
    fun validCallsAreParsedWithoutPrivilegedFields() = runBlocking {
        var captured: AiCommand? = null
        val protocol = McpProtocol { captured = it; buildJsonObject { put("ok", true) } }
        protocol.handle(call("create_note", """{"title":"  笔记  ","content":"Markdown","color":"MINT"}"""))
        assertEquals("笔记", (captured as AiCommand.Create).title)
        protocol.handle(call("list_notes", "{}"))
        assertEquals(AiCommand.ListNotes("", "active", 0, 20), captured)
    }

    private fun request(method: String, params: String = "{}") = """{"jsonrpc":"2.0","id":1,"method":"$method","params":$params}"""
    private fun call(name: String, args: String) = request("tools/call", """{"name":"$name","arguments":$args}""")
}
