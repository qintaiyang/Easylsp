package io.github.easylsp.app

import io.github.easylsp.app.data.McpServerEntity
import io.github.easylsp.app.platform.mcp.McpRegistry
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.*
import org.json.*
import org.junit.Assert.*
import org.junit.Test

class McpRegistryTest {
    @Test fun discoveryIsLazyAndSelectedConnectionAndCatalogAreReused() = runBlocking {
        MockWebServer().use { server ->
            val methods = mutableListOf<String>()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val body = JSONObject(request.body.readUtf8()); val method = body.getString("method"); methods += method
                    if (method == "notifications/initialized") return MockResponse().setResponseCode(202)
                    val result = when (method) {
                        "initialize" -> JSONObject().put("protocolVersion", "2025-11-25").put("capabilities", JSONObject())
                        "tools/list" -> JSONObject().put("tools", JSONArray().put(JSONObject().put("name", "read").put("inputSchema", JSONObject().put("type", "object"))))
                        "tools/call" -> JSONObject().put("content", JSONArray().put(JSONObject().put("type", "text").put("text", "actual result")))
                        else -> error(method)
                    }
                    return MockResponse().setHeader("Content-Type", "application/json").setBody(JSONObject().put("jsonrpc", "2.0").put("id", body.getString("id")).put("result", result).toString())
                }
            }
            server.start()
            val registry = McpRegistry()
            val selected = McpServerEntity("fixture", "fixture", server.url("/mcp").toString())
            assertEquals(0, server.requestCount)
            repeat(2) { assertEquals("read", registry.tools(selected, "fixture-key").single().getString("name")) }
            repeat(2) { assertTrue(registry.call(selected, "fixture-key", "read", JSONObject()).toString().contains("actual result")) }
            assertEquals(1, methods.count { it == "initialize" }); assertEquals(1, methods.count { it == "tools/list" }); assertEquals(2, methods.count { it == "tools/call" })
        }
    }
}
