package io.github.easylsp.app

import android.content.*
import android.os.IBinder
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.github.easylsp.app.platform.harness.HarnessExecutionService
import io.github.easylsp.app.platform.harness.HarnessProjection
import io.github.easylsp.app.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.mockwebserver.*
import okio.Buffer
import org.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.net.InetAddress
import io.github.easylsp.app.platform.tools.RunBinding
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Real app foreground service -> private chroot daemon -> pinned SDK -> Android MCP. */
class HarnessServiceDeviceTest {
    @get:Rule val visible = VisibleActivityRule()
    @Test fun installedRuntimeSurvivesActivityRecreationAndClientReconnect() = runBlocking(Dispatchers.IO) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val screenOffSeconds = InstrumentationRegistry.getArguments().getString("screenOffSeconds", "20").toLong()
        val graph = instrumentation.targetContext.applicationContext as EasylspApp
        val activity = ActivityScenario.launch(MainActivity::class.java)
        val server = MockWebServer()
        val requests = AtomicInteger(); val calls = AtomicInteger()
        val release = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val index = requests.incrementAndGet()
                val body = JSONObject(request.body.readUtf8())
                assertTrue(body.getJSONArray("tools").toString().contains("mcp__easylsp__get_project_info"))
                val delta = if (index == 1) JSONObject("{\"tool_calls\":[{\"index\":0,\"id\":\"project-read\",\"type\":\"function\",\"function\":{\"name\":\"mcp__easylsp__get_project_info\",\"arguments\":\"{}\"}}]}") else JSONObject().put("content", "设备服务继续运行")
                fun event(value: JSONObject, finish: Any) = "data: " + JSONObject().put("id", "reply-$index").put("model", "fixture").put("choices", JSONArray().put(JSONObject().put("index", 0).put("delta", value).put("finish_reason", finish))).toString() + "\n\n"
                val first = event(delta, JSONObject.NULL)
                val stream = first + event(JSONObject(), if (index == 1) "tool_calls" else "stop") + "data: [DONE]\n\n"
                return MockResponse().setHeader("Content-Type", "text/event-stream").setBody(Buffer().writeUtf8(stream)).apply {
                    if (index == 2) throttleBody(first.toByteArray().size.toLong(), 3, TimeUnit.SECONDS)
                }
            }
        }
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        val bound = CompletableDeferred<HarnessExecutionService>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) { bound.complete((binder as HarnessExecutionService.LocalBinder).service) }
            override fun onServiceDisconnected(name: ComponentName) {}
        }
        var isBound = false
        val sessionId = "device-${UUID.randomUUID()}"
        val conversationId = "projection-$sessionId"
        val fixtureProvider = graph.models.configureProvider(null, JSONObject().put("name", "Harness service fixture").put("protocol", "openai").put("baseUrl", server.url("/v1").toString()))
        val fixtureModel = graph.models.configureModel(null, fixtureProvider.id, "fixture", JSONObject())
        try {
            if (!graph.harness.installed) graph.harness.install()
            withContext(Dispatchers.Main) {
                HarnessExecutionService.start(graph)
                isBound = graph.bindService(Intent(graph, HarnessExecutionService::class.java), connection, Context.BIND_AUTO_CREATE)
                check(isBound)
            }
            val service = withTimeout(60000) { bound.await() }
            val config = JSONObject().put("api", "openai-completions").put("baseURL", server.url("/v1").newBuilder().host("127.0.0.1").build().toString().trimEnd('/'))
                .put("model", "fixture").put("secret", "fixture-key").put("contextWindow", 32768).put("maxTokens", 1024)
            val project = graph.store.select("io.github.easylsp.demo", "Easylsp Demo", "1")
            graph.store.workflows.insertConversation(ConversationEntity(conversationId, project.id, fixtureProvider.id, "fixture", "Harness 服务验收", project.sampleRevision, System.currentTimeMillis(), modelConfigId = fixtureModel.id))
            graph.store.workflows.saveHarnessSession(HarnessSessionEntity(conversationId, sessionId))
            service.open(sessionId, config) {
                val tool = graph.tools.builtins(RunBinding(project, project.sampleRevision, null, conversationId)).first { it.name == "get_project_info" }
                val tools = listOf(tool.copy(defaultAvailable = true, invoke = { args ->
                    calls.incrementAndGet()
                    runInterruptible { release.await() }
                    tool.invoke(args)
                }))
                io.github.easylsp.app.platform.tools.BoundToolCatalog(tools, io.github.easylsp.app.platform.tools.ToolCapabilities(tools).apply { resolve() })
            }
            fun params() = JSONObject().put("sessionId", sessionId)
            val idle = async(start = CoroutineStart.UNDISPATCHED) { withTimeout(60000) { service.events.first { it.optString("method") == "session.event" && it.getJSONObject("params").getJSONObject("event").getString("type") == "turn/end" } } }
            val receipt = service.request("session/prompt", params().put("requestId", "one-input").put("contentBlocks", JSONArray().put(JSONObject().put("type", "text").put("text", "读取项目"))))
            assertEquals("ACCEPTED", receipt.getString("status"))
            withTimeout(30000) { while (calls.get() == 0) delay(20) }
            val mirror = service.observeConversation(conversationId)
            assertEquals("RUNNING", graph.store.workflows.turnHistory(conversationId).single().status)
            // The tool remains pending through Activity recreation and an independent socket reconnect.
            activity.recreate()
            activity.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
            if (screenOffSeconds > 0) {
                instrumentation.uiAutomation.executeShellCommand("input keyevent 223").use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes() }
                delay(screenOffSeconds * 1000)
            }
            graph.harness.connect().use { client ->
                assertEquals("running", client.request("session/state", params()).getString("status"))
                val duplicate = client.request("session/prompt", params().put("requestId", "one-input"))
                assertEquals(receipt.getString("messageId"), duplicate.getString("messageId"))
            }
            service.reconnect()
            assertEquals("running", mirror.status.value)
            val live = async(start = CoroutineStart.UNDISPATCHED) { withTimeout(30000) { mirror.live.first { it?.text == "设备服务继续运行" } } }
            release.countDown(); live.await()
            assertEquals("RUNNING", graph.store.workflows.turnHistory(conversationId).single().status)
            service.reconnect()
            assertEquals("设备服务继续运行", mirror.live.value!!.text)
            idle.await()
            val history = service.request("session/history", params().put("after", -1).put("limit", 200))
            val events = history.getJSONArray("events")
            assertTrue((0 until events.length()).any { events.getJSONObject(it).getString("type") == "assistant/message" })
            assertEquals(1, calls.get()); assertEquals(2, requests.get())
            val projection = HarnessProjection(graph.store)
            repeat(2) { for (index in 0 until events.length()) projection.apply(conversationId, events.getJSONObject(index)) }
            mirror.synchronize()
            assertNull(mirror.live.value)
            val projected = graph.store.workflows.history(conversationId)
            assertEquals(3, projected.size)
            assertEquals(3, projected.map { it.engineMessageId }.distinct().size)
            assertEquals("COMPLETE", graph.store.workflows.turnHistory(conversationId).single().status)
            assertEquals(1, graph.store.workflows.toolRuns(conversationId).first().size)
            val cursor = graph.store.workflows.harnessSession(conversationId)!!.lastSeq
            val gap = runCatching { projection.apply(conversationId, JSONObject().put("seq", cursor + 2)) }.exceptionOrNull()
            assertTrue(gap!!.message!!.contains("事件缺失"))
            assertEquals(cursor, graph.store.workflows.harnessSession(conversationId)!!.lastSeq)
            if (screenOffSeconds > 0) instrumentation.uiAutomation.executeShellCommand("input keyevent 224").use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes() }
            activity.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
            File(graph.filesDir, "phase3-harness-service-evidence.json").writeText(JSONObject().put("installedByApp", true).put("foregroundService", true)
                .put("activityRecreated", true).put("activityStoppedDuringTool", true).put("screenOffSeconds", screenOffSeconds).put("reconnected", true).put("duplicateInputsDispatched", 0).put("modelRequests", requests.get())
                .put("projectionReplayedWithoutDuplicates", true).put("projectionMessages", projected.size).put("projectionCursor", cursor)
                .put("serviceSocketReconnectedDuringToolAndStream", true).put("liveBeforeCommit", true).put("liveRestoredFromSnapshot", true)
                .put("toolCalls", calls.get()).put("historyEvents", history.getJSONArray("events").length()).toString(2))
        } finally {
            release.countDown()
            graph.harness.stop()
            withContext(Dispatchers.Main) { if (isBound) graph.unbindService(connection); graph.stopService(Intent(graph, HarnessExecutionService::class.java)) }
            graph.store.workflows.conversation(conversationId)?.let { row -> graph.store.transaction {
                graph.store.workflows.deleteMessages(conversationId); graph.store.workflows.deleteTurns(conversationId); graph.store.workflows.deleteToolRuns(conversationId)
                graph.store.workflows.deleteConversation(requireNotNull(row.projectId), conversationId)
            } }
            graph.models.configureProvider(fixtureProvider.id, JSONObject().put("enabled", false))
            server.shutdown(); activity.close()
        }
    }
}
