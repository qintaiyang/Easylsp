package io.github.easylsp.app

import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.github.easylsp.app.data.*
import io.github.easylsp.app.platform.tools.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.mockwebserver.*
import org.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

class Phase4ConversationDeviceTest {
    @get:Rule val visible = VisibleActivityRule()
    private val graph get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as EasylspApp
    @Test fun authorizationContinuationQueueAndModelSurviveRecovery() = runBlocking(Dispatchers.IO) {
        ActivityScenario.launch(MainActivity::class.java).use {
            val dao = graph.store.workflows
            val project = graph.store.select("io.github.easylsp.demo", "Easylsp Demo", "1")
            val id = UUID.randomUUID().toString(); val a = UUID.randomUUID().toString(); val b = UUID.randomUUID().toString()
            MockWebServer().use { server ->
                fun provider(key: String, model: String) = ProviderEntity(key, "Phase4 $model", "openai", server.url("/v1").newBuilder().host("127.0.0.1").build().toString(), model, maxSteps = 1, timeoutSeconds = 30, options = "{\"contextWindow\":32768,\"contextBudget\":32768}")
                dao.saveProvider(provider(a, "model-a")); dao.saveProvider(provider(b, "model-b"))
                graph.secrets.put("provider:$a", "fixture"); graph.secrets.put("provider:$b", "fixture")
                dao.insertConversation(ConversationEntity(id, project.id, a, "model-a", "第四阶段受控验证", project.sampleRevision, System.currentTimeMillis()))
                suspend fun finished() { withTimeout(30000) { graph.chat.activeConversation.first { it == null } } }
                suspend fun pending(): ChatInteractionEntity = withTimeout(30000) { dao.interactions(id).first { rows -> rows.any { row -> row.status == "PENDING" } }.last { row -> row.status == "PENDING" } }
                fun requestModel() = JSONObject(requireNotNull(server.takeRequest(5, TimeUnit.SECONDS)).body.readUtf8()).getString("model")
                var stage = "first input"
                try {
                    server.enqueue(command("one"))
                    graph.chat.submit(id, "验证授权", null, false, JSONArray()).await()
                    val approval = pending()
                    assertTrue(runCatching { graph.chat.selectModel(id, b).await() }.isFailure)
                    graph.chat.submit(id, "同任务补充", null, false, JSONArray()).await()
                    assertEquals("SUPPLEMENT", dao.inputs(id).first().single().intent)
                    graph.interactions.answer(approval.id, JSONObject().put("allowed", true))
                    finished(); assertEquals("model-a", requestModel()); assertEquals("LIMIT_REACHED", dao.turnHistory(id).last().status)
                    assertFalse(dao.harnessSession(id)!!.authorized)
                    assertEquals("CONFIRM", dao.conversation(id)!!.authorizationMode)
                    graph.chat.authorizeTask(id, true).await()
                    stage = "select model-b"
                    graph.chat.selectModel(id, b).await()
                    assertEquals("model-b", dao.conversation(id)!!.model); assertTrue(dao.harnessSession(id)!!.authorized)
                    stage = "send supplement"
                    server.enqueue(command("two")); graph.chat.sendQueued(id)
                    withTimeout(30000) { dao.turns(id).first { rows -> rows.size == 2 && rows.last().finishedAt != null } }; finished()
                    assertEquals("model-b", requestModel()); assertEquals(1, dao.interactions(id).first().size)
                    val before = dao.history(id).map { row -> row.engineMessageId }
                    val engine = dao.harnessSession(id)!!.sessionId
                    stage = "restart host"
                    graph.harness.stop(); graph.harness.start(); graph.chat.executionService().reconnect(); graph.chat.observe(id).await()
                    assertEquals(before, dao.history(id).map { row -> row.engineMessageId }); assertEquals(engine, dao.harnessSession(id)!!.sessionId)
                    stage = "continue after restart"
                    server.enqueue(command("three")); graph.chat.submit(id, "继续任务", null, false, JSONArray(), "CONTINUE").await(); finished()
                    assertEquals("model-b", requestModel()); assertEquals(1, dao.interactions(id).first().size)
                    assertEquals("CONTINUE", JSONObject(dao.history(id).last { row -> row.role == "user" }.blocks).getString("inputIntent"))
                    graph.chat.authorizeTask(id, false).await(); assertFalse(dao.harnessSession(id)!!.authorized)
                    stage = "continue after revocation"
                    server.enqueue(command("four")); graph.chat.submit(id, "撤销后继续", null, false, JSONArray(), "CONTINUE").await()
                    val revoked = pending(); graph.interactions.answer(revoked.id, JSONObject().put("allowed", false)); finished(); requestModel()
                    stage = "new task"
                    server.enqueue(command("five")); graph.chat.submit(id, "新需求", null, false, JSONArray()).await()
                    val fresh = pending(); graph.interactions.answer(fresh.id, JSONObject().put("allowed", false)); finished(); requestModel()
                    File(graph.filesDir, "phase4-chat-evidence.json").writeText(JSONObject().put("conversation", id).put("engine", engine).put("models", JSONArray(listOf("model-a", "model-b"))).put("turns", dao.turnHistory(id).size).put("authorizationCards", dao.interactions(id).first().size).put("historyRetainedOnRestart", true).toString(2))
                } catch (error: Exception) {
                    throw AssertionError("Phase4 stage: $stage", error)
                } finally {
                    graph.chat.stop(); finished()
                    dao.deleteInputs(id); dao.deleteInteractions(id); dao.deleteMessages(id); dao.deleteToolRuns(id); dao.deleteTurns(id); dao.deleteConversation(project.id, id)
                    dao.deleteProvider(a); dao.deleteProvider(b); graph.secrets.delete("provider:$a"); graph.secrets.delete("provider:$b")
                }
            }
        }
    }
    @Test fun realToolBoundariesAndSharedWorkspace() = runBlocking(Dispatchers.IO) {
        ActivityScenario.launch(MainActivity::class.java).use {
            val project = graph.store.select("io.github.easylsp.demo", "Easylsp Demo", "1")
            val tools = graph.tools.builtins(RunBinding(project, project.sampleRevision, null, UUID.randomUUID().toString()))
            val reference = tools.first { it.name == "read_development_reference" }
            val bad = JSONObject().put("document", "plugin-api").put("limit", 220)
            val rangeError = runCatching { ToolArguments.validate(reference.schema, bad) }.exceptionOrNull()!!
            assertTrue(rangeError.message!!.contains("limit")); assertTrue(rangeError.message!!.contains("200"))
            for (name in listOf("inspect_target", "plugin_action")) {
                val args = JSONObject().put("sessionId", "phase4-offline").put("operation", "viewTree")
                // Plugin resolution precedes session resolution; use one existing plugin if available.
                if (name == "plugin_action") {
                    val plugin = graph.store.dao.plugins(project.id).firstOrNull() ?: continue
                    args.put("pluginId", plugin.id).put("operation", "getPluginState")
                }
                val error = runCatching { tools.first { it.name == name }.invoke(args) }.exceptionOrNull()!!
                assertTrue(error.message!!, error.message!!.contains("phase4-offline")); assertTrue(error.message!!.contains("离线"))
            }
            val relative = "phase4-${UUID.randomUUID()}"
            val base = graph.executions.directory(project.id)
            try {
                val execution = graph.executions.command(project.id, "mkdir '$relative'; printf root > '$relative/root.txt'")
                assertEquals("COMPLETE", graph.executions.await(project.id, execution).getString("state"))
                val export = tools.first { it.name == "export_project_logs" }.invoke(JSONObject().put("path", "$relative/logs.jsonl"))
                assertTrue(File(base, "$relative/logs.jsonl").exists())
                val command = graph.linux.command(project.id, "mkdir '$relative/linux'; printf linux > '$relative/linux/root.txt'")
                val linux = graph.executions.start(project.id, "linux", "phase4 shared ownership") { id, output -> graph.executions.runner.execute(id, command, base, closeInput = true, output = output).exitCode }
                assertEquals("COMPLETE", graph.executions.await(project.id, linux).getString("state"))
                tools.first { it.name == "export_project_logs" }.invoke(JSONObject().put("path", "$relative/linux/logs.jsonl"))
                assertTrue(File(base, "$relative/linux/logs.jsonl").exists())
                File(graph.filesDir, "phase4-tools-evidence.json").writeText(JSONObject().put("rangeError", rangeError.message).put("androidRootToAppExport", true).put("linuxRootToAppExport", true).put("export", export).toString(2))
            } finally { File(base, relative).deleteRecursively() }
        }
    }
    private fun command(id: String): MockResponse {
        val call = JSONObject().put("index", 0).put("id", "phase4-$id").put("type", "function").put("function", JSONObject().put("name", "mcp__easylsp__run_command").put("arguments", "{\"command\":\"printf phase4\",\"waitSeconds\":1}"))
        val delta = JSONObject().put("tool_calls", JSONArray().put(call))
        val chunk = JSONObject().put("id", "reply-$id").put("model", "fixture").put("choices", JSONArray().put(JSONObject().put("index", 0).put("delta", delta).put("finish_reason", "tool_calls")))
        return MockResponse().setHeader("Content-Type", "text/event-stream").setBody("data: $chunk\n\ndata: [DONE]\n\n")
    }
}
