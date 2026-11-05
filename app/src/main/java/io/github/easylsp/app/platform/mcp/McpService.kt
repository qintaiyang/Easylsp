package io.github.easylsp.app.platform.mcp

import io.github.easylsp.app.EasylspApp
import io.github.easylsp.app.data.McpServerEntity
import org.json.*
import java.util.UUID

class McpService(private val graph: EasylspApp) {
    private val dao get() = graph.store.workflows
    private val settings = graph.getSharedPreferences("mcp-overrides", 0)
    private val readCatalogs = java.util.concurrent.ConcurrentHashMap<String, List<JSONObject>>()
    fun enabled(server: McpServerEntity, projectId: String?) = if (projectId != null && settings.contains("$projectId:${server.id}")) settings.getBoolean("$projectId:${server.id}", server.enabled) else server.enabled
    fun available(projectId: String?) = dao.servers().filter { enabled(it, projectId) }
    fun configure(id: String?, patch: JSONObject): McpServerEntity {
        val old = id?.let { requireNotNull(dao.server(it)) } ?: McpServerEntity(UUID.randomUUID().toString(), "MCP", "", false, "http")
        val config = JSONObject(old.config)
        listOf("command", "args", "cwd", "envRefs", "credentialRef", "oauth", "disabledTools", "timeoutSeconds").forEach { if (patch.has(it)) config.put(it, patch.get(it)) }
        val row = old.copy(name = patch.optString("name", old.name), url = patch.optString("url", old.url), transport = patch.optString("transport", old.transport),
            enabled = patch.optBoolean("enabled", old.enabled), config = config.toString(), revision = old.revision + 1)
        require(row.transport in setOf("http", "sse", "stdio")); require(config.optInt("timeoutSeconds", 60) > 0)
        if (row.transport == "stdio") require(config.getString("command").isNotBlank()) else require(java.net.URI(row.url).scheme in setOf("http", "https"))
        dao.saveServer(row); readCatalogs.keys.removeAll { it.endsWith(":${row.id}") }; return row
    }
    fun setEnabled(id: String, enabled: Boolean, projectId: String? = null): JSONObject {
        val row = requireNotNull(dao.server(id))
        if (projectId == null) configure(id, JSONObject().put("enabled", enabled)) else settings.edit().putBoolean("$projectId:$id", enabled).commit()
        return JSONObject().put("serverId", row.id).put("enabled", enabled).put("scope", if (projectId == null) "global" else "project").put("effectiveFrom", "next_turn")
    }
    private fun config(server: McpServerEntity): JSONObject {
        val config = JSONObject(server.config)
        val refs = config.optJSONObject("envRefs") ?: JSONObject(); val env = JSONObject()
        refs.keys().forEach { env.put(it, graph.secrets.get(refs.getString(it))) }
        val credentials = graph.secrets.get("mcp-oauth:${server.id}")
        return config.put("id", server.id).put("revision", server.revision).put("url", server.url).put("transport", server.transport).put("timeoutMs", config.optInt("timeoutSeconds", 60) * 1000L)
            .put("env", env).put("token", graph.secrets.get(config.optString("credentialRef", "mcp:${server.id}")))
            .put("credentials", if (credentials.isBlank()) JSONObject() else JSONObject(credentials))
    }
    suspend fun request(id: String, method: String, args: JSONObject = JSONObject()): JSONObject {
        val row = requireNotNull(dao.server(id)) { "MCP 服务不存在" }
        return graph.chat.executionService().request("mcp/$method", args.put("serverId", id).put("config", config(row)))
    }
    suspend fun tools(id: String, projectId: String?, conversationId: String): List<JSONObject> {
        val server = requireNotNull(dao.server(id)); require(enabled(server, projectId)) { "MCP 服务已停用" }
        val result = request(id, "tools").getJSONArray("tools")
        val disabled = JSONObject(server.config).optJSONArray("disabledTools") ?: JSONArray()
        val names = (0 until disabled.length()).map { disabled.getString(it) }.toSet()
        return (0 until result.length()).map { result.getJSONObject(it) }.filter { it.getString("name") !in names }.also { readCatalogs["$conversationId:$id"] = it }
    }
    suspend fun call(id: String, projectId: String?, conversationId: String, name: String, arguments: JSONObject): JSONObject {
        val server = requireNotNull(dao.server(id)); require(enabled(server, projectId)) { "MCP 服务已停用" }
        val tool = readCatalogs["$conversationId:$id"]?.firstOrNull { it.getString("name") == name } ?: error("请先读取此扩展的准确工具目录")
        val disabled = JSONObject(server.config).optJSONArray("disabledTools") ?: JSONArray()
        require((0 until disabled.length()).none { disabled.getString(it) == name }) { "此工具已停用" }
        io.github.easylsp.app.platform.tools.ToolArguments.validate(tool.getJSONObject("inputSchema"), arguments)
        return request(id, "call", JSONObject().put("name", name).put("arguments", arguments))
    }
    private fun definitions(binding: io.github.easylsp.app.platform.tools.RunBinding, serverId: String, tools: List<JSONObject>): List<io.github.easylsp.app.platform.tools.ToolDefinition> = tools.map { tool ->
        val key = "tool:$serverId:${tool.getString("name")}"
        val name = settings.getString(key, null) ?: ("ext_" + UUID.randomUUID().toString().replace("-", "")).also { settings.edit().putString(key, it).commit() }
        io.github.easylsp.app.platform.tools.ToolDefinition(name, tool.optString("description", tool.getString("name")), tool.getJSONObject("inputSchema"), source = "MCP · ${requireNotNull(dao.server(serverId)).name}", writes = true,
            invoke = { io.github.easylsp.app.platform.tools.ToolPayload(call(serverId, binding.projectBinding?.id, binding.conversationId, tool.getString("name"), it)) }, groupId = "extensions")
    }
    suspend fun select(binding: io.github.easylsp.app.platform.tools.RunBinding, args: JSONObject): JSONObject {
        val serverId = args.getString("extensionId")
        val catalog = tools(serverId, binding.projectBinding?.id, binding.conversationId)
        val names = args.getJSONArray("names"); val requested = (0 until names.length()).map { names.getString(it) }.toSet()
        require(requested.all { name -> catalog.any { it.getString("name") == name } }) { "选择包含未知远程工具" }
        val chosen = catalog.filter { it.getString("name") in requested }
        val prefsKey = "selected:${binding.conversationId}:$serverId"
        val existing = JSONArray(settings.getString(prefsKey, "[]")!!)
        val combined = ((0 until existing.length()).map { existing.getJSONObject(it) } + chosen).associateBy { it.getString("name") }.values.toList()
        settings.edit().putString(prefsKey, JSONArray(combined).toString()).commit()
        val definitions = definitions(binding, serverId, chosen)
        val caps = graph.chat.selection(binding.conversationId); caps.register(definitions)
        return caps.select(JSONObject().put("names", JSONArray(definitions.map { it.name }))).put("schemas", JSONArray(chosen))
    }
    suspend fun refresh(binding: io.github.easylsp.app.platform.tools.RunBinding, id: String): JSONObject {
        val catalog = tools(id, binding.projectBinding?.id, binding.conversationId)
        val key = "selected:${binding.conversationId}:$id"
        val previous = JSONArray(settings.getString(key, "[]")!!)
        val old = (0 until previous.length()).map { previous.getJSONObject(it) }
        val chosen = catalog.filter { tool -> old.any { it.getString("name") == tool.getString("name") } }
        settings.edit().putString(key, JSONArray(chosen).toString()).commit()
        val caps = graph.chat.selection(binding.conversationId)
        caps.unregister(definitions(binding, id, old).map { it.name }.toSet())
        caps.register(definitions(binding, id, chosen))
        return JSONObject().put("tools", JSONArray(catalog)).put("effectiveFrom", "next_step")
    }
    fun restoredTools(binding: io.github.easylsp.app.platform.tools.RunBinding): List<io.github.easylsp.app.platform.tools.ToolDefinition> = available(binding.projectBinding?.id).flatMap { server ->
        val array = JSONArray(settings.getString("selected:${binding.conversationId}:${server.id}", "[]")!!)
        val tools = (0 until array.length()).map { array.getJSONObject(it) }
        if (tools.isNotEmpty()) readCatalogs["${binding.conversationId}:${server.id}"] = tools
        definitions(binding, server.id, tools)
    }
    fun credentials(id: String, value: JSONObject) { graph.secrets.put("mcp-oauth:$id", value.toString()) }
    fun invalidate(id: String) { readCatalogs.keys.removeAll { it.endsWith(":$id") } }
    fun json(server: McpServerEntity) = JSONObject().put("serverId", server.id).put("name", server.name).put("url", server.url).put("transport", server.transport)
        .put("enabled", server.enabled).put("revision", server.revision).put("config", JSONObject(server.config))
}
