package io.github.easylsp.app

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.core.app.ActivityScenario
import io.github.easylsp.app.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.mockwebserver.*
import org.json.*
import org.junit.Test
import org.junit.Before
import org.junit.After
import org.junit.Assert.*
import java.io.File
import java.util.UUID

class Phase3ConversationDeviceTest {
    @get:org.junit.Rule val visible = VisibleActivityRule()
    private lateinit var activity: ActivityScenario<MainActivity>
    @Before fun showActivity() { activity = ActivityScenario.launch(MainActivity::class.java) }
    @After fun closeActivity() { activity.close() }
    private val graph = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as EasylspApp
    @Test fun largeApkQueriesRunInAnalysisProcess() = runBlocking(Dispatchers.IO) {
        val project = requireNotNull(graph.store.dao.projectByPackage("com.ss.android.ugc.aweme"))
        val rows = JSONArray(); val metrics = JSONObject().put("queries", rows)
        val evidence = File(graph.filesDir, "phase3-analysis-evidence.json")
        suspend fun query(action: String, args: JSONObject = JSONObject()): JSONObject {
            val start = System.currentTimeMillis()
            val result = graph.analysis.call(project.id, project.sampleRevision, action, args)
            rows.put(JSONObject().put("action", action).put("startedAt", start).put("finishedAt", System.currentTimeMillis()).put("millis", System.currentTimeMillis() - start).put("result", result))
            evidence.writeText(metrics.toString(2))
            return result
        }
        query("clear")
        assertTrue(query("manifest").getString("text").contains("manifest"))
        assertTrue(query("resources", JSONObject().put("limit", 2)).getInt("total") > 2)
        val classes = query("classes", JSONObject().put("query", "com.ss.android.ugc.aweme").put("limit", 2))
        assertTrue(classes.getInt("total") > 2)
        // The first package match is an empty marker interface in the actual sample.
        val target = query("classes", JSONObject().put("owner", "com.ss.android.ugc.aweme.main.MainActivity").put("limit", 2))
        assertTrue(target.getInt("total") > 0)
        val cls = target.getJSONArray("items").getJSONObject(0)
        val methods = query("methods", JSONObject().put("owner", cls.getString("owner")).put("query", "->onCreate(").put("limit", 2))
        assertTrue(methods.getInt("total") > 0)
        val method = methods.getJSONArray("items").getJSONObject(0)
        val codeArgs = JSONObject().put("className", cls.getString("owner")).put("source", cls.getString("source")).put("signature", method.getString("signature"))
        assertTrue(query("smali", codeArgs).getString("text").contains(".method"))
        assertTrue(query("java", codeArgs).getString("text").isNotBlank())
        query("strings", JSONObject().put("query", "抖音").put("owner", cls.getString("owner")).put("limit", 2))
        metrics.put("queries", rows).put("memory", query("info")).put("apkBytes", File(JSONArray(requireNotNull(graph.store.dao.sample(project.id, project.sampleRevision)).filesJson).getString(0)).length())
        evidence.writeText(metrics.put("complete", true).toString(2))
    }
    @Test fun requestLimitAndUserWaitPersistWithOriginalInput() = runBlocking(Dispatchers.IO) {
        val project = graph.store.select("io.github.easylsp.demo", "Easylsp Demo", "1")
        val providerId = UUID.randomUUID().toString(); val conversationId = UUID.randomUUID().toString()
        MockWebServer().use { server ->
            server.enqueue(response("ask_user", "question", JSONObject().put("question", "等待超过模型请求时限")))
            server.enqueue(response(null, "", JSONObject()))
            graph.store.workflows.saveProvider(ProviderEntity(providerId, "第三阶段测试", "openai", server.url("/v1").newBuilder().host("127.0.0.1").build().toString(), "fixture", maxSteps = 2, timeoutSeconds = 1, options = "{\"contextWindow\":1000000,\"contextBudget\":200000}"))
            graph.secrets.put("provider:$providerId", "fixture-key")
            graph.store.workflows.insertConversation(ConversationEntity(conversationId, project.id, providerId, "fixture", "超时隔离", project.sampleRevision, System.currentTimeMillis()))
            try {
                graph.chat.send(conversationId, "请提问", null, false, JSONArray().put(JSONObject().put("type", "text").put("text", "附件原文"))).await()
                val question = withTimeout(30000) { graph.store.workflows.interactions(conversationId).first { rows -> rows.any { it.status == "PENDING" } } }.last { it.status == "PENDING" }
                delay(1500)
                assertEquals(conversationId, graph.chat.activeConversation.value)
                graph.interactions.answer(question.id, JSONObject().put("text", "继续"))
                withTimeout(15000) { graph.chat.activeConversation.first { it == null } }
                val complete = graph.store.workflows.turnHistory(conversationId).single()
                assertEquals("COMPLETE", complete.status); assertEquals(2, complete.requestCount)
                assertTrue(graph.store.workflows.history(conversationId).first().blocks.contains("附件原文"))
                graph.store.workflows.saveProvider(requireNotNull(graph.store.workflows.provider(providerId)).copy(maxSteps = 1))
                server.enqueue(response("get_project_info", "read", JSONObject()))
                graph.chat.send(conversationId, "再次读取", null, false).await()
                withTimeout(15000) { graph.chat.activeConversation.first { it == null } }
                val limited = graph.store.workflows.turnHistory(conversationId).last()
                assertEquals("LIMIT_REACHED", limited.status); assertEquals("LIMIT", limited.errorSource)
            } finally {
                graph.chat.stop()
                graph.store.transaction { graph.store.workflows.deleteMessages(conversationId); graph.store.workflows.deleteToolRuns(conversationId); graph.store.workflows.deleteTurns(conversationId); graph.store.workflows.deleteConversation(project.id, conversationId); graph.store.workflows.deleteProvider(providerId) }
            }
        }
    }
    @Test fun fixedHarnessUsesCurrentProviderAndReadOnlyAndroidTool() = runBlocking(Dispatchers.IO) {
        val provider = graph.store.workflows.observeProviders().first().first()
        val options = JSONObject(provider.options)
        val project = requireNotNull(graph.store.dao.projectByPackage("io.github.easylsp.demo"))
        val binding = io.github.easylsp.app.platform.tools.RunBinding(project, project.sampleRevision, null, "h0-probe")
        io.github.easylsp.app.platform.harness.HarnessToolBridge(graph.tools.builtins(binding).filter { it.name == "get_project_info" }).use { bridge ->
            val root = graph.linux.root.path
            val script = io.github.easylsp.app.platform.terminal.MountNamespace(graph).shell("mount --rbind /dev '$root/dev'; mount --rbind /proc '$root/proc'; exec chroot '$root' /usr/bin/env PATH=/usr/local/bin:/usr/bin:/bin /usr/local/bin/node /opt/easylsp-harness/probe.mjs")
            val session = "easylsp-h0-${UUID.randomUUID()}"
            suspend fun run(resume: Boolean): String {
                val config = JSONObject().put("api", "openai-completions").put("baseURL", provider.baseUrl).put("model", provider.model).put("maxTokens", options.optInt("maxOutputTokens", 4096)).put("contextWindow", options.optInt("contextWindow")).put("secret", graph.secrets.get("provider:${provider.id}")).put("bridgeURL", bridge.url).put("bridgeToken", bridge.token).put("sessionId", session).put("resume", resume)
                val output = StringBuilder()
                val result = graph.executions.runner.execute(UUID.randomUUID().toString(), listOf(graph.root.su, "-c", script), graph.cacheDir, input = "$config\n", timeoutSeconds = 180) { output.append(it) }
                assertEquals(output.toString(), 0, result.exitCode)
                return output.toString()
            }
            val cold = withTimeout(180000) { run(false) }
            assertTrue(cold, cold.contains("\"probe\":\"complete\""))
            val resumed = withTimeout(180000) { run(true) }
            File(graph.filesDir, "phase3-harness-evidence.json").writeText(JSONObject().put("version", "0.2.2-alpha.3").put("cold", cold).put("resumed", resumed).toString(2))
        }
    }
    @Test fun queuedInputAndQuestionSurviveActivityRecreation() = runBlocking(Dispatchers.IO) {
        val project = graph.store.select("io.github.easylsp.demo", "Easylsp Demo", "1")
        val providerId = UUID.randomUUID().toString(); val conversationId = UUID.randomUUID().toString()
        val dao = graph.store.workflows
        MockWebServer().use { server ->
            server.enqueue(response("ask_user", "queue-question", JSONObject().put("question", "请选择下一步").put("options", JSONArray().put("继续"))))
            server.enqueue(response(null, "", JSONObject()))
            server.enqueue(response(null, "", JSONObject()))
            dao.saveProvider(ProviderEntity(providerId, "队列测试", "openai", server.url("/v1").newBuilder().host("127.0.0.1").build().toString(), "fixture", maxSteps = 2, timeoutSeconds = 10, options = "{\"contextWindow\":1000000,\"contextBudget\":200000}"))
            graph.secrets.put("provider:$providerId", "fixture-key")
            dao.insertConversation(ConversationEntity(conversationId, project.id, providerId, "fixture", "队列测试", project.sampleRevision, System.currentTimeMillis()))
            try {
                graph.chat.submit(conversationId, "先询问", null, false, JSONArray()).await()
                val question = withTimeout(15000) { dao.interactions(conversationId).first { rows -> rows.any { it.status == "PENDING" } } }.single()
                graph.chat.submit(conversationId, "补充信息", null, true, JSONArray().put(JSONObject().put("type", "text").put("text", "队列附件原文"))).await()
                graph.chat.submit(conversationId, "取消这一条", null, false, JSONArray()).await()
                val queued = dao.inputs(conversationId).first()
                assertEquals(2, queued.size); assertTrue(queued.first().authorized); assertTrue(queued.first().attachments.contains("队列附件原文"))
                dao.cancelInput(queued.last().id)
                activity.recreate()
                assertEquals("PENDING", dao.interaction(question.id)!!.status)
                graph.interactions.answer(question.id, JSONObject().put("text", "继续"))
                val turns = withTimeout(20000) { dao.turns(conversationId).first { it.size == 2 && it.all { turn -> turn.finishedAt != null } } }
                assertTrue(turns.all { it.status == "COMPLETE" })
                assertEquals("ANSWERED", dao.interaction(question.id)!!.status)
                assertTrue(dao.inputs(conversationId).first().isEmpty())
                val users = dao.history(conversationId).filter { it.role == "user" }
                assertEquals(listOf("先询问", "补充信息"), users.map { it.content })
                assertTrue(users.last().blocks.contains("队列附件原文"))
                assertEquals(3, server.requestCount)
            } finally {
                graph.chat.stop()
                withTimeout(15000) { graph.chat.activeConversation.first { it == null } }
                graph.store.transaction {
                    dao.deleteMessages(conversationId); dao.deleteToolRuns(conversationId); dao.deleteTurns(conversationId); dao.deleteInputs(conversationId); dao.deleteInteractions(conversationId); dao.deleteConversation(project.id, conversationId); dao.deleteProvider(providerId)
                }
            }
        }
    }
    private fun response(tool: String?, id: String, args: JSONObject): MockResponse {
        val delta = JSONObject().put("content", if (tool == null) "完成" else "")
        if (tool != null) delta.put("tool_calls", JSONArray().put(JSONObject().put("index", 0).put("id", id).put("type", "function").put("function", JSONObject().put("name", "mcp__easylsp__$tool").put("arguments", args.toString()))))
        val event = JSONObject().put("choices", JSONArray().put(JSONObject().put("delta", delta).put("finish_reason", if (tool == null) "stop" else "tool_calls")))
        return MockResponse().setHeader("Content-Type", "text/event-stream").setBody("data: $event\n\ndata: [DONE]\n\n")
    }
}
