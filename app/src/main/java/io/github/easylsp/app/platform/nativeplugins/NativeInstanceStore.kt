package io.github.easylsp.app.platform.nativeplugins

import io.github.easylsp.app.EasylspApp
import io.github.easylsp.app.data.PluginEntity
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Evidence is keyed by process lifetime and instance, independent of Java Binder sessions. */
class NativeInstanceStore(private val graph: EasylspApp) {
    private fun file(projectId: String): File {
        UUID.fromString(projectId)
        return File(graph.filesDir, "native-instances/$projectId/events.jsonl")
    }
    @Synchronized fun events(projectId: String): List<JSONObject> = file(projectId).takeIf { it.exists() }?.readLines()?.filter { it.isNotBlank() }?.map(::JSONObject).orEmpty()
    @Synchronized fun append(projectId: String, incoming: List<JSONObject>) {
        if (incoming.isEmpty()) return
        val all = mergeNativeEvidence(events(projectId), incoming).takeLast(10000)
        val output = file(projectId); output.parentFile!!.mkdirs()
        output.writeText(all.joinToString("\n", postfix = if (all.isEmpty()) "" else "\n"))
    }
    /** Explicit diagnostic reads and collection completion import only that run, once per batch. */
    fun importDiagnostic(projectId: String, id: String, report: JSONObject): Int {
        val row = graph.diagnostics.store.requireRun(projectId, id)
        val project = requireNotNull(graph.store.dao.project(projectId))
        require(row.packageName == project.packageName && report.getString("projectId") == projectId)
        val input = File(graph.diagnostics.store.directory(projectId, id), "native-plugin-events.jsonl")
        if (!input.exists()) return 0
        val uid = report.getInt("uid")
        val rows = input.readLines().mapIndexedNotNull { index, line ->
            if (line.isBlank()) null else JSONObject(line).takeIf { event ->
                val build = graph.store.nativePlugins.build(event.getString("buildId"))
                event.getInt("schema") == 1 && event.getInt("uid") == uid && event.getInt("pid") > 0 && event.getLong("startTicks") > 0 &&
                    build?.projectId == projectId && build.pluginId == event.getString("pluginId")
            }?.put("diagnosticRunId", id)?.put("diagnosticFile", "native-plugin-events.jsonl")?.put("diagnosticLine", index + 1)
        }
        append(projectId, rows)
        return rows.size
    }
    fun receive(session: io.github.easylsp.app.platform.bridge.BridgeHub.Session, line: String) {
        require(line.length <= 2048)
        val row = JSONObject(line)
        require(row.getInt("schema") == 1 && row.getInt("uid") == session.uid && row.getInt("pid") == session.pid && row.getLong("startTicks") > 0)
        val build = requireNotNull(graph.store.nativePlugins.build(row.getString("buildId")))
        require(build.pluginId == row.getString("pluginId") && graph.store.dao.project(build.projectId)?.packageName == session.packageName)
        append(build.projectId, listOf(row.put("sessionId", session.id).put("source", "bridge")))
    }
    suspend fun collect(projectId: String): JSONObject {
        val project = requireNotNull(graph.store.dao.project(projectId))
        if (project.kind != "INSTALLED") return JSONObject().put("collected", false).put("reason", "静态项目尚未发布到已安装目标")
        if (!graph.root.useRoot()) return JSONObject().put("collected", false).put("reason", "读取独立 native 日志需要已授予的 root；保留已有证据")
        val uid = graph.packageManager.getApplicationInfo(project.packageName, 0).uid
        val text = StringBuilder()
        val result = graph.executions.runner.execute(UUID.randomUUID().toString(), listOf(graph.root.su, "-c", "logcat -d -t 2000 -v raw --uid=$uid -s EasylspNative:I '*:S'"), graph.filesDir, closeInput = true, timeoutSeconds = 15) { text.append(it) }
        check(result.exitCode == 0) { "native 日志读取失败：$text" }
        val rows = text.lineSequence().filter { it.startsWith("{\"schema\":1,") }.map(::JSONObject).filter { event ->
            val build = graph.store.nativePlugins.build(event.getString("buildId"))
            event.getInt("uid") == uid && event.getInt("pid") > 0 && event.getLong("startTicks") > 0 &&
                build?.projectId == projectId && build.pluginId == event.getString("pluginId")
        }.toList()
        append(projectId, rows.map { it.put("source", "logcat") })
        return JSONObject().put("collected", true).put("eventsRead", rows.size).put("note", "日志可能已被系统覆盖；没有事件不等于未执行")
    }
    suspend fun status(plugin: PluginEntity): JSONObject {
        require(plugin.runtime == "NATIVE")
        val collection = collect(plugin.projectId)
        graph.store.nativePlugins.builds(plugin.id).filter { it.state == "BUILDING" }.forEach { row ->
            if (graph.executions.store.records.value.firstOrNull { it.id == row.executionId }?.state == "INTERRUPTED")
                graph.store.nativePlugins.saveBuild(row.copy(state = "FAILED", error = "管理端退出导致构建中断；保留原 executionId，不自动重试"))
        }
        val builds = graph.store.nativePlugins.builds(plugin.id).map { row -> JSONObject().put("buildId", row.buildId)
            .put("sourceRevision", row.sourceRevision).put("sampleRevision", row.sampleRevision).put("state", row.state)
            .put("executionId", row.executionId).put("error", row.error).put("abi", row.abi).put("minApi", row.minApi) }
        val publication = graph.store.nativePlugins.published(plugin.projectId)
        val snapshot = publication?.let { JSONObject(it.snapshot) }
        val items = snapshot?.getJSONArray("plugins") ?: JSONArray()
        val published = (0 until items.length()).map { items.getJSONObject(it) }.firstOrNull { it.getString("pluginId") == plugin.id }
        val instances = events(plugin.projectId).filter { it.getString("pluginId") == plugin.id }.groupBy { "${it.getInt("uid")}/${it.getInt("pid")}/${it.getLong("startTicks")}/${it.getString("instanceId")}" }.values.map { list ->
            val latest = list.maxBy { it.getLong("sequence") }
            val lifecycle = nativeLifecycle(list)
            val hooks = list.filter { it.getInt("hookId") != 0 }.groupBy { it.getInt("hookId") }.map { (id, changes) ->
                val last = changes.maxBy { it.getLong("sequence") }
                JSONObject().put("hookId", id).put("phase", last.getString("phase")).put("code", last.getInt("code"))
                    .put("hitObserved", changes.any { it.getString("phase") == "hook_hit" }).put("detail", last.getString("detail"))
            }
            JSONObject().put("instanceId", latest.getString("instanceId")).put("uid", latest.getInt("uid")).put("pid", latest.getInt("pid"))
                .put("startTicks", latest.getLong("startTicks")).put("buildId", latest.getString("buildId")).put("generation", latest.getString("generation"))
                .put("sourceRevision", latest.getLong("sourceRevision")).put("lifecycle", lifecycle?.getString("phase") ?: "UNKNOWN")
                .put("hooks", JSONArray(hooks)).put("dropped", latest.getLong("dropped"))
                .put("liveness", "NOT_CHECKED").put("sources", JSONArray(list.flatMap { event -> val sources = event.optJSONArray("sources"); (0 until (sources?.length() ?: 0)).map { sources!!.getJSONObject(it) } }.distinctBy { it.toString() }))
        }
        val current = if (graph.framework.state.value.active && publication != null) graph.framework.nativeSnapshot("${publication.uid}:${publication.packageName}")?.let(::JSONObject) else null
        return JSONObject().put("pluginId", plugin.id).put("runtime", "NATIVE").put("sourceRevision", plugin.revision)
            .put("builds", JSONArray(builds)).put("published", published ?: JSONObject.NULL).put("generation", publication?.generation ?: JSONObject.NULL)
            .put("frameworkGeneration", current?.getString("generation") ?: JSONObject.NULL).put("policy", publication?.policy ?: "JAVA_ONLY")
            .put("instances", JSONArray(instances)).put("collection", collection)
            .put("evidence", if (instances.isEmpty()) "尚无执行证据" else "已采集实际进程事件；不代表当前进程仍存活或目标问题已修复")
            .put("activation", "NEXT_PROCESS")
    }
}
