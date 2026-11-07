package io.github.easylsp.app.platform.harness

import io.github.easylsp.app.data.*
import org.json.JSONArray
import org.json.JSONObject

/** Rebuildable Room display projection. This class never invokes models or tools. */
class HarnessProjection(private val store: ProjectStore) {
    companion object {
        fun originalToolResult(run: ToolRunEntity, event: JSONObject, time: Long): ToolRunEntity {
            if (event.optJSONObject("surfaceOp")?.optString("op") == "replace" || (run.finishedAt != null && run.status != "UNKNOWN")) return run
            val message = event.getJSONObject("data").getJSONObject("message")
            val content = message.getJSONArray("content")
            val result = (0 until content.length()).map { content.getJSONObject(it) }.filter { it.getString("type") == "text" }.joinToString("\n") { it.getString("text") }
            return run.copy(result = result, status = if (message.optBoolean("isError")) "ERROR" else "COMPLETE", finishedAt = time)
        }
    }
    private val dao = store.workflows
    private fun responseKey(sessionId: String, turnId: String, step: Int, seq: Long) =
        "assistant:$turnId:$step:${dao.precedingResponses(sessionId, turnId, step, seq) + 1}"
    /** Backfill exact event identities for pre-v12 projections without replaying any tools. */
    fun index(conversationId: String, event: JSONObject, number: Int, step: Int) {
        val session = requireNotNull(dao.harnessSession(conversationId))
        val seq = event.getLong("seq")
        val type = event.getString("type")
        val data = event.getJSONObject("data")
        val turnId = "${session.sessionId}:$number"
        if (type == "turn/start") dao.turn(turnId)?.let { dao.saveTurn(it.copy(startSeq = seq)) }
        if (type == "user/message" || type == "assistant/message" || type == "assistant/attempt") {
            val engineId = when (type) { "assistant/message" -> data.getJSONObject("message").getString("id"); "assistant/attempt" -> "attempt:$seq"; else -> data.getString("id") }
            dao.engineMessage(conversationId, engineId)?.let { row -> dao.updateMessage(row.copy(eventSeq = seq, turnId = turnId, step = step,
                displayKey = if (type.startsWith("assistant/")) responseKey(session.sessionId, turnId, step, seq) else "message:${row.id}")) }
            if (type.startsWith("assistant/")) {
                val existing = dao.usageEntry(session.sessionId, seq)
                dao.saveUsage(ChatUsageEntity(session.sessionId, seq, conversationId, existing?.originKey ?: "${session.sessionId}:$seq", turnId, step, responseUsage(data).toString()))
            }
        }
    }
    fun apply(conversationId: String, event: JSONObject, expectedSession: String = requireNotNull(dao.harnessSession(conversationId)).sessionId) = store.transaction {
        var session = requireNotNull(dao.harnessSession(conversationId))
        val seq = event.getLong("seq")
        if (session.sessionId != expectedSession) return@transaction
        if (seq <= session.lastSeq) return@transaction
        check(seq == session.lastSeq + 1) { "会话事件缺失，请先补读历史：${session.lastSeq} → $seq" }
        val conversation = requireNotNull(dao.conversation(conversationId))
        val time = event.getLong("time")
        val data = event.getJSONObject("data")
        fun turnId(number: Int = session.activeTurn) = "${session.sessionId}:$number"
        fun turn() = requireNotNull(dao.turn(turnId()))
        when (event.getString("type")) {
            "turn/start" -> {
                session = session.copy(activeTurn = data.getInt("turn"))
                dao.saveTurn(ChatTurnEntity(turnId(), conversationId, status = "RUNNING", startedAt = time, startSeq = seq))
            }
            "step/start" -> dao.saveTurn(turn().copy(phase = "MODEL", requestCount = data.getInt("step")))
            "user/message", "assistant/message" -> {
                val assistant = event.getString("type") == "assistant/message"
                val message = if (assistant) data.getJSONObject("message") else data
                if (assistant || message.getJSONObject("source").getString("kind") == "user") {
                    val content = message.getJSONArray("content")
                    val blocks = JSONObject().put("harnessContent", content)
                    val calls = JSONArray()
                    val text = StringBuilder(); val reasoning = StringBuilder()
                    for (index in 0 until content.length()) {
                        val part = content.getJSONObject(index)
                        when (part.getString("type")) {
                            "text" -> text.append(part.getString("text"))
                            "reasoning" -> reasoning.append(part.getString("text"))
                            "tool-call" -> calls.put(JSONObject().put("id", part.getString("id")).put("function", JSONObject()
                                .put("name", part.getString("name").removePrefix("mcp__easylsp__")).put("arguments", part.getString("arguments"))))
                        }
                    }
                    if (reasoning.isNotEmpty()) blocks.put("reasoning", reasoning.toString())
                    if (calls.length() > 0) blocks.put("tool_calls", calls)
                    val engineId = message.getString("id")
                    val existing = dao.engineMessage(conversationId, engineId)
                    val original = (existing ?: dao.archivedMessage(conversationId, engineId))?.let { JSONObject(it.blocks) }
                    if (original?.has("originalText") == true) { blocks.put("originalText", original.getString("originalText")); blocks.put("attachments", original.getJSONArray("attachments")); blocks.put("inputIntent", original.optString("inputIntent", "NEW_TASK")) }
                    val row = MessageEntity(conversationId = conversationId, role = if (assistant) "assistant" else "user", content = if (original?.has("originalText") == true) original.getString("originalText") else text.toString(), blocks = blocks.toString(),
                        status = if (data.optBoolean("interrupted")) "INTERRUPTED" else "COMPLETE", sampleRevision = conversation.sampleRevision,
                        sessionId = session.targetSessionId, createdAt = time, usage = data.optJSONObject("usage")?.toString() ?: "{}", engineMessageId = engineId, eventSeq = seq, turnId = turnId(), step = data.optInt("step", turn().requestCount), displayKey = if (assistant) responseKey(session.sessionId, turnId(), data.getInt("step"), seq) else existing?.displayKey.orEmpty())
                    val id = if (existing == null) dao.insertMessage(row) else existing.id.also { dao.updateMessage(row.copy(id = it)) }
                    if (!assistant && turn().userMessageId == 0L) dao.saveTurn(turn().copy(userMessageId = id))
                }
            }
            "assistant/attempt" -> {
                val records = data.getJSONArray("stream")
                val text = StringBuilder(); val reasoning = StringBuilder()
                for (index in 0 until records.length()) {
                    val record = records.getJSONObject(index)
                    if (record.getString("type") == "text-chunks" || record.getString("type") == "reasoning-chunks") {
                        val target = if (record.getString("type") == "text-chunks") text else reasoning
                        val chunks = record.getJSONArray("texts")
                        for (part in 0 until chunks.length()) target.append(chunks.getString(part))
                    }
                    if (record.getString("type") == "chunk") {
                        val chunk = record.getJSONObject("chunk")
                        when (chunk.getString("type")) {
                            "text-delta" -> text.append(chunk.getString("text"))
                            "reasoning-delta" -> reasoning.append(chunk.getString("text"))
                        }
                    }
                }
                if (text.isNotEmpty() || reasoning.isNotEmpty()) dao.insertMessage(MessageEntity(conversationId = conversationId, role = "assistant", content = text.toString(),
                    blocks = JSONObject().put("reasoning", reasoning.toString()).put("harnessAttempt", data).toString(), status = "INTERRUPTED", sampleRevision = conversation.sampleRevision,
                    sessionId = session.targetSessionId, createdAt = time, engineMessageId = "attempt:$seq", eventSeq = seq, turnId = turnId(), step = data.getInt("step"), displayKey = responseKey(session.sessionId, turnId(), data.getInt("step"), seq)))
            }
            "tool/call" -> {
                val name = data.getString("name").removePrefix("mcp__easylsp__")
                val call = data.getString("callId")
                dao.saveToolRun(ToolRunEntity("${session.sessionId}:$call", conversationId, call, name, data.getString("arguments"), status = "RUNNING", startedAt = time))
                dao.saveTurn(turn().copy(phase = "TOOL:$name"))
            }
            "tool/result" -> {
                val message = data.getJSONObject("message")
                val id = "${session.sessionId}:${message.getString("toolCallId")}"
                val run = requireNotNull(dao.toolRun(id))
                val next = originalToolResult(run, event, time)
                val originalResult = next !== run
                if (originalResult) dao.saveToolRun(next)
                if (originalResult && run.name == "update_plan" && !message.optBoolean("isError")) {
                    val items = JSONObject(run.arguments).getJSONArray("items")
                    dao.clearPlan(conversationId)
                    for (i in 0 until items.length()) {
                        val item = items.getJSONObject(i)
                        dao.savePlanItem(PlanItemEntity("${session.sessionId}:plan:$seq:$i", conversationId, i, item.getString("description"), item.getString("status")))
                    }
                }
            }
            "easylsp/limit", "plugin:easylsp/limit" -> dao.saveTurn(turn().copy(status = "LIMIT_REACHED", errorSource = "LIMIT", errorType = "RequestLimit",
                errorDetail = "已达到配置的模型请求上限 ${data.getInt("maxRequests")} 次；可继续已完成的工作"))
            "turn/end" -> {
                val row = turn()
                val reason = data.getJSONObject("reason")
                val kind = reason.getString("kind")
                val status = when (kind) { "completed" -> "COMPLETE"; "aborted" -> "CANCELLED"; "max-tokens" -> "LIMIT_REACHED"; "blocked" -> if (row.status == "LIMIT_REACHED") row.status else "INTERRUPTED"; else -> "ERROR" }
                val source = when { kind == "completed" -> ""; row.errorSource.isNotEmpty() -> row.errorSource; kind == "aborted" -> "USER"; kind == "max-tokens" -> "LIMIT"; row.phase == "MODEL" -> "MODEL"; row.phase.startsWith("TOOL:") -> "TOOL"; else -> "ENGINE" }
                val detail = if (row.errorDetail.isNotEmpty()) row.errorDetail else when (kind) { "completed" -> ""; "aborted" -> "任务已停止；工具状态以实际结果为准"; "max-tokens" -> "模型输出达到上限，可继续已完成的工作"; "error" -> reason.getJSONObject("error").getString("message"); else -> "引擎本轮已中断，可查询已有状态后继续" }
                dao.saveTurn(row.copy(status = status, errorSource = source, errorType = reason.optJSONObject("error")?.optString("code") ?: row.errorType, errorDetail = detail, finishedAt = time))
                dao.unknownTools(conversationId, time)
            }
        }
        if (event.getString("type") == "compaction/summary" && data.has("usage")) {
            val existing = dao.usageEntry(session.sessionId, seq)
            dao.saveUsage(ChatUsageEntity(session.sessionId, seq, conversationId, existing?.originKey ?: "${session.sessionId}:$seq", "${session.sessionId}:compaction:$seq", 0, data.getJSONObject("usage").toString()))
        }
        if (event.getString("type").startsWith("assistant/")) {
            val existing = dao.usageEntry(session.sessionId, seq)
            dao.saveUsage(ChatUsageEntity(session.sessionId, seq, conversationId, existing?.originKey ?: "${session.sessionId}:$seq", turnId(), data.getInt("step"), responseUsage(data).toString()))
        }
        dao.saveHarnessSession(session.copy(lastSeq = seq))
        dao.touchConversation(conversationId, time)
    }
}

/** Usage may survive a failed attempt only inside its recorded stream. */
internal fun responseUsage(data: JSONObject): JSONObject {
    data.optJSONObject("usage")?.let { return it }
    val stream = data.optJSONArray("stream") ?: return JSONObject()
    for (i in stream.length() - 1 downTo 0) {
        val record = stream.getJSONObject(i)
        record.optJSONObject("usage")?.let { return it }
        record.optJSONObject("chunk")?.optJSONObject("usage")?.let { return it }
    }
    return JSONObject()
}
