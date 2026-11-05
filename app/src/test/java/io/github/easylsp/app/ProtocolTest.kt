package io.github.easylsp.app

import io.github.easylsp.app.data.*
import io.github.easylsp.app.platform.ai.*
import io.github.easylsp.app.platform.network.SseReader
import io.github.easylsp.app.platform.mcp.McpClient
import io.github.easylsp.app.platform.tools.*
import kotlinx.coroutines.*
import okhttp3.mockwebserver.*
import okio.Buffer
import org.json.*
import org.junit.Test
import org.junit.Assert.*
import java.util.concurrent.TimeUnit

class ProtocolTest {
    private fun stream(vararg data: String) = data.joinToString("") { "data: $it\n\n" }
    @Test fun fragmentedToolArgumentsAndUsageRemainAssociated() {
        val parser = OpenAiStreamAccumulator()
        val first = """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_a","function":{"name":"find_method","arguments":"{\"query\":"}},{"index":1,"id":"call_b","function":{"name":"read_plugin","arguments":"{\"pluginId\":"}}]}}]}"""
        parser.feed(first)
        parser.feed("""{"choices":[{"delta":{"tool_calls":[{"index":1,"function":{"arguments":"\"p\"}"}},{"index":0,"function":{"arguments":"\"add\"}"}}]},"finish_reason":"tool_calls"}]}""")
        parser.feed("""{"choices":[],"usage":{"total_tokens":12}}""")
        val reply = parser.finish()
        assertEquals(listOf("call_a", "call_b"), reply.calls.map { it.id })
        assertEquals("add", JSONObject(reply.calls[0].arguments).getString("query"))
        assertEquals("p", JSONObject(reply.calls[1].arguments).getString("pluginId"))
        assertEquals(12, reply.usage.getInt("total_tokens"))
    }
    @Test fun sseHandlesCommentsCrLfMultilineAndFinalRecord() {
        val records = mutableListOf<Pair<String,String>>()
        SseReader.read(Buffer().writeUtf8(": ping\r\nevent: result\r\ndata: first\r\ndata: second\r\n\r\ndata: final")) { type, value -> records += type to value; true }
        assertEquals(listOf("result" to "first\nsecond", "message" to "final"), records)
    }
    @Test fun incompleteStreamCannotBecomeSuccessfulToolCall() {
        val parser = OpenAiStreamAccumulator(); parser.feed("""{"choices":[{"delta":{"content":"partial"}}]}""")
        try { parser.finish(); fail("must reject truncated stream") } catch (expected: IllegalArgumentException) { assertTrue(expected.message!!.contains("未正常完成")) }
    }
    @Test fun anthropicRequestMapsToolResultsAndResponseFragments() = runBlocking {
        val server = MockWebServer()
        val sse = """event: content_block_start
            |data: {"index":0,"content_block":{"type":"tool_use","id":"use_1","name":"find_method","input":{}}}
            |
            |event: content_block_delta
            |data: {"index":0,"delta":{"type":"input_json_delta","partial_json":"{\"query\":"}}
            |
            |event: content_block_delta
            |data: {"index":0,"delta":{"type":"input_json_delta","partial_json":"\"add\"}"}}
            |
            |event: message_delta
            |data: {"delta":{"stop_reason":"tool_use"},"usage":{"output_tokens":8}}
            |
            |event: message_stop
            |data: {"type":"message_stop"}
            |
        """.trimMargin()
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(sse))
        server.start()
        try {
            val messages = JSONArray().put(JSONObject().put("role", "system").put("content", "instructions"))
                .put(JSONObject().put("role", "assistant").put("content", "").put("tool_calls", JSONArray().put(ToolCall("a", "first", "{}").json()).put(ToolCall("b", "second", "{}").json())))
                .put(JSONObject().put("role", "tool").put("tool_call_id", "a").put("content", "A"))
                .put(JSONObject().put("role", "tool").put("tool_call_id", "b").put("content", "B"))
            val reply = ModelTransport().stream(ProviderEntity("p", "test", "anthropic", server.url("/v1").toString(), "chosen-model"), "secret", messages, JSONArray()) {}
            assertEquals("add", JSONObject(reply.calls.single().arguments).getString("query"))
            val request = server.takeRequest(2, TimeUnit.SECONDS)!!
            assertEquals("/v1/messages", request.path); assertEquals("secret", request.getHeader("x-api-key"))
            val body = JSONObject(request.body.readUtf8()); assertEquals("chosen-model", body.getString("model"))
            assertEquals(2, body.getJSONArray("messages").getJSONObject(1).getJSONArray("content").length())
        } finally { server.shutdown() }
    }
    @Test fun cancellingModelReadClosesInFlightNetworkCall() = runBlocking {
        val server = MockWebServer(); server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)); server.start()
        try {
            val task = launch { ModelTransport().stream(ProviderEntity("p", "test", "openai", server.url("/v1").toString(), "m"), "", JSONArray(), JSONArray()) {} }
            withContext(Dispatchers.IO) { assertNotNull(server.takeRequest(3, TimeUnit.SECONDS)) }
            withTimeout(3000) { task.cancelAndJoin() }; assertTrue(task.isCancelled)
        } finally { server.shutdown() }
    }
    @Test fun mcpNegotiatesSessionPagesToolsAndAssociatesCallResult() = runBlocking {
        val server = MockWebServer(); val seen = mutableListOf<JSONObject>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val value = JSONObject(request.body.readUtf8()); synchronized(seen) { seen += value }
                val method = value.getString("method")
                if (method != "initialize") {
                    assertEquals("session-1", request.getHeader("Mcp-Session-Id")); assertEquals("2025-11-25", request.getHeader("MCP-Protocol-Version"))
                }
                if (method == "notifications/initialized") return MockResponse().setResponseCode(202)
                val result = when (method) {
                    "initialize" -> JSONObject().put("protocolVersion", "2025-11-25").put("capabilities", JSONObject())
                    "tools/list" -> if (value.getJSONObject("params").has("cursor")) JSONObject().put("tools", JSONArray().put(JSONObject().put("name", "second").put("inputSchema", JSONObject().put("type", "object")))) else JSONObject().put("tools", JSONArray().put(JSONObject().put("name", "echo").put("inputSchema", JSONObject().put("type", "object")))).put("nextCursor", "page2")
                    "tools/call" -> JSONObject().put("content", JSONArray().put(JSONObject().put("type", "text").put("text", value.getJSONObject("params").getJSONObject("arguments").getString("text"))))
                    else -> error(method)
                }
                val response = JSONObject().put("jsonrpc", "2.0").put("id", value.getString("id")).put("result", result)
                return if (method == "tools/call") MockResponse().setHeader("Content-Type", "text/event-stream").setBody(stream(response.toString()))
                    else MockResponse().setHeader("Content-Type", "application/json").setHeader("Mcp-Session-Id", "session-1").setBody(response.toString())
            }
        }
        server.start()
        try {
            val client = McpClient(McpServerEntity("s", "controlled", server.url("/mcp").toString()), "")
            client.initialize(); assertEquals(listOf("echo", "second"), client.listTools().map { it.getString("name") })
            val result = client.call("echo", JSONObject().put("text", "actual result"))
            assertEquals("actual result", result.getJSONArray("content").getJSONObject(0).getString("text"))
            assertEquals(5, seen.size)
        } finally { server.shutdown() }
    }
    @Test fun toolScriptRegistersSchemaAndRunsItsOwnCallback() {
        val registry = ToolPluginRegistry(); val interpreter = bsh.Interpreter(); interpreter.set("toolPlugin", registry)
        interpreter.eval(java.io.File(System.getProperty("easylsp.toolExample")!!).readText())
        val tool = registry.entries.getValue("echo")
        ToolArguments.validate(tool.schema(), JSONObject().put("text", "from script"))
        assertEquals("from script", tool.callback().execute(JSONObject().put("text", "from script")))
        try { ToolArguments.validate(tool.schema(), JSONObject()); fail("required args") } catch (expected: IllegalArgumentException) { assertTrue(expected.message!!.contains("text")) }
    }
}
