package io.github.easylsp.app

import io.github.easylsp.app.data.*
import io.github.easylsp.app.platform.ai.*
import io.github.easylsp.app.platform.mcp.McpClient
import io.github.easylsp.app.platform.mcp.LegacySseTransport
import io.github.easylsp.app.platform.skills.PackagePaths
import io.github.easylsp.app.platform.tools.*
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.*
import org.json.*
import org.junit.Test
import org.junit.Assert.*
import java.util.zip.*
import java.io.ByteArrayOutputStream

class ModelWorkflowTest {
    @Test fun traditionalSseAdvertisesPostEndpointAndReturnsResponseOnGetStream() = runBlocking {
        MockWebServer().use { server ->
            val endpoint = "event: endpoint\ndata: /messages?session=actual\n\n"
            val answer = "event: message\ndata: {\"jsonrpc\":\"2.0\",\"id\":\"call-one\",\"result\":{\"tools\":[]}}\n\n"
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(endpoint + answer).throttleBody(endpoint.toByteArray().size.toLong(), 1, java.util.concurrent.TimeUnit.SECONDS))
            server.enqueue(MockResponse().setResponseCode(202))
            LegacySseTransport(McpServerEntity("id", "local", server.url("/sse").toString(), true, "sse"), "secret").use { transport ->
                val result = transport.post(JSONObject().put("jsonrpc", "2.0").put("id", "call-one").put("method", "tools/list"))
                assertEquals(0, requireNotNull(result).getJSONObject("result").getJSONArray("tools").length())
                val get = server.takeRequest(); val post = server.takeRequest()
                assertEquals("GET", get.method); assertEquals("/sse", get.path); assertEquals("POST", post.method); assertEquals("/messages?session=actual", post.path); assertEquals("Bearer secret", post.getHeader("Authorization"))
            }
        }
    }
    @Test fun providerModelsUseAuthActualRemoteIdsAndAnthropicPaging() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"data":[{"id":"one","display_name":"One"}],"has_more":true,"last_id":"one"}"""))
            server.enqueue(MockResponse().setBody("""{"data":[{"id":"two"}],"has_more":false}"""))
            val models = ProviderModels().list(ProviderEntity("id", "local", "anthropic", server.url("/v1").toString(), ""), "secret")
            assertEquals(listOf("one", "two"), models.map { it.id })
            val first = server.takeRequest(); assertEquals("/v1/models", first.path); assertEquals("secret", first.getHeader("x-api-key"))
            assertEquals("/v1/models?after_id=one", server.takeRequest().path)
        }
    }
    @Test fun configuredOpenAiPathReasoningAndOutputAreSentWithoutChangingProtocol() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody("data: {\"choices\":[{\"delta\":{\"content\":\"OK\",\"reasoning_content\":\"real thought\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"))
            val provider = ProviderEntity("id", "p", "openai", server.url("/api").toString(), "remote", options = """{"requestPath":"/custom/chat","reasoningEffort":"low","maxOutputTokens":123}""")
            val reply = ModelTransport().stream(provider, "key", JSONArray().put(JSONObject().put("role", "user").put("content", "hi")), JSONArray()) {}
            assertEquals("OK", reply.text); assertEquals("real thought", reply.reasoning)
            val request = server.takeRequest(); assertEquals("/api/custom/chat", request.path)
            val body = JSONObject(request.body.readUtf8()); assertEquals("low", body.getString("reasoning_effort")); assertEquals(123, body.getInt("max_tokens")); assertEquals("Bearer key", request.getHeader("Authorization"))
        }
    }
    @Test fun runModesActuallyChangeDefinitionsAndProcessScopesMatch() {
        val tools = listOf(ToolDefinition("find_method", "read", JSONObject(), invoke = { JSONObject() }), ToolDefinition("run_command", "execute", JSONObject(), writes = true, invoke = { JSONObject() }), ToolDefinition("edit_plugin", "write", JSONObject(), writes = true, invoke = { JSONObject() }))
        assertEquals(listOf("find_method"), RunModes.filter("plan", tools).map { it.name })
        assertEquals(listOf("run_command"), RunModes.filter("minimal", tools).map { it.name })
        assertEquals(3, RunModes.filter("normal", tools).size)
        val plugin = PluginEntity("p", "project", "plugin", processScope = "children")
        assertFalse(PluginProcesses.matches(plugin, "app", "app")); assertTrue(PluginProcesses.matches(plugin, "app", "app:worker")); assertFalse(PluginProcesses.matches(plugin, "app", "another:worker"))
    }
    @Test fun skillPackageSupportsWrapperAndRejectsTraversal() {
        fun zip(vararg files: Pair<String,String>): ByteArray { val bytes = ByteArrayOutputStream(); ZipOutputStream(bytes).use { zip -> files.forEach { (name, text) -> zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray()); zip.closeEntry() } }; return bytes.toByteArray() }
        val files = PackagePaths.read(zip("repo/SKILL.md" to "# Skill", "repo/scripts/check.sh" to "echo actual", "repo/references/api.md" to "api").inputStream())
        assertEquals(setOf("SKILL.md", "scripts/check.sh", "references/api.md"), files.keys)
        try { PackagePaths.read(zip("SKILL.md" to "valid", "../escape" to "no").inputStream()); fail() } catch (_: IllegalArgumentException) { }
    }
    @Test fun geminiNativeMessagesToolsAndUsageRoundtrip() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody("data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"answer\"},{\"functionCall\":{\"name\":\"find_method\",\"args\":{\"query\":\"add\"}},\"thoughtSignature\":\"signed\"}]},\"finishReason\":\"STOP\"}],\"usageMetadata\":{\"promptTokenCount\":20,\"candidatesTokenCount\":10,\"totalTokenCount\":30}}\n\n"))
            val provider = ProviderEntity("id", "gemini", "gemini", server.url("/v1beta").toString(), "fixture")
            val reply = ModelTransport().stream(provider, "key", JSONArray().put(JSONObject().put("role", "user").put("content", "hello")), JSONArray().put(JSONObject().put("type", "function").put("function", JSONObject().put("name", "find_method").put("description", "find").put("parameters", JSONObject().put("type", "object"))))) {}
            assertEquals("answer", reply.text); assertEquals("signed", reply.calls.single().thoughtSignature); assertEquals(30, reply.usage.getInt("total_tokens"))
            val request = server.takeRequest(); assertEquals("/v1beta/models/fixture:streamGenerateContent?alt=sse", request.path); assertEquals("key", request.getHeader("x-goog-api-key"))
            assertTrue(JSONObject(request.body.readUtf8()).has("tools"))
        }
    }
}
