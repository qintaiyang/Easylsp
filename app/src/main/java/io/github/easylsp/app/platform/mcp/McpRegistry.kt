package io.github.easylsp.app.platform.mcp

import io.github.easylsp.app.data.McpServerEntity
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/** One owner per configured extension. Connect only when its catalog or a tool is requested. */
class McpRegistry {
    private class Entry(val server: McpServerEntity, val token: String) {
        val client = McpClient(server, token)
        val serial = Mutex()
        var catalog: List<JSONObject>? = null
    }
    private val entries = mutableMapOf<String, Entry>()
    private val owners = Mutex()
    private suspend fun entry(server: McpServerEntity, token: String) = owners.withLock {
        val old = entries[server.id]
        if (old != null && old.server == server && old.token == token) old else {
            old?.client?.close()
            Entry(server, token).also { entries[server.id] = it }
        }
    }
    private suspend fun <T> use(server: McpServerEntity, token: String, action: suspend (Entry) -> T): T {
        val owner = entry(server, token)
        return owner.serial.withLock {
            try {
                if (owner.catalog == null) { owner.client.initialize(); owner.catalog = owner.client.listTools() }
                action(owner)
            } catch (error: java.io.IOException) {
                owners.withLock { if (entries[server.id] === owner) entries.remove(server.id) }
                owner.client.close()
                throw error
            }
        }
    }
    suspend fun tools(server: McpServerEntity, token: String): List<JSONObject> = use(server, token) { it.catalog!! }
    suspend fun call(server: McpServerEntity, token: String, name: String, arguments: JSONObject): JSONObject = use(server, token) {
        val tool = it.catalog!!.first { tool -> tool.getString("name") == name }
        io.github.easylsp.app.platform.tools.ToolArguments.validate(tool.getJSONObject("inputSchema"), arguments)
        it.client.call(name, arguments)
    }
}
