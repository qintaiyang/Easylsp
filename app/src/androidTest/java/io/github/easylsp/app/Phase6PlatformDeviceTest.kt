package io.github.easylsp.app

import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.github.easylsp.app.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.mockwebserver.*
import org.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Exercise the actual Android services, private Ubuntu host and pinned SDK with controlled model replies. */
class Phase6PlatformDeviceTest {
    @get:Rule val visible = VisibleActivityRule()
    @Test fun quickChatDelegationForkModelsAndMemoryUseRealServices() = runBlocking(Dispatchers.IO) {
        val graph = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as EasylspApp
        val activity = ActivityScenario.launch(MainActivity::class.java)
        val server = MockWebServer()
        val stages = ConcurrentHashMap<String, AtomicInteger>()
        val requests = AtomicInteger()
        val imageReceived = java.util.concurrent.atomic.AtomicBoolean()
        val requestModels = java.util.concurrent.CopyOnWriteArrayList<String>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val raw = request.body.readUtf8(); if (raw.contains("data:image/")) imageReceived.set(true)
                val body = JSONObject(raw); requests.incrementAndGet(); requestModels += body.getString("model")
                val messages = body.getJSONArray("messages")
                val rows = (0 until messages.length()).map { messages.getJSONObject(it) }
                assertTrue(rows.filter { it.optString("role") == "system" }.none { it.optString("content").contains("child-fixture") })
                // SDK runtime/time snapshots are also user-role messages; locate the task itself.
                val userTexts = rows.filter { it.optString("role") == "user" }.map { row ->
                    val content = row.opt("content")
                    if (content is JSONArray) (0 until content.length()).joinToString("\n") { content.getJSONObject(it).optString("text") } else content?.toString().orEmpty()
                }
                val child = userTexts.any { it.startsWith("child-fixture：") }
                val branch = userTexts.any { it.startsWith("branch-fixture：") }
                val step = stages.getOrPut(if (child) "child" else if (branch) "branch" else "parent") { AtomicInteger() }.getAndIncrement()
                val name: String?; val args: JSONObject
                if (child) {
                    name = if (step == 0) "write_file" else null
                    args = JSONObject().put("path", "child-result.txt").put("text", "子代理实际写入")
                } else if (branch) { name = null; args = JSONObject() }
                else when (step) {
                    0 -> { name = "select_tools"; args = JSONObject().put("names", JSONArray(listOf("spawn_agent", "wait_agent", "phone_use"))) }
                    1 -> { name = "phone_use"; args = JSONObject().put("action", "observe").put("mode", "screen") }
                    2 -> { name = "spawn_agent"; args = JSONObject().put("task", "child-fixture：写入 child-result.txt，然后返回结果") }
                    3 -> {
                        name = "wait_agent"
                        val result = rows.filter { it.optString("role") == "tool" }.mapNotNull { runCatching { JSONObject(it.getString("content")) }.getOrNull() }.first { it.has("agentId") }
                        args = JSONObject().put("agentId", result.getString("agentId")).put("timeoutSeconds", 60)
                    }
                    else -> { name = null; args = JSONObject() }
                }
                val declared = body.optJSONArray("tools")?.toString().orEmpty()
                if (name != null) check(declared.contains("mcp__easylsp__$name")) { "$name not declared" }
                val delta = if (name == null) JSONObject().put("content", "完成：真实服务返回的结果") else JSONObject().put("tool_calls", JSONArray().put(JSONObject().put("index", 0).put("id", "call-${if (child) "child" else "parent"}-$step").put("type", "function").put("function", JSONObject().put("name", "mcp__easylsp__$name").put("arguments", args.toString()))))
                fun event(delta: JSONObject, finish: Any) = "data: " + JSONObject().put("id", "fixture").put("model", body.getString("model")).put("choices", JSONArray().put(JSONObject().put("index", 0).put("delta", delta).put("finish_reason", finish))).toString() + "\n\n"
                return MockResponse().setHeader("Content-Type", "text/event-stream").setBody(event(delta, JSONObject.NULL) + event(JSONObject(), if (name == null) "stop" else "tool_calls") + "data: [DONE]\n\n")
            }
        }
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        val provider = graph.models.configureProvider(null, JSONObject().put("name", "Phase6 device fixture").put("protocol", "openai").put("baseUrl", server.url("/v1").toString().trimEnd('/')))
        graph.secrets.put(graph.models.credential(provider), "fixture-key")
        val first = graph.models.configureModel(null, provider.id, "fixture-one", JSONObject().put("contextWindow", 128000).put("maxOutputTokens", 8192).put("supportsImages", true))
        val second = graph.models.configureModel(null, provider.id, "fixture-two", JSONObject().put("contextWindow", 200000).put("maxOutputTokens", 16384).put("supportsImages", true))
        val ids = mutableListOf<String>()
        try {
            // Instrumentation restarts the app; wait for the device's accessibility service rebind.
            withTimeout(20000) { while (!graph.phone.status().getBoolean("enabled")) delay(100) }
            assertEquals(128000, JSONObject(graph.models.resolve(first.id).options).getInt("contextWindow"))
            assertEquals(200000, JSONObject(graph.models.resolve(second.id).options).getInt("contextWindow"))
            val choices = graph.models.available().first().filter { it.connectionId == provider.id }
            assertEquals(2, choices.size)
            val conversation = graph.conversations.create(modelId = first.id, title = "Phase6 quick chat").also { ids += it.id }
            assertNull(conversation.projectId)
            graph.chat.observe(conversation.id).await(); assertEquals(0, requests.get())
            val host = graph.chat.executionService(); host.request("host/configure", JSONObject().put("concurrency", 1))
            graph.chat.submit(conversation.id, "platform-fixture：委派可写子任务并等待", null, true, JSONArray()).await()
            withTimeout(120000) { while (graph.store.workflows.turnHistory(conversation.id).lastOrNull()?.finishedAt == null) delay(100) }
            val parentTurn = graph.store.workflows.turnHistory(conversation.id).last()
            assertEquals(parentTurn.errorDetail, "COMPLETE", parentTurn.status)
            val children = graph.store.workflows.allConversations().filter { it.parentConversationId == conversation.id }
            assertEquals(1, children.size); ids += children.map { it.id }
            assertEquals("子代理实际写入", graph.executions.directory(children.single().id).resolve("child-result.txt").readText())
            assertEquals("ALLOW", children.single().authorizationMode)
            assertTrue("Actual phone screenshot must reach the SDK model request", imageReceived.get())
            val childTurn = graph.store.workflows.turnHistory(children.single().id).last()
            val notification = "agent-result-${children.single().id}-${childTurn.startSeq}"
            withTimeout(30000) { while (!graph.getSharedPreferences("agent-notifications", 0).getBoolean(notification, false) || graph.chat.isRunning(conversation.id)) delay(100) }
            val before = requests.get()
            val fork = graph.chat.fork(graph.store.workflows.conversation(conversation.id)!!, second.id).also { ids += it.id }
            assertEquals(before, requests.get())
            assertEquals(graph.store.workflows.history(conversation.id).size, graph.store.workflows.history(fork.id).size)
            graph.chat.observe(conversation.id).await(); assertEquals(before, requests.get())
            graph.chat.submit(fork.id, "branch-fixture：仅根据继承历史回答", null, true, JSONArray()).await()
            withTimeout(60000) { while (graph.store.workflows.turnHistory(fork.id).lastOrNull()?.status != "COMPLETE" || requestModels.last() != "fixture-two") delay(100) }
            assertEquals(2, stages["child"]!!.get())
            val memory = graph.memories.save(null, JSONObject().put("scope", "global").put("title", "Phase6 fact").put("text", "来源可追溯"), conversation)
            assertEquals(memory.id, graph.memories.search(null, JSONObject().put("query", "来源可追溯")).getJSONArray("memories").getJSONObject(0).getString("memoryId"))
            graph.memories.delete(null, memory.id)
            assertEquals(0, graph.memories.search(null, JSONObject().put("query", "来源可追溯")).getJSONArray("memories").length())
        } finally {
            for (id in ids) {
                graph.store.workflows.harnessSession(id)?.let { mapping -> graph.chat.executionService().request("session/close", JSONObject().put("sessionId", mapping.sessionId)) }
                graph.store.workflows.deleteInputs(id); graph.store.workflows.deleteMessages(id); graph.store.workflows.deleteTurns(id); graph.store.workflows.deleteToolRuns(id); graph.store.workflows.deleteUsage(id); graph.store.workflows.deleteGlobalConversation(id)
            }
            graph.models.configureProvider(provider.id, JSONObject().put("enabled", false)); graph.secrets.delete(graph.models.credential(provider))
            server.shutdown(); activity.close()
        }
    }
}
