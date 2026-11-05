package io.github.easylsp.app.platform.ai

import io.github.easylsp.app.EasylspApp
import io.github.easylsp.app.data.*
import org.json.*
import java.util.UUID

class ConversationService(private val graph: EasylspApp) {
    private val dao get() = graph.store.workflows
    fun create(projectId: String? = null, modelId: String? = null, title: String = "新对话", parent: ConversationEntity? = null, task: String = ""): ConversationEntity {
        val model = modelId ?: requireNotNull(graph.models.defaultModel(projectId)) { "请先配置并启用一个模型" }
        val provider = graph.models.resolve(model)
        val project = projectId?.let { requireNotNull(graph.store.dao.project(it)) { "项目不存在" } }
        val row = ConversationEntity(UUID.randomUUID().toString(), projectId, provider.id, provider.model, title, project?.sampleRevision ?: 0, System.currentTimeMillis(),
            scope = if (projectId == null) "GLOBAL" else "PROJECT", modelConfigId = model, parentConversationId = parent?.id, task = task,
            mode = parent?.mode ?: "normal", promptText = parent?.promptText.orEmpty(), authorizationMode = parent?.authorizationMode.orEmpty(), historyAccess = parent?.historyAccess ?: "scope")
        dao.insertConversation(row); return row
    }
    fun accessible(caller: ConversationEntity, target: ConversationEntity) = caller.id == target.id || caller.historyAccess == "authorized" || target.projectId == null || caller.projectId == target.projectId
    fun requireConversation(caller: ConversationEntity, id: String) = requireNotNull(dao.conversation(id)) { "会话不存在" }.also { require(accessible(caller, it)) { "此会话不在已授权历史范围内" } }
    fun list(caller: ConversationEntity, args: JSONObject): JSONObject {
        val scope = args.optString("scope", if (caller.projectId == null) "global" else "current_project")
        require(scope in setOf("global", "current_project", "authorized"))
        require(scope != "authorized" || caller.historyAccess == "authorized") { "尚未授权其他项目历史访问" }
        val rows = dao.allConversations().filter { !it.archived && accessible(caller, it) && when (scope) {
            "global" -> it.projectId == null; "current_project" -> it.projectId == caller.projectId; else -> true
        } }.sortedByDescending { it.updatedAt }
        val cursor = args.optInt("cursor", 0); val limit = args.optInt("limit", 20); require(cursor >= 0 && limit > 0)
        val page = rows.drop(cursor).take(limit)
        return JSONObject().put("conversations", JSONArray(page.map(::json))).put("nextCursor", if (cursor + page.size < rows.size) cursor + page.size else JSONObject.NULL)
    }
    fun read(caller: ConversationEntity, args: JSONObject): JSONObject {
        val row = requireConversation(caller, args.getString("conversationId"))
        val cursor = args.optLong("cursor", 0); val limit = args.optInt("limit", 50); require(cursor >= 0 && limit > 0)
        val messages = dao.historyPage(row.id, cursor, limit + 1)
        return json(row).put("messages", JSONArray(messages.take(limit).map { message -> JSONObject().put("messageId", message.id).put("role", message.role).put("text", message.content)
            .put("eventSeq", message.eventSeq).put("turnId", message.turnId).put("blocks", JSONObject(message.blocks)) }))
            .put("nextCursor", if (messages.size > limit) messages[limit - 1].id else JSONObject.NULL)
    }
    fun search(caller: ConversationEntity, args: JSONObject): JSONObject {
        val cursor = args.optLong("cursor", 0); val limit = args.optInt("limit", 20); require(cursor >= 0 && limit > 0)
        val scope = args.optString("scope", if (caller.projectId == null) "global" else "current_project")
        require(scope in setOf("global", "current_project", "authorized")); require(scope != "authorized" || caller.historyAccess == "authorized")
        val rows = dao.allConversations().filter { accessible(caller, it) && when (scope) { "global" -> it.projectId == null; "current_project" -> it.projectId == caller.projectId; else -> true } }
            .flatMap { dao.searchMessages(it.id, "%${args.getString("query")}%", cursor, limit + 1) }.sortedBy { it.id }.take(limit + 1)
        return JSONObject().put("matches", JSONArray(rows.take(limit).map { JSONObject().put("conversationId", it.conversationId).put("messageId", it.id).put("text", it.content) }))
            .put("nextCursor", if (rows.size > limit) rows[limit - 1].id else JSONObject.NULL)
    }
    fun json(row: ConversationEntity) = JSONObject().put("conversationId", row.id).put("scope", row.scope).put("projectId", row.projectId ?: JSONObject.NULL)
        .put("title", row.title).put("modelConfigId", row.modelConfigId).put("parentConversationId", row.parentConversationId ?: JSONObject.NULL).put("updatedAt", row.updatedAt).put("archived", row.archived)
}
