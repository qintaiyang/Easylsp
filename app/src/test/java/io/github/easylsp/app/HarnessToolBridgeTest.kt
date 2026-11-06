package io.github.easylsp.app

import io.github.easylsp.app.data.McpServerEntity
import io.github.easylsp.app.platform.harness.HarnessToolBridge
import io.github.easylsp.app.platform.mcp.McpClient
import io.github.easylsp.app.platform.tools.ToolDefinition
import io.github.easylsp.app.platform.tools.ToolCapabilities
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Test
import org.junit.Assert.*

class HarnessToolBridgeTest {
    @Test fun closeWaitsForInFlightToolCleanup() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val cancelling = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var cleaned = false
        val tool = ToolDefinition("wait", "Owned task", JSONObject("""{"type":"object","properties":{}}"""), invoke = {
            started.complete(Unit)
            try { awaitCancellation() }
            finally { withContext(NonCancellable) { cancelling.complete(Unit); release.await(); cleaned = true } }
        })
        HarnessToolBridge(listOf(tool)).use { bridge ->
            McpClient(McpServerEntity("local", "local", bridge.url), bridge.token).use { client ->
                client.initialize()
                val call = launch { client.call("wait", JSONObject()) }
                withTimeout(5000) { started.await() }
                val closing = async { bridge.closeAndJoin() }
                withTimeout(5000) { cancelling.await() }
                try { assertFalse(closing.isCompleted); assertFalse(cleaned) }
                finally { release.complete(Unit) }
                withTimeout(5000) { closing.await(); call.join() }
                assertTrue(cleaned)
            }
        }
    }

    @Test fun capabilityAdmissionMatchesTheStepAndDoesNotChangeDuringItsBatch() = runBlocking {
        var effects = 0
        val tool = ToolDefinition("operation", "A specialized operation", JSONObject("""{"type":"object","properties":{}}"""), invoke = { effects++; JSONObject().put("done", true) })
        val capabilities = ToolCapabilities(listOf(tool))
        HarnessToolBridge(listOf(tool) + capabilities.definitions(), capabilities = capabilities).use { bridge ->
            val http = OkHttpClient()
            fun resolve(): JSONObject {
                val request = Request.Builder().url(bridge.url).header("Authorization", "Bearer ${bridge.token}")
                    .post("""{"jsonrpc":"2.0","id":99,"method":"easylsp/capabilities/resolve"}""".toRequestBody("application/json".toMediaType())).build()
                return http.newCall(request).execute().use { JSONObject(it.body!!.string()).getJSONObject("result") }
            }
            McpClient(McpServerEntity("local", "local", bridge.url), bridge.token).use { client ->
                client.initialize()
                assertEquals(3, client.listTools().size)
                resolve()
                client.call("select_tools", JSONObject("""{"names":["operation"]}"""))
                assertTrue(client.call("operation", JSONObject()).getBoolean("isError"))
                assertEquals(0, effects)
                assertEquals(1L, resolve().getLong("toolSetVersion"))
                assertFalse(client.call("operation", JSONObject()).optBoolean("isError"))
                client.call("select_tools", JSONObject("""{"names":["operation"],"mode":"remove"}"""))
                assertFalse(client.call("operation", JSONObject()).optBoolean("isError"))
                resolve()
                assertTrue(client.call("operation", JSONObject()).getBoolean("isError"))
                assertEquals(2, effects)
            }
        }
    }

    @Test fun realHttpInitializeListCallErrorsAndCancellation() = runBlocking {
        val started = CompletableDeferred<Unit>(); val cancelled = CompletableDeferred<Unit>()
        val schema = JSONObject("""{"type":"object","properties":{},"additionalProperties":false}""")
        val tools = listOf(
            ToolDefinition("info", "Read fixed binding", schema, invoke = { JSONObject().put("project", "fixed") }),
            ToolDefinition("fail", "Report failure", schema, invoke = { error("actual failure") }),
            ToolDefinition("wait", "Wait until cancelled", schema, invoke = { started.complete(Unit); try { awaitCancellation() } finally { cancelled.complete(Unit) } })
        )
        HarnessToolBridge(tools).use { bridge ->
            McpClient(McpServerEntity("local", "local", bridge.url), bridge.token).use { client ->
                client.initialize(); assertEquals(3, client.listTools().size)
                assertEquals("fixed", client.call("info", JSONObject()).getJSONObject("structuredContent").getString("project"))
                assertTrue(client.call("fail", JSONObject()).getBoolean("isError"))
                val job = launch { client.call("wait", JSONObject()) }
                withTimeout(5000) { started.await() }; job.cancelAndJoin()
                withTimeout(5000) { cancelled.await() }
            }
        }
    }
}
