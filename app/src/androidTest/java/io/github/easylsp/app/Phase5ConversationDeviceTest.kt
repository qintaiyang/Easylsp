package io.github.easylsp.app

import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.github.easylsp.app.data.*
import io.github.easylsp.app.feature.chat.summarizeUsage
import io.github.easylsp.app.platform.harness.HarnessSessionClient
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

class Phase5ConversationDeviceTest {
    @get:Rule val visible = VisibleActivityRule()
    private val graph get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as EasylspApp
    private fun reply(text: String? = null, command: String? = null): MockResponse {
        val delta = if (command == null) JSONObject().put("content", text) else JSONObject().put("tool_calls", JSONArray().put(JSONObject().put("index", 0).put("id", UUID.randomUUID().toString()).put("type", "function")
            .put("function", JSONObject().put("name", "mcp__easylsp__run_command").put("arguments", JSONObject().put("command", command).put("waitSeconds", 1).toString()))))
        val chunk = JSONObject().put("id", "fixture").put("choices", JSONArray().put(JSONObject().put("index", 0).put("delta", delta).put("finish_reason", if (command == null) "stop" else "tool_calls")))
        val usage = JSONObject().put("choices", JSONArray()).put("usage", JSONObject().put("prompt_tokens", 90).put("completion_tokens", 10).put("total_tokens", 100)
            .put("prompt_tokens_details", JSONObject().put("cached_tokens", 80)))
        return MockResponse().setHeader("Content-Type", "text/event-stream").setBody("data: $chunk\n\ndata: $usage\n\ndata: [DONE]\n\n")
    }
    private suspend fun finished(id: String) = withTimeout(45000) {
        graph.store.workflows.turns(id).first { it.lastOrNull()?.finishedAt != null }
        graph.chat.activeConversation.first { it == null }
    }
    private suspend fun approval(id: String) = withTimeout(30000) { graph.store.workflows.interactions(id).first { rows -> rows.any { it.status == "PENDING" } }.last { it.status == "PENDING" } }
    private fun request(server: MockWebServer) = JSONObject(requireNotNull(server.takeRequest(5, TimeUnit.SECONDS)).body.readUtf8())
    private fun cleanup(id: String, project: String, providers: List<String>) {
        val dao = graph.store.workflows
        graph.store.transaction {
            dao.branchHistory(id).forEach { b -> dao.deleteMessages(b.id); dao.deleteTurns(b.id); dao.deleteToolRuns(b.id); dao.deleteInteractions(b.id); dao.clearPlan(b.id) }
            dao.deleteBranches(id); dao.deleteUsage(id); dao.deleteMessages(id); dao.deleteToolRuns(id); dao.deleteTurns(id); dao.deleteInteractions(id)
            dao.deleteInputs(id); dao.deleteDraft(id); dao.deleteReading(id); dao.deleteConversation(project, id)
            providers.forEach { dao.deleteProvider(it); graph.secrets.delete("provider:$it") }
        }
    }
    @Test fun realRewindKeepsPrefixAttachmentsUsageAndNeverReplaysWrites() = runBlocking(Dispatchers.IO) {
        ActivityScenario.launch(MainActivity::class.java).use {
            graph.harness.stop(); graph.harness.start(); graph.chat.executionService().reconnect()
            val dao = graph.store.workflows
            val project = graph.store.select("io.github.easylsp.demo", "Easylsp Demo", "1")
            val id = UUID.randomUUID().toString(); val p = UUID.randomUUID().toString()
            MockWebServer().use { server ->
                dao.saveProvider(ProviderEntity(p, "Phase5 rewind", "openai", server.url("/v1").toString(), "fixture", maxSteps = 1, options = "{\"contextWindow\":32768}"))
                graph.secrets.put("provider:$p", "fixture")
                dao.insertConversation(ConversationEntity(id, project.id, p, "fixture", "第五阶段回退验证", project.sampleRevision, System.currentTimeMillis()))
                val marker = "phase5-${UUID.randomUUID()}.txt"; val file = File(graph.executions.directory(project.id), marker)
                try {
                    server.enqueue(reply(command = "printf first >> '$marker'"))
                    graph.chat.submit(id, "first-keep", null, true, JSONArray().put(JSONObject().put("type", "text").put("text", "first-attachment"))).await(); finished(id); request(server)
                    server.enqueue(reply(command = "printf second >> '$marker'"))
                    graph.chat.submit(id, "discard-this", null, false, JSONArray()).await(); finished(id); request(server)
                    assertEquals("firstsecond", file.readText()); assertEquals("ALLOW", dao.conversation(id)!!.authorizationMode)
                    assertTrue(dao.interactions(id).first().isEmpty())
                    val old = dao.harnessSession(id)!!
                    val late = HarnessSessionClient(graph.store, id) { _, _ -> error("旧分支不能读取当前历史") }
                    val edited = dao.history(id).single { row -> row.role == "user" && row.content == "discard-this" }
                    val branch = UUID.randomUUID().toString()
                    dao.saveDraft(ChatDraftEntity(id, "replacement", "[]", edited.id, branch))
                    server.enqueue(reply("replacement-ok"))
                    graph.chat.submit(id, "replacement", null, false, JSONArray(), editedMessageId = edited.id, branchId = branch).await(); finished(id)
                    val body = request(server).toString()
                    assertTrue(body.contains("first-keep")); assertTrue(body.contains("first-attachment")); assertFalse(body.contains("discard-this"))
                    assertEquals("firstsecond", file.readText())
                    assertEquals(branch, dao.harnessSession(id)!!.sessionId)
                    assertEquals(listOf("first-keep", "replacement"), dao.history(id).filter { row -> row.role == "user" }.map { row -> row.content })
                    assertEquals(1, io.github.easylsp.app.feature.chat.messageAttachments(dao.history(id).first { row -> row.role == "user" }).length())
                    assertTrue(dao.history(branch).any { row -> row.content == "discard-this" })
                    val usage = dao.usageHistory(id)
                    assertEquals(200L, summarizeUsage(usage.filter { row -> row.sessionId == branch }).total.tokens)
                    assertEquals(300L, summarizeUsage(usage).total.tokens)
                    val before = dao.history(id)
                    late.accept(JSONObject().put("method", "session.event").put("params", JSONObject().put("sessionId", old.sessionId).put("event", JSONObject().put("seq", old.lastSeq + 1))))
                    assertEquals(before, dao.history(id))
                    graph.harness.stop(); graph.harness.start(); graph.chat.executionService().reconnect(); graph.chat.observe(id).await()
                    assertEquals(before, dao.history(id)); assertEquals(300L, summarizeUsage(dao.usageHistory(id)).total.tokens)
                    server.enqueue(reply("restart-ok")); graph.chat.submit(id, "after-restart", null, false, JSONArray()).await(); finished(id)
                    val restored = request(server).toString(); assertTrue(restored.contains("replacement")); assertFalse(restored.contains("discard-this"))
                    assertEquals("firstsecond", file.readText())
                    val firstUser = dao.history(id).first { row -> row.role == "user" }; val emptyBranch = UUID.randomUUID().toString()
                    server.enqueue(reply("new-first-ok")); graph.chat.submit(id, "new-first", null, false, JSONArray(), editedMessageId = firstUser.id, branchId = emptyBranch).await(); finished(id)
                    val empty = request(server).toString(); assertFalse(empty.contains("first-keep")); assertFalse(empty.contains("replacement")); assertFalse(empty.contains("first-attachment"))
                    assertEquals("firstsecond", file.readText())
                    File(graph.filesDir, "phase5-rewind-evidence.json").writeText(JSONObject().put("exactPrefix", true).put("attachments", true).put("oldWritesReplayed", 0).put("lateEventsIgnored", true).put("restartRestored", true).put("actualTokens", summarizeUsage(dao.usageHistory(id)).total.tokens).toString(2))
                } finally { graph.chat.stop(); withTimeout(45000) { graph.chat.activeConversation.first { it == null } }; cleanup(id, project.id, listOf(p)); file.delete() }
            }
        }
    }
    @Test fun singleOperationApprovalNeverUpgradesConversationAndModeSurvivesModelRestart() = runBlocking(Dispatchers.IO) {
        ActivityScenario.launch(MainActivity::class.java).use {
            val dao = graph.store.workflows; val project = graph.store.select("io.github.easylsp.demo", "Easylsp Demo", "1")
            val id = UUID.randomUUID().toString(); val a = UUID.randomUUID().toString(); val b = UUID.randomUUID().toString()
            MockWebServer().use { server ->
                for (p in listOf(a, b)) { dao.saveProvider(ProviderEntity(p, "Phase5 approval", "openai", server.url("/v1").toString(), p, maxSteps = 1, options = "{\"contextWindow\":32768}")); graph.secrets.put("provider:$p", "fixture") }
                dao.insertConversation(ConversationEntity(id, project.id, a, a, "第五阶段授权验证", project.sampleRevision, System.currentTimeMillis()))
                try {
                    server.enqueue(reply(command = "printf phase5-one")); graph.chat.submit(id, "one", null, false, JSONArray()).await()
                    val one = approval(id)
                    val firstUser = dao.history(id).first { row -> row.role == "user" }
                    val draft = ChatDraftEntity(id, "keep-edit-draft", "[]", firstUser.id, UUID.randomUUID().toString()); dao.saveDraft(draft)
                    assertTrue(runCatching { graph.chat.submit(id, draft.text, null, false, JSONArray(), editedMessageId = draft.editedMessageId, branchId = draft.branchId).await() }.isFailure)
                    assertEquals(draft, dao.draft(id))
                    graph.chat.submit(id, "queued-two", null, false, JSONArray()).await()
                    graph.interactions.answer(one.id, JSONObject().put("allowed", true)); finished(id); request(server)
                    assertEquals("CONFIRM", dao.conversation(id)!!.authorizationMode); assertFalse(dao.harnessSession(id)!!.authorized)
                    server.enqueue(reply(command = "printf phase5-two")); graph.chat.sendQueued(id)
                    val two = approval(id); assertNotEquals(one.id, two.id); graph.interactions.answer(two.id, JSONObject().put("allowed", true)); finished(id); request(server)
                    graph.chat.authorizeTask(id, true).await(); graph.chat.selectModel(id, b).await()
                    graph.harness.stop(); graph.harness.start(); graph.chat.executionService().reconnect(); graph.chat.observe(id).await()
                    assertEquals("ALLOW", dao.conversation(id)!!.authorizationMode)
                    server.enqueue(reply(command = "printf phase5-three")); graph.chat.submit(id, "ordinary-next", null, false, JSONArray()).await(); finished(id)
                    assertEquals(b, request(server).getString("model")); assertEquals(2, dao.interactions(id).first().size)
                    graph.chat.authorizeTask(id, false).await()
                    server.enqueue(reply(command = "printf phase5-four")); graph.chat.submit(id, "continue-after-mode-change", null, false, JSONArray()).await()
                    val four = approval(id); graph.interactions.answer(four.id, JSONObject().put("allowed", false)); finished(id); request(server)
                    assertEquals("CONFIRM", dao.conversation(id)!!.authorizationMode)
                    File(graph.filesDir, "phase5-authorization-evidence.json").writeText("{\"singleOperationOnly\":true,\"queuePreservesMode\":true,\"modelAndRestartPreserveMode\":true,\"runningEditRetainsDraft\":true,\"explicitModeChange\":true}")
                } finally { graph.chat.stop(); withTimeout(45000) { graph.chat.activeConversation.first { it == null } }; cleanup(id, project.id, listOf(a, b)) }
            }
        }
    }
    @Test fun savedR9DiagnosticImportsIntoStatusWithoutLaunchingTarget() = runBlocking(Dispatchers.IO) {
        val project = requireNotNull(graph.store.dao.projectByPackage("cn.jthj.turbobunny"))
        val id = "71781493-5d74-46ac-b1e5-dec116dd0591"
        val report = graph.diagnostics.read(project.id, id)
        val first = graph.nativeInstances.events(project.id)
        val saved = first.filter { it.optString("diagnosticRunId") == id || it.getJSONArray("sources").toString().contains(id) }
        val rawRows = File(graph.diagnostics.store.directory(project.id, id), "native-plugin-events.jsonl").readLines().count { it.isNotBlank() }
        assertEquals(261, rawRows)
        assertEquals(216, saved.size)
        graph.diagnostics.read(project.id, id)
        assertEquals(first.map { it.toString() }, graph.nativeInstances.events(project.id).map { it.toString() })
        val demo = graph.store.select("io.github.easylsp.demo", "Easylsp Demo", "1")
        val isolatedId = UUID.randomUUID().toString()
        val isolated = graph.diagnostics.store.directory(demo.id, isolatedId).apply { mkdirs() }
        val beforeDemo = graph.nativeInstances.events(demo.id).map { it.toString() }
        val demoEventsFile = File(graph.filesDir, "native-instances/${demo.id}/events.jsonl")
        val beforeModified = demoEventsFile.lastModified()
        graph.diagnostics.store.dao.save(DiagnosticEntity(isolatedId, demo.id, demo.sampleRevision, demo.packageName, "NORMAL", 1000, 2000, "COMPLETE"))
        try {
            File(graph.diagnostics.store.directory(project.id, id), "native-plugin-events.jsonl").copyTo(File(isolated, "native-plugin-events.jsonl"))
            assertEquals(0, graph.nativeInstances.importDiagnostic(demo.id, isolatedId, JSONObject().put("projectId", demo.id).put("uid", report.getInt("uid"))))
            assertEquals(beforeDemo, graph.nativeInstances.events(demo.id).map { it.toString() })
            assertEquals(beforeModified, demoEventsFile.lastModified())
        } finally { graph.diagnostics.store.dao.delete(isolatedId); isolated.deleteRecursively() }
        val plugin = requireNotNull(graph.store.dao.plugin("e6b08885-699d-4902-a06f-a571367e6285"))
        val status = graph.nativeInstances.status(plugin)
        val instances = status.getJSONArray("instances")
        assertTrue((0 until instances.length()).any { instances.getJSONObject(it).getLong("sourceRevision") == 9L && instances.getJSONObject(it).getString("buildId") == "5fdb8e7d-7b8e-4304-a08b-30f5003ebfaf" })
        File(graph.filesDir, "phase5-native-evidence.json").writeText(JSONObject().put("rawDiagnosticRows", rawRows).put("importedEvents", saved.size).put("idempotentImport", true).put("crossProjectFiltered", true).put("instances", instances).put("targetLaunched", false).toString(2))
    }
}
