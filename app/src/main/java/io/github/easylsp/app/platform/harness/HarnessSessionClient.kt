package io.github.easylsp.app.platform.harness

import io.github.easylsp.app.data.ProjectStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/** Reads the engine's history and live output. It never submits input or dispatches tools. */
class HarnessSessionClient(
    private val store: ProjectStore,
    private val conversationId: String,
    private val request: suspend (String, JSONObject) -> JSONObject,
) {
    data class Assistant(val attemptId: String, val turn: Int, val step: Int, val text: String = "", val reasoning: String = "", val displayKey: String = "")
    private val dao = store.workflows
    internal val sessionId = requireNotNull(dao.harnessSession(conversationId)).sessionId
    private val projection = HarnessProjection(store)
    private val serial = Mutex()
    private val current = MutableStateFlow<Assistant?>(null)
    private val state = MutableStateFlow("detached")
    val live = current.asStateFlow()
    val status = state.asStateFlow()
    private var revision = 0L
    private var nextIndex = 0
    private fun params() = JSONObject().put("sessionId", sessionId)

    suspend fun synchronize() = serial.withLock { synchronizeLocked() }

    private suspend fun history() {
        val mapping = requireNotNull(dao.harnessSession(conversationId))
        if (mapping.sessionId != sessionId) return
        if (mapping.projectionVersion == 0) {
            var cursor = -1L; var turn = 0; var step = 0
            do {
                val page = request("session/history", params().put("after", cursor).put("limit", 200))
                val events = page.getJSONArray("events")
                for (i in 0 until events.length()) {
                    val event = events.getJSONObject(i); val data = event.getJSONObject("data")
                    if (event.getString("type") == "turn/start") { turn = data.getInt("turn"); step = 0 }
                    if (event.getString("type") == "step/start") step = data.getInt("step")
                    projection.index(conversationId, event, turn, step)
                    projection.apply(conversationId, event, sessionId)
                }
                cursor = page.getLong("next")
            } while (page.getBoolean("hasMore"))
            dao.harnessSession(conversationId)?.takeIf { it.sessionId == sessionId }?.let { dao.saveHarnessSession(it.copy(projectionVersion = 1)) }
        }
        do {
            val cursor = requireNotNull(dao.harnessSession(conversationId)).lastSeq
            val page = request("session/history", params().put("after", cursor).put("limit", 200))
            val events = page.getJSONArray("events")
            for (index in 0 until events.length()) projection.apply(conversationId, events.getJSONObject(index), sessionId)
        } while (page.getBoolean("hasMore"))
    }

    private suspend fun synchronizeLocked() {
        var snapshot = request("session/state", params())
        if (snapshot.getString("status") != "detached") { history(); snapshot = request("session/state", params()) }
        state.value = snapshot.getString("status")
        current.value = null; revision = 0; nextIndex = 0
        snapshot.optJSONObject("stream")?.let { snapshotStream ->
            stream(snapshotStream.getJSONObject("start"))
            val chunks = snapshotStream.getJSONArray("chunks")
            for (index in 0 until chunks.length()) stream(chunks.getJSONObject(index))
        }
    }

    internal suspend fun accept(frame: JSONObject) = serial.withLock {
        val params = frame.getJSONObject("params")
        if (params.optString("sessionId") != sessionId || dao.harnessSession(conversationId)?.sessionId != sessionId) return@withLock
        when (frame.getString("method")) {
            "session.event" -> {
                val event = params.getJSONObject("event")
                val cursor = requireNotNull(dao.harnessSession(conversationId)).lastSeq
                if (event.getLong("seq") > cursor + 1) history()
                projection.apply(conversationId, event, sessionId)

            }
            "session.stream" -> {
                val part = params.getJSONObject("frame")
                if (part.getString("type") == "chunk" && part.getLong("revision") > revision && (current.value == null || part.getInt("index") > nextIndex)) synchronizeLocked()
                else stream(part)
            }
            "session.status" -> state.value = params.getString("status")
            "host.engine-exit" -> {
                val partial = current.value
                state.value = "exited"; current.value = null
                val time = System.currentTimeMillis()
                store.transaction {
                    if (partial != null && dao.history(conversationId).none { it.displayKey == partial.displayKey } && (partial.text.isNotEmpty() || partial.reasoning.isNotEmpty())) {
                        val conversation = requireNotNull(dao.conversation(conversationId))
                        val mapping = requireNotNull(dao.harnessSession(conversationId))
                        dao.insertMessage(io.github.easylsp.app.data.MessageEntity(conversationId = conversationId, role = "assistant", content = partial.text,
                            blocks = JSONObject().put("reasoning", partial.reasoning).toString(), status = "INTERRUPTED", sampleRevision = conversation.sampleRevision,
                            sessionId = mapping.targetSessionId, createdAt = time, engineMessageId = "stream:${partial.attemptId}", displayKey = partial.displayKey))
                    }
                    dao.turnHistory(conversationId).lastOrNull()?.takeIf { it.status == "RUNNING" }?.let {
                        dao.saveTurn(it.copy(status = "INTERRUPTED", errorSource = "HOST", errorType = "EngineExit", errorDetail = "会话引擎已退出；恢复历史并查询工具状态后继续", finishedAt = time))
                        dao.unknownTools(conversationId, time)
                    }
                }
            }
        }
    }

    internal fun disconnected() { state.value = "disconnected" }

    private fun stream(frame: JSONObject) {
        val incoming = frame.getLong("revision")
        if (incoming <= revision) return
        when (frame.getString("type")) {
            "start" -> {
                current.value = Assistant(frame.getString("attemptId"), frame.getInt("turn"), frame.getInt("step"), displayKey = "assistant:$sessionId:${frame.getInt("turn")}:${frame.getInt("step")}:${frame.getInt("responseIndex")}")
                nextIndex = 0
            }
            "chunk" -> {
                val live = current.value ?: return
                if (frame.getString("attemptId") != live.attemptId || frame.getInt("index") < nextIndex) return
                check(frame.getInt("index") == nextIndex) { "实时输出缺失，请重新同步会话" }
                val chunk = frame.getJSONObject("chunk")
                current.value = when (chunk.getString("type")) {
                    "text-delta" -> live.copy(text = live.text + chunk.getString("text"))
                    "reasoning-delta" -> live.copy(reasoning = live.reasoning + chunk.getString("text"))
                    else -> live
                }
                nextIndex++
            }
            "end" -> if (frame.getJSONObject("outcome").getString("kind") == "abandoned") current.value = null
        }
        revision = incoming
    }
}
