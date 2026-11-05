package io.github.easylsp.app.platform.tools

import io.github.easylsp.app.EasylspApp
import org.json.*
import java.io.File

/** Public tool boundary delegates persistence and scope checks to the same services as settings. */
class PlatformTools(private val graph: EasylspApp, private val binding: RunBinding) {
    private val dao get() = graph.store.workflows
    private val projectId get() = binding.projectBinding?.id
    private fun conversation() = requireNotNull(dao.conversation(binding.conversationId))
    private fun saved(value: JSONObject) = value.put("effectiveFrom", "next_turn")
    fun definitions(): List<ToolDefinition> = buildList {
        fun tool(name: String, description: String, group: String, fields: Map<String, String> = emptyMap(), required: List<String> = emptyList(), writes: Boolean = false, invoke: suspend (JSONObject) -> JSONObject) {
            add(ToolDefinition(name, description, BuiltinToolSchemas.create(name, fields, required), writes = writes, invoke = invoke, groupId = group))
        }
        tool("list_providers", "List saved provider connections; credentials are references only.", "config.models") {
            JSONObject().put("providers", JSONArray(dao.providers().map { JSONObject().put("providerId", it.id).put("name", it.name).put("protocol", it.protocol).put("baseUrl", it.baseUrl).put("enabled", it.enabled).put("credentialRef", graph.models.credential(it)).put("revision", it.revision) }))
        }
        tool("list_models", "List independent model configurations, including disabled choices and their parameters.", "config.models", mapOf("providerId" to "string")) { args ->
            JSONObject().put("models", JSONArray(dao.models().filter { !args.has("providerId") || it.providerId == args.getString("providerId") }.map { JSONObject().put("modelConfigId", it.id).put("providerId", it.providerId).put("remoteModelId", it.remoteModelId).put("displayName", it.displayName).put("enabled", it.enabled).put("options", JSONObject(it.options)).put("maxSteps", it.maxSteps).put("revision", it.revision) }))
        }
        tool("configure_provider", "Create/update a provider connection. patch: name, protocol(openai/anthropic/gemini), baseUrl, requestPath, modelsPath, credentialRef, enabled, timeoutSeconds. Secrets are set through settings, never returned.", "config.models", mapOf("providerId" to "string", "patch" to "object"), listOf("patch"), true) { args ->
            val row = graph.models.configureProvider(args.optString("providerId").ifEmpty { null }, args.getJSONObject("patch")); saved(JSONObject().put("providerId", row.id).put("revision", row.revision))
        }
        tool("configure_model", "Create/update a model configuration. patch: displayName, enabled, contextWindow, contextBudget, maxOutputTokens, summaryMaxTokens, supportsImages, reasoningEffort, thinkingBudget, maxSteps(0 unlimited), maxParallelToolCalls. Does not alter the active turn.", "config.models", mapOf("modelConfigId" to "string", "providerId" to "string", "remoteModelId" to "string", "patch" to "object"), listOf("providerId", "remoteModelId", "patch"), true) { args ->
            val row = graph.models.configureModel(args.optString("modelConfigId").ifEmpty { null }, args.getString("providerId"), args.getString("remoteModelId"), args.getJSONObject("patch")); saved(JSONObject().put("modelConfigId", row.id).put("revision", row.revision))
        }
        tool("select_model", "Select a model for this conversation, project default or global default; applies next turn.", "config.models", mapOf("modelConfigId" to "string", "scope" to "string"), listOf("modelConfigId"), true) { graph.models.select(conversation(), it.getString("modelConfigId"), it.optString("scope", "conversation")) }
        tool("create_skill", "Create a standard Skill package. files maps relative paths to text, including SKILL.md with YAML name/description. scope=global/project.", "config.skills", mapOf("scope" to "string", "files" to "object"), listOf("scope", "files"), true) {
            val scope = it.getString("scope"); require(scope in setOf("global", "project"))
            val skill = graph.skillPackages.create(if (scope == "project") requireNotNull(projectId) else null, it.getJSONObject("files")); saved(JSONObject().put("skillId", skill.id).put("revision", skill.revision))
        }
        tool("update_skill", "Update text resources and metadata together; files maps relative paths to text, removePaths deletes listed resources. SKILL.md is required.", "config.skills", mapOf("skillId" to "string", "files" to "object", "removePaths" to "array"), listOf("skillId", "files"), true) {
            val row = graph.skillPackages.update(projectId, it.getString("skillId"), it.getJSONObject("files"), it.optJSONArray("removePaths") ?: JSONArray()); saved(JSONObject().put("skillId", row.id).put("revision", row.revision))
        }
        tool("install_skill", "Install a Skill from a GitHub URL or a workspace ZIP path. Imported packages start disabled; inspect and enable when appropriate.", "config.skills", mapOf("url" to "string", "path" to "string", "scope" to "string"), writes = true) { args ->
            require(args.has("url") != args.has("path"))
            val row = if (args.has("url")) graph.skillPackages.github(args.getString("url")) else File(graph.executions.directory(binding.workspace.id), args.getString("path")).inputStream().use { graph.skillPackages.import(it) }
            val scope = args.optString("scope", "global"); require(scope in setOf("global", "project"))
            val installed = row.copy(projectId = if (scope == "project") requireNotNull(projectId) else null); dao.saveSkill(installed)
            saved(JSONObject().put("skillId", installed.id).put("enabled", installed.enabled))
        }
        tool("set_skill_enabled", "Enable or disable an available Skill package. Disabled Skills stop contributing tools at the next step.", "config.skills", mapOf("skillId" to "string", "enabled" to "boolean"), listOf("skillId", "enabled"), true) {
            val row = graph.skillPackages.requireSkill(it.getString("skillId"), projectId, false)
            dao.saveSkill(row.copy(enabled = it.getBoolean("enabled"), revision = row.revision + 1))
            JSONObject().put("skillId", row.id).put("enabled", it.getBoolean("enabled")).put("effectiveFrom", "next_step")
        }
        tool("search_memory", "Search global/current-project reusable facts. Returns sources; load full entries and history when needed. No keyword gate.", "memory", mapOf("query" to "string", "scope" to "string", "cursor" to "integer", "limit" to "integer"), listOf("query")) { graph.memories.search(projectId, it) }
        tool("read_memory", "Read a complete memory and its source references; project facts retain sample revision.", "memory", mapOf("memoryId" to "string"), listOf("memoryId")) { require(graph.memories.enabled()) { "记忆已停用" }; graph.memories.json(graph.memories.read(it.getString("memoryId"), projectId)) }
        tool("save_memory", "Save a reusable fact, not a permanent instruction. scope=global/project; sourceMessageIds belong to this conversation.", "memory", mapOf("scope" to "string", "title" to "string", "text" to "string", "sourceMessageIds" to "array"), listOf("scope", "title", "text"), true) { graph.memories.json(graph.memories.save(projectId, it, conversation())) }
        tool("update_memory", "Update an existing memory within the accessible scope.", "memory", mapOf("memoryId" to "string", "title" to "string", "text" to "string"), listOf("memoryId", "text"), true) { graph.memories.json(graph.memories.update(projectId, it)) }
        tool("delete_memory", "Delete a memory from future retrieval. Previously read chat history is retained.", "memory", mapOf("memoryId" to "string"), listOf("memoryId"), true) { graph.memories.delete(projectId, it.getString("memoryId")); JSONObject().put("deleted", true) }
        tool("list_conversations", "List accessible conversations. scope=global/current_project/authorized, cursor/limit paginate. Reading does not start models.", "sessions", mapOf("scope" to "string", "cursor" to "integer", "limit" to "integer")) { graph.conversations.list(conversation(), it) }
        tool("search_conversations", "Search accessible message text with conversation/message source references.", "sessions", mapOf("query" to "string", "scope" to "string", "cursor" to "integer", "limit" to "integer"), listOf("query")) { graph.conversations.search(conversation(), it) }
        tool("read_conversation", "Read original paged messages and structured blocks for an accessible conversation.", "sessions", mapOf("conversationId" to "string", "cursor" to "integer", "limit" to "integer"), listOf("conversationId")) { graph.conversations.read(conversation(), it) }
        tool("create_conversation", "Create an independent conversation in this scope, or scope=global. Does not submit a model request or switch the user's page.", "sessions", mapOf("title" to "string", "scope" to "string", "modelConfigId" to "string"), writes = true) {
            graph.conversations.json(graph.conversations.create(if (it.optString("scope") == "global") null else projectId, it.optString("modelConfigId").ifEmpty { graph.models.selected(conversation()) }, it.optString("title", "新对话")))
        }
        tool("send_to_conversation", "Submit an instruction to an accessible conversation. Returns current admission state; supplement=true steers a running turn at the next step.", "sessions", mapOf("conversationId" to "string", "text" to "string", "supplement" to "boolean"), listOf("conversationId", "text"), true) {
            val row = graph.conversations.requireConversation(conversation(), it.getString("conversationId"))
            require(row.id != binding.conversationId) { "当前会话可直接继续任务" }
            graph.chat.submit(row.id, it.getString("text"), null, row.authorizationMode == "ALLOW", JSONArray(), intent = if (it.optBoolean("supplement")) "SUPPLEMENT" else "NEW_TASK").await()
            graph.agents.describe(row)
        }
        tool("archive_conversation", "Archive an accessible conversation without deleting history.", "sessions", mapOf("conversationId" to "string"), listOf("conversationId"), true) {
            val row = graph.conversations.requireConversation(conversation(), it.getString("conversationId")); dao.saveConversation(row.copy(archived = true)); JSONObject().put("archived", true)
        }
        tool("fork_conversation", "Create an independent branch from the latest completed turn. The source remains running; inherited tools are never replayed.", "sessions", mapOf("conversationId" to "string", "modelConfigId" to "string", "task" to "string"), writes = true) {
            val source = graph.conversations.requireConversation(conversation(), it.optString("conversationId", binding.conversationId))
            graph.conversations.json(graph.chat.fork(source, it.optString("modelConfigId").ifEmpty { null }, task = it.optString("task")))
        }
        tool("spawn_agent", "Delegate a task to a persistent child conversation. Inherits scope, authorization and model; can delegate further. inheritHistory copies completed turns only. tools optionally selects initial capabilities.", "agents", mapOf("task" to "string", "modelConfigId" to "string", "inheritHistory" to "boolean", "tools" to "array"), listOf("task"), true) { graph.agents.spawn(conversation(), it) }
        tool("list_agents", "Read child task states and result references.", "agents") { graph.agents.list(binding.conversationId) }
        tool("send_agent_message", "Steer an active child or continue an idle child with a new task.", "agents", mapOf("agentId" to "string", "text" to "string"), listOf("agentId", "text"), true) { graph.agents.send(binding.conversationId, it.getString("agentId"), it.getString("text")) }
        tool("wait_agent", "Wait for a child while yielding the parent's execution slot. timeoutSeconds=1..60; repeated waits do not restart the child.", "agents", mapOf("agentId" to "string", "timeoutSeconds" to "integer"), listOf("agentId")) { graph.agents.wait(binding.conversationId, it.getString("agentId"), it.optLong("timeoutSeconds", 30)) }
        tool("stop_agent", "Stop a child execution tree. Independent forks remain active.", "agents", mapOf("agentId" to "string"), listOf("agentId"), true) { graph.agents.stop(binding.conversationId, it.getString("agentId")) }
        tool("configure_mcp_server", "Create/update HTTP/SSE/stdio MCP configuration. patch: name,url,transport,enabled,timeoutSeconds,command,args,cwd,envRefs,credentialRef,oauth,disabledTools. envRefs are Keystore references.", "config.mcp", mapOf("serverId" to "string", "patch" to "object"), listOf("patch"), true) { saved(graph.mcp.json(graph.mcp.configure(it.optString("serverId").ifEmpty { null }, it.getJSONObject("patch")))) }
        tool("set_mcp_enabled", "Set global enablement or current-project override.", "config.mcp", mapOf("serverId" to "string", "enabled" to "boolean", "scope" to "string"), listOf("serverId", "enabled"), true) { graph.mcp.setEnabled(it.getString("serverId"), it.getBoolean("enabled"), if (it.optString("scope") == "project") requireNotNull(projectId) else null) }
        tool("test_mcp_server", "Connect using the shared MCP client and report actual connection/authentication status.", "config.mcp", mapOf("serverId" to "string"), listOf("serverId")) { graph.mcp.request(it.getString("serverId"), "status") }
        tool("refresh_mcp_tools", "Refresh the actual remote directory, including changed schemas; use call_extension_tool with the returned schema.", "config.mcp", mapOf("serverId" to "string"), listOf("serverId")) { graph.mcp.refresh(binding, it.getString("serverId")) }
        tool("select_extension_tools", "Expose selected remote tools directly at the next step using their actual schema. First read the extension directory; names are remote tool names.", "extensions", mapOf("extensionId" to "string", "names" to "array"), listOf("extensionId", "names")) { graph.mcp.select(binding, it) }
        tool("list_extension_resources", "List resources or URI templates advertised by a selected MCP service.", "extensions", mapOf("extensionId" to "string", "templates" to "boolean", "cursor" to "string"), listOf("extensionId")) {
            require(graph.mcp.available(projectId).any { row -> row.id == it.getString("extensionId") })
            graph.mcp.request(it.getString("extensionId"), if (it.optBoolean("templates")) "templates" else "resources", JSONObject().put("arguments", JSONObject().apply { if (it.has("cursor")) put("cursor", it.getString("cursor")) }))
        }
        tool("read_extension_resource", "Read a discovered MCP resource URI on demand.", "extensions", mapOf("extensionId" to "string", "uri" to "string"), listOf("extensionId", "uri")) {
            require(graph.mcp.available(projectId).any { row -> row.id == it.getString("extensionId") })
            graph.mcp.request(it.getString("extensionId"), "read", JSONObject().put("uri", it.getString("uri")))
        }
        val boundConversation = dao.conversation(binding.conversationId)
        val imageSupport = boundConversation?.let { JSONObject(graph.models.resolve(graph.models.selected(it), requireEnabled = false).options).optBoolean("supportsImages") } ?: false
        val phoneSchema = BuiltinToolSchemas.phone(JSONObject(graph.assets.open("phone-use.input.schema.json").bufferedReader().use { it.readText() }))
        add(ToolDefinition("phone_use", "Observe and operate the phone through Accessibility. action: status/list_apps/observe/release/launch/open_uri/tap/long_press/swipe/gesture/set_text/key/wait. Observe mode=nodes/screen/both. Use actual snapshotId for input. target={nodeId} or {x,y}; coordinates use observation dimensions. set_text supports Unicode; actions require subsequent observation to confirm outcome. Another owner's device returns DEVICE_BUSY immediately.", phoneSchema, writes = true,
            writesFor = { it.optString("action") !in setOf("status", "list_apps", "observe", "wait", "release") },
            invoke = { args ->
                try { graph.phone.use(binding.conversationId, imageSupport, args) }
                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (e: Exception) {
                    val message = e.message ?: e.javaClass.simpleName
                    val code = message.substringBefore(":").takeIf { it.matches(Regex("[A-Z_]+")) } ?: "ACTION_FAILED"
                    val failure = JSONObject().put("error", JSONObject().put("code", code).put("message", message))
                    ToolPayload(JSONObject().put("isError", true).put("structuredContent", failure).put("content", JSONArray().put(JSONObject().put("type", "text").put("text", failure.toString()))))
                }
            }, groupId = "phone"))
    }
}
