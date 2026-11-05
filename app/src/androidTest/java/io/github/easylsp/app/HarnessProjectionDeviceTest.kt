package io.github.easylsp.app

import androidx.test.platform.app.InstrumentationRegistry
import io.github.easylsp.app.data.*
import io.github.easylsp.app.platform.harness.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class HarnessProjectionDeviceTest {
    @Test fun engineExitRetainsCompressedPartialTextAndUnknownToolResult() = runBlocking(Dispatchers.IO) {
        val graph = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as EasylspApp
        val dao = graph.store.workflows
        val project = graph.store.select("io.github.easylsp.demo", "Easylsp Demo", "1")
        val id = UUID.randomUUID().toString(); val engine = UUID.randomUUID().toString()
        dao.insertConversation(ConversationEntity(id, project.id, "fixture", "fixture", "Projection", project.sampleRevision, System.currentTimeMillis()))
        dao.saveHarnessSession(HarnessSessionEntity(id, engine))
        try {
            val projection = HarnessProjection(graph.store)
            fun event(seq: Int, type: String, data: String) = JSONObject().put("seq", seq).put("type", type).put("time", 1000 + seq).put("data", JSONObject(data))
            projection.apply(id, event(0, "turn/start", """{"turn":1}"""))
            val partial = event(1, "assistant/attempt", """{"turn":1,"step":1,"stream":[{"type":"text-chunks","texts":["已查询","签名"]},{"type":"reasoning-chunks","texts":["验证","实际状态"]}]}""")
            projection.apply(id, partial); projection.apply(id, partial)
            projection.apply(id, event(2, "tool/call", """{"name":"mcp__easylsp__load_plugin","callId":"write-one","arguments":"{}"}"""))
            val client = HarnessSessionClient(graph.store, id) { _, _ -> error("退出通知不能请求模型或重放工具") }
            suspend fun stream(frame: JSONObject) = client.accept(JSONObject().put("method", "session.stream").put("params", JSONObject().put("sessionId", engine).put("frame", frame)))
            stream(JSONObject().put("type", "start").put("revision", 1).put("attemptId", "live-two").put("responseIndex", 1).put("turn", 1).put("step", 2))
            stream(JSONObject().put("type", "chunk").put("revision", 2).put("attemptId", "live-two").put("index", 0)
                .put("chunk", JSONObject().put("type", "text-delta").put("text", "尚未提交的正文")))
            client.accept(JSONObject().put("method", "host.engine-exit").put("params", JSONObject().put("sessionId", engine).put("code", 1)))
            client.accept(JSONObject().put("method", "host.engine-exit").put("params", JSONObject().put("sessionId", engine).put("code", 1)))
            assertEquals("exited", client.status.value)
            assertEquals("HOST", dao.turnHistory(id).single().errorSource)
            assertEquals("UNKNOWN", dao.toolRuns(id).first().single().status)
            val messages = dao.history(id)
            assertEquals(2, messages.size)
            val message = messages.first()
            assertEquals("已查询签名", message.content); assertEquals("验证实际状态", JSONObject(message.blocks).getString("reasoning"))
            assertEquals("INTERRUPTED", message.status)
            assertEquals("尚未提交的正文", messages.last().content)
            assertEquals("INTERRUPTED", messages.last().status)
        } finally {
            graph.store.transaction { dao.deleteMessages(id); dao.deleteToolRuns(id); dao.deleteTurns(id); dao.deleteConversation(project.id, id) }
        }
    }
}
