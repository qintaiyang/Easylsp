package io.github.easylsp.app.platform.tools

import io.github.easylsp.app.EasylspApp
import io.github.easylsp.app.data.PluginProcesses
import org.json.*
import java.io.File

object ProjectContext {
    fun read(graph: EasylspApp, binding: RunBinding): JSONObject {
        val project = binding.project
        val current = requireNotNull(graph.store.dao.project(project.id))
        fun sample(revision: Long): Any = graph.store.dao.sample(project.id, revision)?.let {
            JSONObject().put("revision", it.revision).put("version", it.version).put("capturedAt", it.createdAt)
        } ?: JSONObject.NULL
        val installed = if (project.kind == "INSTALLED") try {
            val info = graph.targetApplications.info(project.packageName, project.userId)
            JSONObject().put("versionName", info.versionName).put("versionCode", info.longVersionCode).put("observedAt", System.currentTimeMillis())
        } catch (error: android.content.pm.PackageManager.NameNotFoundException) { JSONObject().put("unavailable", error.toString()) } else JSONObject.NULL
        return JSONObject().put("conversationId", binding.conversationId).put("projectId", project.id).put("packageName", project.packageName)
            .put("userId", project.userId).put("kind", project.kind).put("boundSample", sample(binding.sampleRevision))
            .put("latestSample", sample(current.sampleRevision)).put("installed", installed)
            .put("targets", JSONArray(graph.bridge.candidates(project).map(graph.bridge::identity)))
            .put("root", graph.root.state.value.detail).put("framework", graph.framework.state.value.description)
            .put("linuxInstalled", graph.linux.installed).put("harnessInstalled", graph.harness.installed)
            .put("nativeBackend", project.nativeBackend).put("nativePolicy", project.nativePolicy)
            .put("toolDiscovery", "search_tools lists availability and missing requirements; select_tools enables required operations")
            .put("staticAddressSource", "boundSample unless explicitly overridden")
    }
    suspend fun coverage(graph: EasylspApp, binding: RunBinding, args: JSONObject = JSONObject()): JSONObject {
        val project = binding.project
        val cursor = args.optInt("cursor", 0); val limit = args.optInt("limit", 10)
        require(cursor >= 0 && limit in 1..50) { "cursor 必须 >= 0；limit 必须在 1..50 范围内" }
        val plugins = graph.store.dao.plugins(project.id).filter { !args.has("pluginId") || it.id == args.getString("pluginId") }
        if (args.has("pluginId")) require(plugins.isNotEmpty()) { "插件不属于当前项目或不存在" }
        val selected = plugins.drop(cursor).take(limit)
        val native = if (selected.any { it.runtime == "NATIVE" }) graph.nativeInstances.observe(project.id) else null
        val sessions = graph.bridge.candidates(project).filter { !args.has("processName") || it.processName == args.getString("processName") }
        val published = File(graph.filesDir, "java-publications/" + project.id + ".json").let { if (it.exists()) JSONObject(it.readText()) else JSONObject() }
        val entries = published.optJSONArray("plugins") ?: JSONArray()
        val rows = selected.map { plugin ->
            if (plugin.runtime == "NATIVE") graph.nativeInstances.status(plugin,
                JSONObject().apply { if (args.has("processName")) put("processName", args.getString("processName")) }, native).apply {
                    for (key in listOf("collection", "evidence", "activation", "buildsMeaning", "builds")) remove(key)
                    optJSONObject("published")?.remove("config")
                    put("historyRead", JSONObject().put("tool", "get_native_plugin_status")
                        .put("arguments", JSONObject().put("pluginId", plugin.id).put("scope", "history")))
                }
            else {
                val pin = (0 until entries.length()).map { entries.getJSONObject(it) }.firstOrNull { it.getString("pluginId") == plugin.id }
                JSONObject().put("pluginId", plugin.id).put("engine", "JAVA").put("savedRevision", plugin.revision)
                    .put("processScope", plugin.processScope).put("processName", plugin.processName.ifEmpty { project.packageName })
                    .put("enabled", plugin.desiredEnabled).put("publishedRevision", pin?.getLong("revision") ?: JSONObject.NULL)
                    .put("publicationMissing", plugin.desiredEnabled && pin == null)
                    .put("publicationOutdated", pin != null && pin.getLong("revision") != plugin.revision)
                    .put("generation", published.opt("generation") ?: JSONObject.NULL)
                    .put("processes", JSONArray(sessions.map { session ->
                        val instance = graph.bridge.instances.value[session.id + "/" + plugin.id]
                        graph.bridge.identity(session).put("configurationMatches", PluginProcesses.matches(plugin, project.packageName, session.processName))
                            .put("reportedState", instance?.state ?: "NO_REPORT").put("runningRevision", instance?.revision ?: JSONObject.NULL)
                            .put("instanceId", instance?.instanceId ?: JSONObject.NULL).put("error", instance?.error ?: "")
                            .put("revisionDiffers", instance != null && instance.revision != plugin.revision)
                            .put("restartRequiredForPublication", instance != null && pin != null && instance.revision != pin.getLong("revision"))
                    }))
            }
        }
        return JSONObject().put("plugins", JSONArray(rows)).put("bridgeOnline", sessions.isNotEmpty())
            .put("totalPlugins", plugins.size).put("nextCursor", if (cursor + selected.size < plugins.size) cursor + selected.size else JSONObject.NULL)
            .put("nativeCollection", native?.opt("collection") ?: JSONObject.NULL)
            .put("offlineMeaning", "No online report; published business hooks may still run")
            .put("functionalValidationRead", JSONObject().put("tool", "read_file").put("path", "plugin-validation.jsonl"))
    }
}
