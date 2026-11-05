package io.github.easylsp.app

import io.github.easylsp.app.data.McpServerEntity
import io.github.easylsp.app.platform.harness.HarnessToolBridge
import io.github.easylsp.app.platform.mcp.McpClient
import io.github.easylsp.app.platform.tools.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class BuiltinToolContractTest {
    @Test fun arraySchemasAndPhoneGesturesExposeTheirRealElementTypes() {
        val phone = BuiltinToolSchemas.phone(JSONObject(java.io.File("src/main/assets/phone-use.input.schema.json").readText()))
        val fields = phone.getJSONObject("properties")
        val stroke = fields.getJSONObject("strokes").getJSONObject("items")
        assertEquals("object", stroke.getString("type"))
        val point = stroke.getJSONObject("properties").getJSONObject("points").getJSONObject("items")
        assertEquals("integer", point.getJSONObject("properties").getJSONObject("x").getString("type"))
        assertEquals(setOf("x", "y", "nodeId"), fields.getJSONObject("target").getJSONObject("properties").keySet())
        assertFalse(phone.toString().contains("\$ref"))
        val memory = BuiltinToolSchemas.create("save_memory", mapOf("sourceMessageIds" to "array"), emptyList())
        assertEquals("integer", memory.getJSONObject("properties").getJSONObject("sourceMessageIds").getJSONObject("items").getString("type"))
        val plan = BuiltinToolSchemas.create("update_plan", mapOf("items" to "array"), listOf("items"))
        assertEquals(3, plan.getJSONObject("properties").getJSONObject("items").getJSONObject("items").getJSONObject("properties").getJSONObject("status").getJSONArray("enum").length())
    }

    @Test fun actualConversationPaginationMistakesExplainTheCorrectUnitAndLimit() {
        for ((name, limit, unit) in listOf(Triple("read_plugin", 220, "行"), Triple("read_execution", 18000, "字符"), Triple("read_execution", 14000, "字符"))) {
            val schema = BuiltinToolSchemas.create(name, mapOf("offset" to "integer", "limit" to "integer", "waitSeconds" to "integer"), emptyList())
            val error = runCatching { ToolArguments.validate(schema, JSONObject().put("limit", limit)) }.exceptionOrNull()!!
            assertTrue(error.message!!, error.message!!.contains("limit"))
            assertTrue(schema.getJSONObject("properties").getJSONObject("limit").getString("description").contains(unit))
            val maximum = schema.getJSONObject("properties").getJSONObject("limit").getInt("maximum")
            assertEquals(if (unit == "行") 200 else 12000, maximum)
            ToolArguments.validate(schema, JSONObject().put("offset", 0).put("limit", maximum))
        }
    }

    @Test fun invalidWaitCannotStartACommandThenReportFailure() = runBlocking {
        val schema = BuiltinToolSchemas.create("run_command", mapOf("command" to "string", "timeoutSeconds" to "integer", "waitSeconds" to "integer"), listOf("command"))
        var executions = 0
        val tool = ToolDefinition("run_command", "Android shell", schema, invoke = { executions++; JSONObject().put("executionId", "one-command") })
        HarnessToolBridge(listOf(tool)).use { bridge ->
            McpClient(McpServerEntity("local", "local", bridge.url), bridge.token).use { client ->
                client.initialize()
                val advertised = client.listTools().single().getJSONObject("inputSchema")
                assertEquals(60, advertised.getJSONObject("properties").getJSONObject("waitSeconds").getInt("maximum"))
                for ((key, value) in listOf("waitSeconds" to 0, "waitSeconds" to 61, "timeoutSeconds" to 3601)) {
                    val result = client.call("run_command", JSONObject().put("command", "echo one").put(key, value))
                    assertTrue(result.getBoolean("isError"))
                    assertTrue(result.toString().contains(key))
                }
                assertEquals(0, executions)
                client.call("run_command", JSONObject().put("command", "echo one").put("waitSeconds", 1))
                assertEquals(1, executions)
            }
        }
    }

    @Test fun ubuntuCommandDeadlineIsAdvertisedAndRejectedAtTheBoundary() {
        val schema = BuiltinToolSchemas.create("run_linux", mapOf("command" to "string", "timeoutSeconds" to "integer"), listOf("command"))
        ToolArguments.validate(schema, JSONObject().put("command", "python3 script.py").put("timeoutSeconds", 600))
        val error = runCatching { ToolArguments.validate(schema, JSONObject().put("command", "python3 script.py").put("timeoutSeconds", 0)) }.exceptionOrNull()!!
        assertTrue(error.message!!.contains("timeoutSeconds"))
    }
}
