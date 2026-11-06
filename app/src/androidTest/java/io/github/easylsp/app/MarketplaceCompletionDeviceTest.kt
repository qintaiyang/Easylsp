package io.github.easylsp.app

import android.app.Notification
import android.app.NotificationManager
import android.content.*
import android.os.IBinder
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.github.easylsp.app.data.*
import io.github.easylsp.app.platform.harness.*
import io.github.easylsp.app.platform.tools.*
import kotlinx.coroutines.*
import org.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import okhttp3.mockwebserver.*

/** Tests only owned fixture sessions; user projects, models and credentials are preserved. */
class MarketplaceCompletionDeviceTest {
    @get:org.junit.Rule(order = 0) val fixtureModel = FixtureModelRule()
    @get:org.junit.Rule(order = 1) val visible = VisibleActivityRule()
    private val graph get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as EasylspApp
    private suspend fun notification(expected: String): android.service.notification.StatusBarNotification = withTimeout(20000) {
        while (true) {
            val row = graph.getSystemService(NotificationManager::class.java).activeNotifications.firstOrNull { it.id == 31 }
            if (row?.notification?.extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString() == expected) return@withTimeout row
            delay(50)
        }
        error("unreachable")
    }
    @Test fun realForegroundStatesOwnershipDeletionAndReconnect() = runBlocking<Unit>(Dispatchers.IO) {
        val activity = ActivityScenario.launch<MainActivity>(Intent(graph, MainActivity::class.java))
        val bound = CompletableDeferred<HarnessExecutionService>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) { bound.complete((binder as HarnessExecutionService.LocalBinder).service) }
            override fun onServiceDisconnected(name: ComponentName) {}
        }
        val modelGate = CountDownLatch(1)
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val body = JSONObject(request.body.readUtf8())
                val completed = body.getJSONArray("messages").toString().contains("fixture-answer")
                if (!completed) modelGate.await(30, TimeUnit.SECONDS)
                val delta = if (completed) JSONObject().put("content", "完成") else JSONObject().put("tool_calls", JSONArray().put(JSONObject().put("index", 0).put("id", UUID.randomUUID().toString()).put("type", "function").put("function", JSONObject().put("name", "mcp__easylsp__fixture_question").put("arguments", "{}"))))
                fun event(value: JSONObject, finish: Any) = "data: " + JSONObject().put("id", "reply").put("model", "fixture").put("choices", JSONArray().put(JSONObject().put("index", 0).put("delta", value).put("finish_reason", finish))) + "\n\n"
                return MockResponse().setHeader("Content-Type", "text/event-stream").setBody(event(delta, JSONObject.NULL) + event(JSONObject(), if (completed) "stop" else "tool_calls") + "data: [DONE]\n\n")
            }
        }
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        val parent = graph.conversations.create(modelId = fixtureModel.id, title = "通知与删除验收")
        val child = graph.conversations.create(modelId = fixtureModel.id, parent = parent, task = "owned child")
        val fork = graph.conversations.create(modelId = fixtureModel.id, title = "独立 fork 保留")
        val maintenance = graph.conversations.create(modelId = fixtureModel.id, title = "内部维护", task = "__memory_consolidation__")
        val owned = listOf(parent, child, fork, maintenance)
        val sessions = owned.associate { it.id to UUID.randomUUID().toString() }
        var service: HarnessExecutionService? = null
        var previousConcurrency: Int? = null
        var isBound = false
        val evidence = JSONObject()
        try {
            withContext(Dispatchers.Main) {
                HarnessExecutionService.start(graph)
                isBound = graph.bindService(Intent(graph, HarnessExecutionService::class.java), connection, Context.BIND_AUTO_CREATE)
            }
            val host = withTimeout(60000) { bound.await() }; service = host
            previousConcurrency = host.request("host/sessions").getJSONArray("sessions").let { rows ->
                if (rows.length() > 0) rows.getJSONObject(0).getInt("concurrency") else host.request("host/status").getInt("concurrency")
            }
            host.request("host/configure", JSONObject().put("concurrency", 1))
            val config = JSONObject().put("api", "openai-completions").put("baseURL", server.url("/v1").toString().trimEnd('/')).put("model", "fixture").put("secret", "fixture").put("contextWindow", 32768).put("maxTokens", 1024)
            for (row in owned) {
                graph.store.workflows.saveHarnessSession(HarnessSessionEntity(row.id, sessions.getValue(row.id), sourceConversationId = if (row.id == fork.id) parent.id else null))
                host.open(sessions.getValue(row.id), config, parentSessionId = row.parentConversationId?.let(sessions::getValue)) {
                    val tools = listOf(ToolDefinition("fixture_question", "Owned test interaction", JSONObject().put("type", "object").put("properties", JSONObject()), defaultAvailable = true,
                        invoke = { graph.interactions.request(row.id, "fixture-turn", "QUESTION", JSONObject().put("question", "继续验收？")) }))
                    BoundToolCatalog(tools, ToolCapabilities(tools).apply { resolve() })
                }
            }
            suspend fun prompt(row: ConversationEntity) { host.request("session/prompt", JSONObject().put("sessionId", sessions.getValue(row.id)).put("requestId", UUID.randomUUID().toString()).put("contentBlocks", JSONArray().put(JSONObject().put("type", "text").put("text", "fixture")))) }
            prompt(parent)
            val running = notification("1 个任务执行中")
            evidence.put("running", running.notification.extras.getCharSequence(Notification.EXTRA_TEXT))
            val timestamp = running.postTime
            delay(250)
            assertEquals("unchanged content must not notify again", timestamp, notification("1 个任务执行中").postTime)
            prompt(child); prompt(fork)
            notification("1 个任务执行中 · 1 个任务排队")
            modelGate.countDown()
            notification("1 个任务排队 · 1 个任务等待你的回复或授权")
            // PendingIntent must point to the latest route. Single and multiple states both update it.
            notification("1 个任务排队 · 1 个任务等待你的回复或授权").notification.contentIntent.send()
            delay(300)
            activity.onActivity { assertEquals("conversations", it.intent.getStringExtra("route")) }
            evidence.put("multiClick", "conversations")
            val field = HarnessExecutionService::class.java.getDeclaredField("connection").apply { isAccessible = true }
            (field.get(host) as HarnessConnection).close()
            notification("执行服务已断开，任务状态待确认")
            host.reconnect()
            notification("1 个任务排队 · 1 个任务等待你的回复或授权")
            val media = File(graph.filesDir, "conversation-media/${parent.id}/fixture").apply { parentFile!!.mkdirs(); writeText("owned") }
            graph.toolOutputs.pageResult(parent.id, JSONObject().put("text", "x".repeat(13000)))
            graph.store.workflows.saveDraft(ChatDraftEntity(parent.id, "draft"))
            graph.localManagement.deleteConversation(parent.id)
            assertNull(graph.store.workflows.conversation(parent.id)); assertNull(graph.store.workflows.conversation(child.id))
            assertNotNull(graph.store.workflows.conversation(fork.id)); assertNull(graph.store.workflows.harnessSession(fork.id)!!.sourceConversationId)
            assertFalse(media.exists()); assertFalse(File(graph.filesDir, "tool-output/${parent.id}").exists())
            val remaining = host.request("host/sessions").getJSONArray("sessions").toString()
            assertFalse(remaining.contains(sessions.getValue(parent.id))); assertFalse(remaining.contains(sessions.getValue(child.id)))
            notification("1 个任务等待你的回复或授权").notification.contentIntent.send()
            delay(300)
            activity.onActivity { assertEquals("chat/${fork.id}", it.intent.getStringExtra("route")) }
            val interaction = withTimeout(10000) { while (graph.store.workflows.interactionHistory(fork.id).none { it.status == "PENDING" }) delay(30); graph.store.workflows.interactionHistory(fork.id).last { it.status == "PENDING" } }
            graph.interactions.answer(interaction.id, JSONObject().put("answer", "fixture-answer"))
            notification("已连接，当前无任务")
            host.request("host/configure", JSONObject().put("concurrency", 2))
            prompt(maintenance)
            notification("后台整理中")
            graph.localManagement.deleteConversation(maintenance.id)
            notification("已连接，当前无任务")
            host.reconnect()
            assertNull(graph.store.workflows.conversation(parent.id)); assertTrue(graph.store.workflows.history(parent.id).isEmpty())
            evidence.put("states", JSONArray(listOf("running", "queued", "waiting", "disconnected", "reconnected", "maintenance", "idle")))
                .put("ownedChildRemoved", true).put("independentForkRetained", true).put("lateReconnectDoesNotRestoreDeletedRows", true)
            File(graph.filesDir, "marketplace-completion-evidence.json").writeText(evidence.toString(2))
        } finally {
            modelGate.countDown()
            owned.forEach { if (graph.store.workflows.conversation(it.id) != null) graph.localManagement.deleteConversation(it.id) }
            previousConcurrency?.let { service?.request("host/configure", JSONObject().put("concurrency", it)) }
            withContext(Dispatchers.Main) { if (isBound) graph.unbindService(connection) }
            server.shutdown(); activity.close()
        }
    }

    @Test fun idleServiceStopRemovesForegroundNotificationAndCanRestart() = runBlocking<Unit>(Dispatchers.IO) {
        val activity = ActivityScenario.launch(MainActivity::class.java)
        try {
            val host = graph.chat.executionService()
            val sessions = host.request("host/sessions").getJSONArray("sessions")
            check((0 until sessions.length()).none { sessions.getJSONObject(it).getString("status") in setOf("running", "queued") }) { "Do not stop user tasks" }
            val idle = notification("已连接，当前无任务")
            assertEquals("停止执行服务", idle.notification.actions.single().title.toString())
            idle.notification.actions.single().actionIntent.send()
            withTimeout(15000) { host.ended.await() }
            withTimeout(10000) { while (graph.getSystemService(NotificationManager::class.java).activeNotifications.any { it.id == 31 }) delay(50) }
            delay(300) // Let the client release its ended service binding.
            graph.chat.executionService()
            notification("已连接，当前无任务")
        } finally { activity.close() }
    }

    @Test fun failedBackendRevocationRetainsProjectAndOwnedData() = runBlocking<Unit>(Dispatchers.IO) {
        val project = ProjectEntity(UUID.randomUUID().toString(), "io.github.easylsp.test", "撤销失败验收", "1", nativeBackend = "ZYGISK")
        graph.store.dao.insertProject(project)
        val row = graph.conversations.create(project.id, modelId = fixtureModel.id)
        val plugin = graph.store.create(project, "Unpublished fixture", "/* no executable code */", runtime = "NATIVE")
        val file = File(graph.executions.directory(project.id), "retained.txt").apply { writeText("retain on failure") }
        val preference = graph.root.preferred.value
        try {
            graph.root.prefer(false)
            val error = runCatching { graph.localManagement.deleteProject(project.id) }.exceptionOrNull()
            assertNotNull(error)
            assertNotNull(graph.store.dao.project(project.id)); assertNotNull(graph.store.dao.plugin(plugin.id))
            assertNotNull(graph.store.workflows.conversation(row.id)); assertTrue(file.isFile)
        } finally {
            graph.root.prefer(preference)
            graph.localManagement.deleteProject(project.id)
        }
        assertFalse(file.exists()); assertNull(graph.store.dao.project(project.id))
    }

    @Test fun projectConversationDeletionRetainsSharedWorkspaceAndGlobalToolsExplainBindings() = runBlocking<Unit>(Dispatchers.IO) {
        val id = UUID.randomUUID().toString()
        val project = ProjectEntity(id, "fixture.delete.$id", "Delete fixture", "1", kind = "IMPORTED")
        graph.store.dao.insertProject(project)
        val row = graph.conversations.create(id, modelId = fixtureModel.id)
        val other = graph.conversations.create(id, modelId = fixtureModel.id)
        val workspace = graph.executions.directory(id)
        val shared = File(workspace, "shared.txt").apply { writeText("retained") }
        val global = graph.conversations.create(modelId = fixtureModel.id)
        try {
            graph.localManagement.deleteConversation(row.id)
            assertTrue(shared.isFile); assertNotNull(graph.store.workflows.conversation(other.id))
            val projectBinding = RunBinding(project, 0, null, other.id)
            val projectTools = graph.tools.builtins(projectBinding)
            for (name in listOf("get_project_info", "capture_sample")) assertNull(graph.tools.unavailableReason(projectTools.first { it.name == name }, projectBinding))
            assertTrue(graph.tools.unavailableReason(projectTools.first { it.name == "search_classes" }, projectBinding)!!.contains("样本"))
            assertTrue(graph.tools.unavailableReason(projectTools.first { it.name == "load_plugin" }, projectBinding)!!.contains("在线 Java 目标进程"))
            val binding = RunBinding(null, 0, null, global.id)
            graph.executions.bind(binding.workspace)
            val tools = graph.tools.builtins(binding)
            val unavailable = tools.mapNotNull { tool -> graph.tools.unavailableReason(tool, binding)?.let { tool.name to it } }.toMap()
            val catalog = ToolCapabilities(tools, unavailable)
            val search = catalog.search(JSONObject().put("query", "get_project_info"))
            assertTrue(search.toString(), search.toString().contains("需要真实项目绑定"))
            assertTrue(tools.any { it.name == "prepare_plugin_release" && it.writes })
            assertTrue(tools.any { it.name == "publish_plugin_release" && it.writes })
            val fileTool = tools.first { it.name == "list_files" }
            fileTool.invoke(JSONObject())
            assertTrue(catalog.resolve().getJSONArray("activeTools").toString().contains("run_command"))
        } finally {
            graph.localManagement.deleteProject(id)
            graph.localManagement.deleteConversation(global.id)
        }
        assertFalse(workspace.exists()); assertNull(graph.store.workflows.conversation(other.id))
    }
}
