package io.github.easylsp.app

import androidx.test.platform.app.InstrumentationRegistry
import io.github.easylsp.app.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.mockwebserver.*
import org.json.*
import org.junit.Test
import org.junit.Assert.*
import java.util.UUID
import java.io.File

/** Controlled model transport drives real tools; no claim of a commercial model request. */
class AiWorkflowDeviceTest {
    @get:org.junit.Rule val visible = VisibleActivityRule()
    private val graph = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as EasylspApp
    @Test fun ptyPublishesLiveOutputAcceptsInputAndStopsItsCommand() = runBlocking(Dispatchers.IO) {
        val project = graph.store.select("io.github.easylsp.demo", "Easylsp Demo", "1")
        val id = graph.executions.command(project.id, "printf 'WAITING_FOR_INPUT\\n'; read answer; printf 'ANSWER=%s\\n' \"\$answer\"", interactive = true, timeoutSeconds = 15)
        withTimeout(10000) { while (!graph.executions.store.page(project.id, id, 0, 12000).getString("text").contains("WAITING_FOR_INPUT")) delay(50) }
        assertEquals("RUNNING", graph.executions.requireRecord(project.id, id).state)
        graph.executions.input(project.id, id, "hello-from-ai\n", false)
        val completed = graph.executions.await(project.id, id)
        assertTrue(completed.toString(), completed.getString("text").contains("ANSWER=hello-from-ai"))
        val lateFile = File(graph.executions.directory(project.id), "pty-stop-${UUID.randomUUID()}.txt")
        val running = graph.executions.command(project.id, "printf 'STOP_READY\\n'; sleep 3; printf survived > '${lateFile.name}'", timeoutSeconds = 15)
        withTimeout(10000) { while (!graph.executions.store.page(project.id, running, 0, 12000).getString("text").contains("STOP_READY")) delay(50) }
        graph.executions.stop(project.id, running)
        graph.executions.await(project.id, running)
        delay(3500)
        try { assertFalse("停止后命令不应继续写入文件", lateFile.exists()) } finally { lateFile.delete() }
    }
    @Test fun controlledModelCreatesLoadsVerifiesAndDeletesRealHook() = runBlocking(Dispatchers.IO) {
        val project = graph.store.select("io.github.easylsp.demo", "Easylsp Demo", "1")
        graph.startActivity(requireNotNull(graph.packageManager.getLaunchIntentForPackage(project.packageName)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        val host = withTimeout(15000) { graph.bridge.sessions.first { rows -> rows.values.any { it.packageName == project.packageName } }.values.first { it.packageName == project.packageName } }
        val sample = if (project.sampleRevision == 0L) graph.samples.capture(project) else project
        val providerId = UUID.randomUUID().toString(); val conversationId = UUID.randomUUID().toString()
        var pluginId = ""; var verified = false; var requestNumber = 0
        val source = graph.assets.open("replace-add/main.java").bufferedReader().use { it.readText() }.replace("\n}\nvoid onUnload", "\n    plugin.log(\"AI_ACTUAL=\" + new io.github.easylsp.demo.Calculator().add(2, 3));\n}\nvoid onUnload")
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val messages = JSONObject(request.body.readUtf8()).getJSONArray("messages")
                    val previous = messages.getJSONObject(messages.length() - 1)
                    return when (requestNumber++) {
                        0 -> sse("", "find_method", "find", JSONObject().put("query", "Calculator;->add(II)I"))
                        1 -> { check(previous.getString("content").contains("Calculator;->add(II)I")); sse("", "create_plugin", "create", JSONObject().put("name", "AI 闭环验收").put("source", source)) }
                        2 -> { pluginId = JSONObject(previous.getString("content")).getString("pluginId"); sse("", "plugin_action", "load", JSONObject().put("pluginId", pluginId).put("sessionId", host.id).put("operation", "loadPlugin")) }
                        3 -> { check(!previous.getString("content").contains("\"error\"")); sse("", "read_plugin_logs", "logs", JSONObject().put("pluginId", pluginId)) }
                        4 -> { verified = previous.getString("content").contains("AI_ACTUAL=42"); sse("", "delete_plugin", "delete", JSONObject().put("pluginId", pluginId)) }
                        else -> sse("已验证真实返回值 42，并卸载删除测试插件。")
                    }
                }
            }
            graph.store.workflows.saveProvider(ProviderEntity(providerId, "闭环本地协议验收", "openai", server.url("/v1").newBuilder().host("127.0.0.1").build().toString(), "fixture", maxSteps = 10, options = "{\"contextWindow\":1000000,\"contextBudget\":200000}"))
            graph.secrets.put("provider:$providerId", "fixture-key")
            graph.store.workflows.insertConversation(ConversationEntity(conversationId, project.id, providerId, "fixture", "AI 真机闭环", sample.sampleRevision, System.currentTimeMillis()))
            try {
                graph.chat.send(conversationId, "找到加法方法，修改为返回 42，运行验证，然后清理。", host.id, true).await()
                withTimeout(90000) { graph.chat.activeConversation.first { it == null } }
                assertEquals("已完成", graph.chat.status.value); assertTrue("AI 工具应读到目标真实返回值", verified); assertNull(graph.store.dao.plugin(pluginId))
                File(graph.filesDir, "phase2-model-hook-evidence.json").writeText(JSONObject().put("actual", 42).put("verified", verified).put("requests", requestNumber).put("source", "local controlled model; real analysis, plugin tools and target Hook").toString(2))
            } finally {
                graph.chat.stop()
                if (pluginId.isNotEmpty() && graph.store.dao.plugin(pluginId) != null) { graph.bridge.commandSession(host.id, "unloadPlugin", pluginId); graph.store.delete(pluginId) }
                graph.store.transaction { graph.store.workflows.deleteMessages(conversationId); graph.store.workflows.deleteToolRuns(conversationId); graph.store.workflows.deleteConversation(project.id, conversationId); graph.store.workflows.deleteProvider(providerId) }
            }
        }
    }
    @Test fun realPtyAndAiQuestionCompleteWithoutTechnicalPageInput() = runBlocking(Dispatchers.IO) {
        val project = graph.store.select("io.github.easylsp.demo", "Easylsp Demo", "1")
        val execution = graph.executions.command(project.id, "printf '\\033[31mPTY_OK\\033[0m\\n'; test -t 0 && test -t 1 && printf 'TTY_ACTUAL=yes\\n'; stty size", timeoutSeconds = 15)
        val result = graph.executions.await(project.id, execution)
        assertEquals(result.toString(), "COMPLETE", result.getString("state")); assertTrue(result.getString("text").contains("TTY_ACTUAL=yes")); assertTrue(result.getString("text").contains("PTY_OK"))
        val entry = graph.terminals.entries.value.first { it.id == execution }
        assertEquals(90, entry.session.emulator.mColumns)
        val providerId = UUID.randomUUID().toString(); val conversationId = UUID.randomUUID().toString()
        MockWebServer().use { server ->
            server.enqueue(sse("", "ask_user", "question-call", JSONObject().put("question", "选择演示风格").put("options", JSONArray().put("简洁").put("详细"))))
            server.enqueue(sse("已收到取消，本轮结束。"))
            graph.store.workflows.saveProvider(ProviderEntity(providerId, "本地协议验收", "openai", server.url("/v1").newBuilder().host("127.0.0.1").build().toString(), "fixture", options = "{\"contextWindow\":1000000,\"contextBudget\":200000}"))
            graph.secrets.put("provider:$providerId", "fixture-key")
            graph.store.workflows.insertConversation(ConversationEntity(conversationId, project.id, providerId, "fixture", "提问协议验收", project.sampleRevision, System.currentTimeMillis()))
            try {
                graph.chat.send(conversationId, "先询问我的选择", null, false).await()
                val question = withTimeout(30000) { graph.store.workflows.interactions(conversationId).first { rows -> rows.any { it.status == "PENDING" } } }.last { it.status == "PENDING" }
                graph.interactions.answer(question.id, JSONObject().put("cancelled", true))
                withTimeout(15000) { graph.chat.activeConversation.first { it == null } }
                assertEquals("已完成", graph.chat.status.value)
                val request1 = JSONObject(server.takeRequest().body.readUtf8()); assertTrue(request1.getJSONArray("tools").toString().contains("inspect_target")); assertTrue(request1.getJSONArray("tools").toString().contains("write_plugin_file")); assertTrue(request1.getJSONArray("tools").toString().contains("run_linux"))
                val request2 = JSONObject(server.takeRequest().body.readUtf8()); assertTrue(request2.getJSONArray("messages").toString().contains("cancelled"))
                File(graph.filesDir, "phase2-ai-evidence.json").writeText(JSONObject().put("pty", result).put("toolCount", request1.getJSONArray("tools").length()).put("questionCancelled", true).put("source", "local controlled model service; real Android PTY and tool execution").toString(2))
            } finally {
                graph.chat.stop(); graph.store.transaction { graph.store.workflows.deleteMessages(conversationId); graph.store.workflows.deleteToolRuns(conversationId); graph.store.workflows.deleteConversation(project.id, conversationId); graph.store.workflows.deleteProvider(providerId) }
            }
        }
    }
    @Test fun actualContinuousPerformanceAndTargetOverlayCommands() = runBlocking(Dispatchers.IO) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.executeShellCommand("am start -n io.github.easylsp.demo/.DemoActivity").use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes() }
        val session = withTimeout(15000) { graph.bridge.sessions.first { rows -> rows.values.any { it.packageName == "io.github.easylsp.demo" && it.processName == "io.github.easylsp.demo" } }.values.first { it.packageName == "io.github.easylsp.demo" && it.processName == "io.github.easylsp.demo" } }
        graph.bridge.commandSession(session.id, "performanceStart")
        try {
            graph.bridge.commandSession(session.id, "performanceOverlay", arguments = android.os.Bundle().apply { putBoolean("enabled", true) })
            delay(1600)
            val reading = JSONObject(graph.bridge.commandSession(session.id, "performanceRead"))
            assertTrue(reading.getLong("elapsedMs") >= 1000); assertTrue(reading.has("fpsOnePercentLow")); assertTrue(reading.getLong("totalPssKb") > 0)
            val tree = JSONObject(graph.bridge.commandSession(session.id, "viewTree")); assertTrue(tree.getInt("total") > 0)
            val overlay = JSONObject(graph.bridge.commandSession(session.id, "inspectOverlay", arguments = android.os.Bundle().apply { putBoolean("enabled", true); putInt("x", 100); putInt("y", 400) }))
            assertTrue(overlay.getBoolean("enabled"))
            File(graph.filesDir, "phase2-inspect-evidence.json").writeText(JSONObject().put("reading", reading).put("viewCount", tree.getInt("total")).put("overlay", overlay).toString(2))
        } finally {
            graph.bridge.commandSession(session.id, "inspectOverlay", arguments = android.os.Bundle().apply { putBoolean("enabled", false) })
            graph.bridge.commandSession(session.id, "performanceStop")
        }
    }
    private fun sse(text: String, tool: String? = null, id: String = "", arguments: JSONObject = JSONObject()): MockResponse {
        val delta = JSONObject().put("content", text)
        if (tool != null) delta.put("tool_calls", JSONArray().put(JSONObject().put("index", 0).put("id", id).put("type", "function").put("function", JSONObject().put("name", "mcp__easylsp__$tool").put("arguments", arguments.toString()))))
        val event = JSONObject().put("choices", JSONArray().put(JSONObject().put("delta", delta).put("finish_reason", if (tool == null) "stop" else "tool_calls")))
        return MockResponse().setHeader("Content-Type", "text/event-stream").setBody("data: $event\n\ndata: [DONE]\n\n")
    }
}
