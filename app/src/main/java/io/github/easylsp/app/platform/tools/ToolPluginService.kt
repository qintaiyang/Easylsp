package io.github.easylsp.app.platform.tools
import bsh.Interpreter
import io.github.easylsp.app.EasylspApp
import io.github.easylsp.app.platform.tasks.FileTaskService
import kotlinx.coroutines.runInterruptible
import org.json.*
import java.io.File
import java.util.UUID

class ToolPluginService : FileTaskService() {
    private data class Loaded(val revision: Long, val registry: ToolPluginRegistry, val interpreter: Interpreter)
    companion object { private val loaded = mutableMapOf<String, Loaded>() }
    private fun unload(id: String) {
        val previous = loaded.remove(id) ?: return
        val method = previous.interpreter.nameSpace.getMethod("onUnload", emptyArray<Class<*>>())
        method?.invoke(emptyArray<Any>(), previous.interpreter)
        previous.registry.entries.clear()
    }
    override suspend fun execute(payload: JSONObject, progress: (String) -> Unit): JSONObject {
        val graph = application as EasylspApp
        val enabled = graph.store.workflows.enabledToolPlugins()
        loaded.keys.filter { id -> enabled.none { it.id == id } }.toList().forEach(::unload)
        for (plugin in enabled) if (loaded[plugin.id]?.revision != plugin.revision) {
            unload(plugin.id)
            UUID.fromString(plugin.id)
            val registry = ToolPluginRegistry()
            val interpreter = Interpreter().apply { setClassLoader(javaClass.classLoader); set("toolPlugin", registry) }
            val file = File(filesDir, "tool-plugins/${plugin.id}/${plugin.revision}.java")
            runInterruptible { file.reader().use { interpreter.eval(it) } }
            loaded[plugin.id] = Loaded(plugin.revision, registry, interpreter)
        }
        if (payload.getString("action") == "list") {
            val tools = JSONArray()
            enabled.forEach { plugin -> loaded.getValue(plugin.id).registry.entries.values.forEach { entry ->
                tools.put(JSONObject().put("pluginId", plugin.id).put("revision", plugin.revision).put("name", entry.name()).put("description", entry.description()).put("inputSchema", entry.schema()))
            } }
            return JSONObject().put("tools", tools)
        }
        val plugin = loaded[payload.getString("pluginId")] ?: error("工具插件未启用")
        require(plugin.revision == payload.getLong("revision")) { "工具插件已修改，请重新开始任务" }
        val tool = plugin.registry.entries[payload.getString("name")] ?: error("工具不存在")
        val result = runInterruptible { tool.callback().execute(payload.getJSONObject("arguments")) }
        return JSONObject().put("result", result ?: JSONObject.NULL)
    }
}
