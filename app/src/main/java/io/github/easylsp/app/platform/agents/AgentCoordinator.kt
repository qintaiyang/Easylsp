package io.github.easylsp.app.platform.agents

import io.github.easylsp.app.EasylspApp
import io.github.easylsp.app.data.ConversationEntity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.*

/** Delegation uses ordinary persistent conversations and the host's shared scheduler. */
class AgentCoordinator(private val graph: EasylspApp) {
    private val dao get() = graph.store.workflows
    private val delivered = graph.getSharedPreferences("agent-notifications", 0)
    suspend fun spawn(parent: ConversationEntity, args: JSONObject): JSONObject {
        val task = args.getString("task"); require(task.isNotBlank())
        val modelId = args.optString("modelConfigId").ifEmpty { graph.models.selected(parent) }
        val child = if (args.optBoolean("inheritHistory")) graph.chat.fork(parent, modelId, parent = true, task = task)
            else graph.conversations.create(parent.projectId, modelId, task.lineSequence().first().take(40), parent, task)
        val selection = args.optJSONArray("tools")
        if (selection != null) {
            val prefs = graph.getSharedPreferences("conversation-capabilities", 0)
            prefs.edit().putString(child.id, JSONObject().put("selectedTools", selection).toString()).commit()
        }
        graph.chat.submit(child.id, task, null, parent.authorizationMode == "ALLOW", JSONArray()).await()
        return describe(child)
    }
    fun descendant(parent: String, child: String): Boolean {
        var current = dao.conversation(child)?.parentConversationId
        while (current != null) { if (current == parent) return true; current = dao.conversation(current)?.parentConversationId }
        return false
    }
    fun requireChild(parent: String, id: String) = requireNotNull(dao.conversation(id)).also { require(descendant(parent, id)) { "此代理不属于当前执行树" } }
    fun list(parent: String) = JSONObject().put("agents", JSONArray(dao.allConversations().filter { descendant(parent, it.id) }.map(::describe)))
    fun describe(child: ConversationEntity): JSONObject {
        val turn = dao.turnHistory(child.id).lastOrNull()
        val pending = dao.pendingInputs(child.id)
        val status = when {
            pending.any { it.status == "HOST_QUEUED" } -> "QUEUED"
            turn?.phase in setOf("USER_QUESTION", "USER_APPROVAL") && turn?.status == "RUNNING" -> "WAITING_USER"
            turn != null -> turn.status
            else -> "IDLE"
        }
        return graph.conversations.json(child).put("agentId", child.id).put("status", status).put("task", child.task)
            .put("lastMessage", dao.history(child.id).lastOrNull { it.role == "assistant" }?.let { JSONObject().put("messageId", it.id).put("text", it.content) } ?: JSONObject.NULL)
    }
    suspend fun send(parent: String, childId: String, text: String): JSONObject {
        val child = requireChild(parent, childId)
        graph.chat.submit(child.id, text, null, child.authorizationMode == "ALLOW", JSONArray(), intent = "SUPPLEMENT").await()
        return describe(child)
    }
    suspend fun wait(parent: String, childId: String, timeoutSeconds: Long): JSONObject {
        val child = requireChild(parent, childId); require(timeoutSeconds in 1..60)
        val host = graph.chat.executionService(); val mapping = requireNotNull(dao.harnessSession(parent))
        val params = JSONObject().put("sessionId", mapping.sessionId)
        host.request("session/yield", params)
        try {
            withTimeoutOrNull(timeoutSeconds * 1000) {
                dao.turns(child.id).first { rows -> rows.lastOrNull()?.finishedAt != null && dao.pendingInputs(child.id).isEmpty() }
            }
            return describe(requireNotNull(dao.conversation(child.id)))
        } finally { if (currentCoroutineContext().isActive) host.request("session/acquire", params) }
    }
    suspend fun stop(parent: String, childId: String): JSONObject {
        requireChild(parent, childId)
        dao.harnessSession(childId)?.let { graph.chat.executionService().request("session/cancel", JSONObject().put("sessionId", it.sessionId)) }
        graph.phone.release(childId); return JSONObject().put("stopRequested", true)
    }
    fun cancelConversation(conversationId: String) { dao.allConversations().filter { descendant(conversationId, it.id) }.forEach { graph.phone.release(it.id) } }
    suspend fun completed(conversationId: String) {
        val child = dao.conversation(conversationId) ?: return
        val parentId = child.parentConversationId ?: return
        val turn = dao.turnHistory(conversationId).lastOrNull() ?: return
        if (turn.finishedAt == null || turn.status in setOf("STOPPED", "CANCELLED", "INTERRUPTED")) return
        val key = "agent-result-$conversationId-${turn.startSeq}"
        if (delivered.getBoolean(key, false)) return
        val parent = dao.conversation(parentId) ?: return
        val result = dao.history(conversationId).lastOrNull { it.role == "assistant" }
        val summary = "子代理 ${child.id} 已${if (turn.status == "COMPLETE") "完成" else "结束（${turn.status}）"}。任务：${child.task}\n${result?.content.orEmpty()}\n完整记录：conversationId=${child.id}, messageId=${result?.id ?: 0}"
        // The parent receives a durable input even when its page is not visible.
        graph.chat.submit(parent.id, summary, null, parent.authorizationMode == "ALLOW", JSONArray(), intent = "SUPPLEMENT", inputId = key).await()
        delivered.edit().putBoolean(key, true).commit()
    }
}
