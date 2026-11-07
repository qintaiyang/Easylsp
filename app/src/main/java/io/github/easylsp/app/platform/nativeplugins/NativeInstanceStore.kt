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
    private val cache = mutableMapOf<String, List<JSONObject>>()
    @Synchronized fun clearProject(projectId: String) { cache.remove(projectId); file(projectId).parentFile!!.deleteRecursively() }
    @Synchronized fun events(projectId: String): List<JSONObject> = cache.getOrPut(projectId) { file(projectId).takeIf { it.exists() }?.readLines()?.filter { it.isNotBlank() }?.map(::JSONObject).orEmpty() }
    @Synchronized fun append(projectId: String, incoming: List<JSONObject>) {
        if (incoming.isEmpty()) return
        val previous = events(projectId)
        val all = mergeNativeEvidence(previous, incoming).takeLast(10000)
        if (all.size == previous.size && all.indices.all { all[it].toString() == previous[it].toString() }) return
        cache[projectId] = all
        val output = file(projectId); output.parentFile!!.mkdirs()
        if (all.size >= previous.size && previous.indices.all { all[it].toString() == previous[it].toString() })
            output.appendText(all.drop(previous.size).joinToString("\n", postfix = "\n"))
        else output.writeText(all.joinToString("\n", postfix = if (all.isEmpty()) "" else "\n"))
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
    fun receive(session: io.github.easylsp.app.platform.bridge.BridgeHub.Session, line: String) = receiveBatch(session, listOf(line))
    fun receiveBatch(session: io.github.easylsp.app.platform.bridge.BridgeHub.Session, lines: List<String>) {
        require(lines.size <= 32)
        val rows = lines.map { line ->
            require(line.length <= 2048)
            val row = JSONObject(line)
            require(row.getInt("schema") == 1 && row.getInt("uid") == session.uid && row.getInt("pid") == session.pid && row.getLong("startTicks") > 0)
            val build = requireNotNull(graph.store.nativePlugins.build(row.getString("buildId")))
            require(build.pluginId == row.getString("pluginId") && graph.store.dao.project(build.projectId)?.packageName == session.packageName)
            build.projectId to row.put("sessionId", session.id).put("source", "bridge")
        }
        rows.groupBy({ it.first }, { it.second }).forEach { (project, batch) -> append(project, batch) }
    }
    suspend fun collect(projectId: String): JSONObject {
        val project = requireNotNull(graph.store.dao.project(projectId))
        if (project.kind != "INSTALLED") return JSONObject().put("collected", false).put("reason", "静态项目尚未发布到已安装目标")
        if (!graph.root.useRoot()) return JSONObject().put("collected", false).put("reason", "读取独立 native 日志需要已授予的 root；保留已有证据")
        val uid = requireNotNull(graph.targetApplications.info(project.packageName, project.userId).applicationInfo).uid
        if (project.nativeBackend == "ZYGISK") {
            val result = if (graph.store.dao.plugins(projectId).any { it.nativeProcessType != "app" }) graph.nativeHost.call("events", uid.toString(), "isolated") else graph.nativeHost.call("events", uid.toString())
            val incoming = result.getJSONArray("events")
            val rows = (0 until incoming.length()).map { incoming.getJSONObject(it) }.filter { event ->
                val build = graph.store.nativePlugins.build(event.getString("buildId"))
                build?.projectId == projectId && build.pluginId == event.getString("pluginId")
            }
            append(projectId, rows.map { it.put("source", "host-direct") })
            return result.put("collected", true).put("eventsRead", rows.size).put("note", "直接读取存活进程的有界事件缓冲；没有事件不等于未执行，退出进程需诊断采集")
        }
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
    /** Shared by all plugins in one coverage request. Native evidence does not require a Java bridge. */
    suspend fun observe(projectId: String): JSONObject {
        val collection = collect(projectId)
        collection.remove("events")
        val project = requireNotNull(graph.store.dao.project(projectId))
        val processes = graph.bridge.candidates(project).filter { it.startTicks != null }.map(graph.bridge::identity).toMutableList()
        val pids = events(projectId).map { it.getInt("pid") }.distinct()
        if (pids.isNotEmpty() && graph.root.useRoot()) {
            val output = StringBuilder()
            val script = "for p in " + pids.joinToString(" ") + "; do " +
                "if s=\$(cat /proc/\$p/stat 2>/dev/null) && u=\$(stat -c %u /proc/\$p 2>/dev/null); then " +
                "n=\$(tr '\\000' '\\n' < /proc/\$p/cmdline 2>/dev/null | head -n 1); " +
                "printf '%s\\t%s\\t%s\\n' \"\$s\" \"\$u\" \"\$n\"; fi; done"
            val result = graph.executions.runner.execute(UUID.randomUUID().toString(), listOf(graph.root.su, "-c", script), graph.filesDir, closeInput = true, timeoutSeconds = 15) { output.append(it) }
            if (result.exitCode == 0) {
                processes.removeAll { it.getInt("pid") in pids }
                output.lines().filter { it.isNotEmpty() }.forEach { line ->
                    val row = line.split('\t', limit = 3)
                    val fields = row[0].substringAfterLast(") ").split(' ')
                    val pid = row[0].substringBefore(' ').toInt()
                    if (fields[0] !in setOf("Z", "X")) processes.add(JSONObject().put("pid", pid).put("uid", row[1].toInt())
                        .put("startTicks", fields[19].toLong()).put("processName", row[2]))
                }
            }
            collection.put("livenessRead", if (result.exitCode == 0) "PROC_STAT" else "UNAVAILABLE")
        }
        return JSONObject().put("collection", collection).put("processes", JSONArray(processes)).put("observedAt", System.currentTimeMillis())
    }
    suspend fun status(plugin: PluginEntity, args: JSONObject = JSONObject(), observed: JSONObject? = null): JSONObject {
        require(plugin.runtime == "NATIVE")
        val observation = observed ?: observe(plugin.projectId)
        val collection = observation.getJSONObject("collection")
        graph.store.nativePlugins.builds(plugin.id).filter { it.state == "BUILDING" }.forEach { row ->
            if (graph.executions.store.records.value.firstOrNull { it.id == row.executionId }?.state == "INTERRUPTED")
                graph.store.nativePlugins.saveBuild(row.copy(state = "FAILED", error = "管理端退出导致构建中断；保留原 executionId，不自动重试"))
        }
        val builds = graph.store.nativePlugins.builds(plugin.id).take(1).map { row -> JSONObject().put("buildId", row.buildId)
            .put("sourceRevision", row.sourceRevision).put("sampleRevision", row.sampleRevision).put("state", row.state)
            .put("executionId", row.executionId).put("error", row.error).put("abi", row.abi).put("minApi", row.minApi) }
        val publication = graph.store.nativePlugins.published(plugin.projectId)
        val snapshot = publication?.let { JSONObject(it.snapshot) }
        val items = snapshot?.getJSONArray("plugins") ?: JSONArray()
        val published = (0 until items.length()).map { items.getJSONObject(it) }.firstOrNull { it.getString("pluginId") == plugin.id }
        val processRows = observation.getJSONArray("processes")
        val page = NativeStatusQuery.page(events(plugin.projectId).filter { it.getString("pluginId") == plugin.id },
            (0 until processRows.length()).map { processRows.getJSONObject(it) }, args)
        val project = requireNotNull(graph.store.dao.project(plugin.projectId))
        val current = if (project.nativeBackend == "ZYGISK" && publication != null) graph.nativeHost.current(publication.uid, publication.packageName)?.let(graph.nativeHost::canonical)
            else if (graph.framework.state.value.active && publication != null) graph.framework.nativeSnapshot("${publication.uid}:${publication.packageName}")?.let(::JSONObject) else null
        return page.put("pluginId", plugin.id).put("runtime", "NATIVE").put("sourceRevision", plugin.revision)
            .put("builds", JSONArray(builds)).put("published", published ?: JSONObject.NULL).put("generation", publication?.generation ?: JSONObject.NULL)
            .put("frameworkGeneration", current?.getString("generation") ?: JSONObject.NULL).put("policy", publication?.policy ?: "JAVA_ONLY")
            .put("collection", collection).put("observedAt", observation.getLong("observedAt")).put("buildsMeaning", "Latest build only")
            .put("evidence", "当前实例须匹配观察到的 UID/PID/startTicks；缺少证据表示未知，不代表未执行。scope=history 读取历史摘要，evidenceRead 读取事件。")
            .put("activation", "NEXT_PROCESS")
    }
}
