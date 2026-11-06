package io.github.easylsp.app.platform.ai

import io.github.easylsp.app.EasylspApp
import io.github.easylsp.app.data.*
import org.json.*
import kotlinx.coroutines.CancellationException
import java.util.UUID

class MemoryService(private val graph: EasylspApp) {
    private val dao get() = graph.store.workflows
    val preferences = graph.getSharedPreferences("memory-settings", 0)
    fun enabled() = preferences.getBoolean("enabled", true)
    fun rules(projectId: String?) = dao.memories(projectId).filter { it.rule }.joinToString("\n") { it.text }
    fun read(id: String, projectId: String?): MemoryEntity = requireNotNull(dao.memory(id)) { "记忆不存在" }.also {
        require(it.projectId == null || it.projectId == projectId) { "记忆不属于当前作用域" }
    }
    fun search(projectId: String?, args: JSONObject): JSONObject {
        require(enabled()) { "用户已停用记忆检索" }
        val scope = args.optString("scope", if (projectId == null) "global" else "both")
        require(scope in setOf("global", "project", "both") && (scope != "project" || projectId != null))
        val cursor = args.optInt("cursor", 0); val limit = args.optInt("limit", 10); require(cursor >= 0 && limit > 0)
        val query = args.getString("query")
        val rows = dao.searchMemories(projectId, "%${query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")}%").filter {
            !it.rule && (scope == "both" || (scope == "global") == (it.projectId == null))
        }
        val page = rows.drop(cursor).take(limit)
        return JSONObject().put("memories", JSONArray(page.map { json(it).put("text", it.text.take(1000)) })).put("nextCursor", if (cursor + page.size < rows.size) cursor + page.size else JSONObject.NULL)
    }
    @Synchronized fun save(projectId: String?, args: JSONObject, conversation: ConversationEntity): MemoryEntity {
        val scope = args.getString("scope"); require(scope in setOf("global", "project"))
        val bound = if (scope == "project") requireNotNull(projectId) else null
        val sources = args.optJSONArray("sourceMessageIds") ?: JSONArray()
        val refs = JSONArray((0 until sources.length()).map { index ->
            val message = requireNotNull(dao.message(sources.getLong(index))) { "来源消息不存在" }
            require(message.conversationId == conversation.id || (conversation.task == "__memory_consolidation__" && message.conversationId == dao.harnessSession(conversation.id)?.sourceConversationId)) { "来源消息必须属于当前会话或整理来源" }
            JSONObject().put("conversationId", message.conversationId).put("messageId", message.id)
        })
        return MemoryEntity(UUID.randomUUID().toString(), bound, args.getString("title"), args.getString("text"), refs.toString(), conversation.sampleRevision.takeIf { bound != null }).also(dao::saveMemory)
    }
    @Synchronized fun update(projectId: String?, args: JSONObject): MemoryEntity {
        val old = read(args.getString("memoryId"), projectId); require(!old.rule) { "常驻规则请在规则界面编辑" }
        return old.copy(title = args.optString("title", old.title), text = args.getString("text"), revision = old.revision + 1, updatedAt = System.currentTimeMillis()).also(dao::saveMemory)
    }
    @Synchronized fun delete(projectId: String?, id: String) { require(!read(id, projectId).rule) { "常驻规则请在规则界面删除" }; dao.deleteMemory(id) }
    @Synchronized fun edit(row: MemoryEntity) { require(row.title.isNotBlank() && row.text.isNotBlank()); dao.saveMemory(row.copy(revision = row.revision + 1, updatedAt = System.currentTimeMillis())) }
    @Synchronized fun removeFromSettings(id: String) { dao.deleteMemory(id) }
    fun export(projectId: String?) = dao.memories(projectId).joinToString("\n\n") { "## ${it.title}\n\n${it.text}\n\n<!-- scope=${if (it.projectId == null) "global" else "project"}; rule=${it.rule}; sources=${it.sources} -->" }
    @Synchronized fun import(projectId: String?, text: String) {
        require(text.isNotBlank())
        dao.saveMemory(MemoryEntity(UUID.randomUUID().toString(), projectId, "导入记忆", text))
    }
    suspend fun consolidate(conversation: ConversationEntity) {
        if (!preferences.getBoolean("automatic", false) || conversation.task == "__memory_consolidation__") return
        val turn = dao.turnHistory(conversation.id).lastOrNull() ?: return
        if (preferences.getString("organized:${conversation.id}", null) == turn.id) return
        val child = graph.chat.fork(conversation, task = "__memory_consolidation__")
        dao.saveConversation(child.copy(archived = true, mode = "normal", authorizationMode = "ALLOW"))
        val sources = dao.history(conversation.id).filter { it.role == "user" || it.role == "assistant" }.map { JSONObject().put("messageId", it.id).put("role", it.role).put("engineMessageId", it.engineMessageId) }
        val instruction = "独立整理刚完成任务中的长期记忆。仅保存适合跨会话复用的已确认结论，保留来源；不要保存完整日志、临时进度或重复条目。项目专有事实只保存到项目；全局记忆只保存用户偏好等跨项目事实。不得创建常驻规则。不需要保存时直接结束。来源会话 ${conversation.id}，来源消息如下：\n${JSONArray(sources)}"
        val submission = graph.chat.submit(child.id, instruction, null, conversation.authorizationMode == "ALLOW", JSONArray(), intent = "NEW_TASK")
        try { submission.await() }
        catch (error: CancellationException) { submission.cancel(); throw error }
        preferences.edit().putString("organized:${conversation.id}", turn.id).putString("lastTask", child.id).commit()
    }
    fun json(row: MemoryEntity) = JSONObject().put("memoryId", row.id).put("scope", if (row.projectId == null) "global" else "project").put("projectId", row.projectId ?: JSONObject.NULL)
        .put("title", row.title).put("text", row.text).put("revision", row.revision).put("sources", JSONArray(row.sources)).put("sampleRevision", row.sampleRevision ?: JSONObject.NULL).put("updatedAt", row.updatedAt)
}
