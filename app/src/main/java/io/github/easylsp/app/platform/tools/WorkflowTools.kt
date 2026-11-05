package io.github.easylsp.app.platform.tools

import android.content.Intent
import io.github.easylsp.app.EasylspApp
import io.github.easylsp.app.platform.root.RootCommand
import io.github.easylsp.app.platform.ai.ProviderModels
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.*
import java.io.File
import io.github.easylsp.app.platform.network.HttpCalls
import okhttp3.OkHttpClient
import okhttp3.Request

/** The same operations used by advanced screens, exposed as concrete model actions. */
class WorkflowTools(private val graph: EasylspApp) {
    fun definitions(binding: RunBinding): List<ToolDefinition> {
        val project by lazy { binding.project }; val list = mutableListOf<ToolDefinition>()
        fun add(name: String, description: String, fields: Map<String,String> = emptyMap(), required: List<String> = emptyList(), writes: Boolean = false, call: suspend (JSONObject) -> JSONObject) {
            val schema = BuiltinToolSchemas.create(name, fields, required)
            list += ToolDefinition(name, description, schema, writes = writes, invoke = call)
        }
        fun plugin(args: JSONObject) = requireNotNull(graph.store.dao.plugin(args.getString("pluginId"))).also { require(it.projectId == project.id) { "插件不属于当前项目" } }
        fun workspaceFile(path: String): File {
            val base = graph.executions.directory(binding.workspace.id)
            return File(base, path).canonicalFile.also { require(it.path.startsWith(base.canonicalPath + File.separator)) { "文件必须位于当前项目空间" } }
        }
        fun logFilter(args: JSONObject): (JSONObject) -> Boolean {
            val pluginId = if (args.has("pluginId")) plugin(args).id else null
            val sessionId = args.optString("sessionId")
            return { row -> row.optString("packageName") == project.packageName && (pluginId == null || row.optString("pluginId") == pluginId) && (sessionId.isEmpty() || row.optString("sessionId") == sessionId) }
        }
        add("start_native_diagnostic", "Start a bounded startup evidence collection BEFORE launching this project's app. mode: EXTERNAL_ONLY (default, preserves runtime behavior), ENTRY_ONLY, NATIVE_OBSERVE (built-in observation only; no user native/Java plugins), JAVA_NO_PLUGINS (Java initialization only; no user native plugins), NORMAL (published native policy and enabled Java plugins). ENTRY_ONLY loads neither engine nor Java hooks. processName: package or package:child, or * (default). restart=true explicitly force-stops all target processes before launch; required for entry comparison modes. Returns diagnosticRunId/executionId immediately; does not require a session. NATIVE_OBSERVE requires root and framework scope already enabled; read actual nativeEngine/probe outcomes and native-events.jsonl. Optional sampleRevision pins this run to an explicitly captured sample; after capture_sample pass its returned revision because the conversation binding stays unchanged.",
            mapOf("mode" to "string", "processName" to "string", "durationSeconds" to "integer", "restart" to "boolean", "sampleRevision" to "integer"), writes = true) { args ->
            graph.diagnostics.start(project, args.optLong("sampleRevision", binding.sampleRevision), io.github.easylsp.app.platform.diagnostics.DiagnosticMode.valueOf(args.optString("mode", "EXTERNAL_ONLY")),
                args.optString("processName", "*"), args.optInt("durationSeconds", 15), args.optBoolean("restart", false))
        }
        add("list_native_diagnostics", "List persisted startup diagnostics for this project, including offline targets. Reading never launches an app.") {
            JSONObject().put("diagnostics", JSONArray(graph.diagnostics.store.dao.list(project.id).map { row -> JSONObject().put("diagnosticRunId", row.id).put("state", row.state)
                .put("mode", row.mode).put("sampleRevision", row.sampleRevision).put("startedAt", row.startedAt).put("executionId", row.executionId ?: JSONObject.NULL) }))
        }
        add("read_native_diagnostic", "Read an existing structured report without launching the target. Optional artifact=report.md/events.jsonl or a raw/ evidence path returns a character page; nextOffset continues the same artifact. Original logcat-window contains system-wide local evidence; scope any conclusions to this run's process/time/UID.",
            mapOf("diagnosticRunId" to "string", "artifact" to "string", "offset" to "integer", "limit" to "integer"), listOf("diagnosticRunId")) { args ->
            val id = args.getString("diagnosticRunId"); val report = graph.diagnostics.read(project.id, id)
            if (!args.has("artifact")) report
            else {
                val base = graph.diagnostics.store.directory(project.id, id).canonicalFile
                val file = File(base, args.getString("artifact")).canonicalFile
                require(file.path.startsWith(base.path + File.separator) && file.isFile && file.extension != "pb") { "只能读取本次诊断的文本文件" }
                val text = file.readText(); val offset = args.optInt("offset", 0).coerceAtMost(text.length); val end = minOf(text.length, offset + args.optInt("limit", 12000))
                JSONObject().put("text", text.substring(offset, end)).put("nextOffset", if (end < text.length) end else JSONObject.NULL).put("totalCharacters", text.length)
            }
        }
        add("stop_native_diagnostic", "End an existing collector and finalize available evidence. Does not stop or restart the target. Read the same diagnosticRunId for final state.", mapOf("diagnosticRunId" to "string"), listOf("diagnosticRunId"), true) { args ->
            graph.diagnostics.stop(project.id, args.getString("diagnosticRunId"))
        }
        add("download_file", "Download an HTTP(S) file into a relative project workspace path for plugin ZIP/library/Skill import. Returns actual saved size; maximum 64 MiB.", mapOf("url" to "string", "path" to "string"), listOf("url", "path"), true) { args ->
            val file = workspaceFile(args.getString("path"))
            val request = Request.Builder().url(args.getString("url")).build()
            require(request.url.scheme in setOf("http", "https"))
            HttpCalls.execute(OkHttpClient.Builder().retryOnConnectionFailure(false).callTimeout(120, java.util.concurrent.TimeUnit.SECONDS).build(), request) { response ->
                file.parentFile!!.mkdirs()
                try {
                    requireNotNull(response.body).byteStream().use { input -> file.outputStream().use { out ->
                        val buffer = ByteArray(65536); var total = 0L
                        while (true) { val count = input.read(buffer); if (count < 0) break; total += count; require(total <= 64 * 1024 * 1024) { "下载超过 64 MiB" }; out.write(buffer, 0, count) }
                    } }
                } catch (e: Exception) { file.delete(); throw e }
            }
            JSONObject().put("path", file.relativeTo(graph.executions.directory(binding.workspace.id)).invariantSeparatorsPath).put("bytes", file.length())
        }
        add("import_plugin_zip", "Import a plugin ZIP from the project workspace, including source, metadata and DEX/APK libraries. Starts disabled.", mapOf("path" to "string"), listOf("path"), true) { args ->
            val item = workspaceFile(args.getString("path")).inputStream().use { graph.store.importPlugin(project, it) }
            JSONObject().put("pluginId", item.id).put("revision", item.revision).put("name", item.name)
        }
        add("export_plugin_zip", "Export a saved plugin with metadata, source files and libraries to a relative project workspace path.", mapOf("pluginId" to "string", "path" to "string"), listOf("pluginId", "path"), true) { args ->
            val item = plugin(args); val file = workspaceFile(args.getString("path")); file.parentFile!!.mkdirs()
            file.outputStream().use { graph.store.exportPlugin(item.id, it) }
            JSONObject().put("path", file.path).put("bytes", file.length()).put("revision", item.revision)
        }
        add("export_project_logs", "Export all stored logs scoped to this project, optionally one plugin/session, into a project workspace JSONL file.", mapOf("path" to "string", "pluginId" to "string", "sessionId" to "string"), listOf("path"), true) { args ->
            val file = workspaceFile(args.getString("path")); file.parentFile!!.mkdirs(); graph.logs.export(file.outputStream(), logFilter(args))
            JSONObject().put("path", file.path).put("bytes", file.length())
        }
        add("clear_project_logs", "Clear stored logs only within this project, optionally one plugin/session.", mapOf("pluginId" to "string", "sessionId" to "string"), writes = true) { args -> graph.logs.clear(logFilter(args)); JSONObject().put("cleared", true) }
        add("import_skill_zip", "Import a SKILL.md directory package ZIP from the project workspace. Read its instructions before enabling.", mapOf("path" to "string"), listOf("path"), true) { args ->
            val item = workspaceFile(args.getString("path")).inputStream().use { graph.skillPackages.import(it) }
            JSONObject().put("skillId", item.id).put("name", item.name)
        }
        add("list_target_sessions", "Read framework activation, root and all connected processes for the project; choose a sessionId for inspection or plugin actions.") {
            JSONObject().put("framework", graph.framework.state.value.description).put("root", graph.root.state.value.detail).put("sessions", JSONArray(graph.bridge.sessions.value.values.filter { it.packageName == project.packageName }.map { JSONObject().put("id", it.id).put("processName", it.processName).put("pid", it.pid) }))
        }
        add("start_target", "Launch the selected app. restart=true force-stops its old processes first. Native execution does not require a Java session; query get_native_plugin_status after launch.", mapOf("restart" to "boolean"), writes = true) { args ->
            require(project.kind == "INSTALLED") { "导入 APK 仅支持静态分析" }
            if (args.optBoolean("restart")) {
                require(graph.root.useRoot()) { "重启目标需要已授予的 root" }
                val output = StringBuilder()
                val result = graph.executions.runner.execute(java.util.UUID.randomUUID().toString(), listOf(graph.root.su, "-c", "am force-stop ${RootCommand.quote(project.packageName)}"), graph.filesDir, closeInput = true) { output.append(it) }
                check(result.exitCode == 0) { output.toString() }
            }
            val intent = requireNotNull(graph.packageManager.getLaunchIntentForPackage(project.packageName)) { "目标无启动入口" }
            if (args.optBoolean("restart")) {
                val output = StringBuilder()
                val result = graph.executions.runner.execute(java.util.UUID.randomUUID().toString(), listOf(graph.root.su, "-c", "am start -W -n ${RootCommand.quote(requireNotNull(intent.component).flattenToString())}"), graph.filesDir, closeInput = true) { output.append(it) }
                check(result.exitCode == 0 && !output.contains("Error:")) { output.toString() }
            } else graph.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            if (graph.store.nativePlugins.published(project.id)?.policy == "NATIVE_ONLY") return@add JSONObject().put("started", true).put("restarted", args.optBoolean("restart")).put("note", "NATIVE_ONLY 不建立 Java session；查询 native 插件实际实例和目标行为")
            graph.sendBroadcast(Intent("io.github.easylsp.RECONNECT").setPackage(project.packageName))
            val connected = withTimeoutOrNull(10000) { graph.bridge.sessions.first { rows -> rows.values.any { it.packageName == project.packageName } } }
            JSONObject().put("started", true).put("sessions", JSONArray(connected?.values?.filter { it.packageName == project.packageName }?.map { JSONObject().put("sessionId", it.id).put("processName", it.processName) } ?: emptyList<JSONObject>())).put("note", if (connected == null) "目标启动请求已发出，尚无 Java session；这不能判定 native 是否执行" else "目标已连接")
        }
        add("reconnect_target", "Reconnect the running project target; read sessions after this action.", writes = true) { graph.sendBroadcast(Intent("io.github.easylsp.RECONNECT").setPackage(project.packageName)); JSONObject().put("requested", true) }
        for (restart in listOf(false, true)) add(if (restart) "restart_target" else "stop_target", "${if (restart) "Restart" else "Force stop"} the target app using device root. This ends all of its processes.", writes = true) {
            require(project.kind == "INSTALLED"); require(graph.root.useRoot()) { "需要在设备 root 管理器授权" }
            val output = StringBuilder(); val result = graph.executions.runner.execute(java.util.UUID.randomUUID().toString(), listOf(graph.root.su, "-c", "am force-stop ${RootCommand.quote(project.packageName)}"), graph.filesDir, closeInput = true) { output.append(it) }
            require(result.exitCode == 0) { output.toString() }
            if (restart) graph.startActivity(requireNotNull(graph.packageManager.getLaunchIntentForPackage(project.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            JSONObject().put("stopped", true).put("restarted", restart)
        }
        add("capture_sample", "Capture installed APK/splits as a new immutable sample. Current conversation keeps its original sample; use analysis_query with returned revision for new data.", writes = true) { val sampled = graph.samples.capture(project); JSONObject().put("sampleRevision", sampled.sampleRevision) }
        add("analysis_query", "Run any static analysis operation on a specific saved sample revision: info/classes/methods/fields/strings/references/java/smali/manifest/resources/resource. Arguments match built-in analysis tools and support offset/limit.", mapOf("action" to "string", "sampleRevision" to "integer", "arguments" to "object"), listOf("action", "arguments")) { args ->
            val action = args.getString("action"); require(action in setOf("info", "classes", "methods", "fields", "strings", "references", "java", "smali", "manifest", "resources", "resource"))
            graph.analysis.call(project.id, args.optLong("sampleRevision", binding.sampleRevision), action, args.getJSONObject("arguments"))
        }
        add("set_plugin_enabled", "Set expected enabled state independently of running state. Use explicit load/reload/unload to change target instances.", mapOf("pluginId" to "string", "enabled" to "boolean"), listOf("pluginId", "enabled"), true) { args -> val item = plugin(args); graph.store.enable(item.id, args.getBoolean("enabled")); JSONObject().put("enabled", args.getBoolean("enabled")) }
        add("plugin_action", "Load/reload/unload/status a plugin in an explicit project session. operation is loadPlugin/reloadPlugin/unloadPlugin/getPluginState. Returns actual running state and revision.", mapOf("pluginId" to "string", "sessionId" to "string", "operation" to "string"), listOf("pluginId", "sessionId", "operation"), true) { args ->
            val item = plugin(args); val sessionId = args.getString("sessionId")
            val session = graph.bridge.sessions.value[sessionId] ?: error("目标会话 $sessionId 已离线；请调用 list_target_sessions 查询在线目标，或重新连接目标")
            require(session.packageName == project.packageName) { "目标会话 $sessionId 不属于当前项目" }
            val operation = args.getString("operation"); require(operation in setOf("loadPlugin", "reloadPlugin", "unloadPlugin", "getPluginState"))
            if (operation == "loadPlugin" || operation == "reloadPlugin") graph.store.enable(item.id, true)
            val result = graph.bridge.commandSession(session.id, operation, item.id)
            val state = graph.bridge.instances.value["${session.id}/${item.id}"]
            JSONObject().put("result", result).put("state", state?.state ?: "UNKNOWN").put("revision", state?.revision ?: JSONObject.NULL).put("sessionId", session.id).put("pluginId", item.id).put("validation", "加载状态不代表功能验证完成")
        }
        add("edit_plugin_metadata", "Edit plugin name/author/description/version and process matching scope (exact/main/children/all). Saves a new revision without changing running instances.", mapOf("pluginId" to "string", "name" to "string", "author" to "string", "description" to "string", "version" to "string", "processName" to "string", "processScope" to "string"), listOf("pluginId"), true) { args ->
            val item = plugin(args); val scope = args.optString("processScope", item.processScope); require(scope in setOf("exact", "main", "children", "all"))
            graph.store.save(item.id, args.optString("name", item.name), args.optString("author", item.author), args.optString("description", item.description), args.optString("version", item.version), graph.store.read(item.id), args.optString("processName", item.processName))
            graph.store.dao.updatePlugin(requireNotNull(graph.store.dao.plugin(item.id)).copy(processScope = scope))
            JSONObject().put("revision", item.revision + 1).put("processScope", scope)
        }
        add("delete_plugin", "Unload all running project instances then delete this plugin and its files.", mapOf("pluginId" to "string"), listOf("pluginId"), true) { args ->
            val item = plugin(args)
            if (item.runtime == "NATIVE") graph.nativeDeployments.disable(item)
            else graph.bridge.sessions.value.values.filter { it.packageName == project.packageName && graph.bridge.instances.value["${it.id}/${item.id}"]?.state == "RUNNING" }.forEach { graph.bridge.commandSession(it.id, "unloadPlugin", item.id) }
            graph.store.delete(item.id); JSONObject().put("deleted", true)
        }
        add("list_plugin_libraries", "List saved DEX/APK dependencies for a plugin revision.", mapOf("pluginId" to "string"), listOf("pluginId")) { args -> JSONObject().put("libraries", JSONArray(graph.store.libraries(plugin(args)).map { it.name })) }
        add("add_plugin_library", "Attach a DEX/APK file from this project workspace as a new plugin revision. JAR/AAR conversion is separate. Explicit reload loads dependencies in an isolated class loader.", mapOf("pluginId" to "string", "path" to "string"), listOf("pluginId", "path"), true) { args ->
            val item = plugin(args); val base = graph.executions.directory(binding.workspace.id); val file = File(base, args.getString("path")).canonicalFile
            require(file.path.startsWith(base.canonicalPath + File.separator)) { "依赖文件必须位于项目空间" }
            val bytes = file.inputStream().use { it.readNBytes(16 * 1024 * 1024 + 1) }; val next = graph.store.addLibrary(item.id, file.name, bytes)
            JSONObject().put("revision", next.revision).put("library", file.name)
        }
        add("remove_plugin_library", "Remove a DEX/APK dependency in a new saved revision; running instance changes only after reload.", mapOf("pluginId" to "string", "name" to "string"), listOf("pluginId", "name"), true) { args -> val item = plugin(args); graph.store.removeLibrary(item.id, args.getString("name")); JSONObject().put("saved", true) }
        add("read_plugin_files", "List plugin source and attached text files, or read one relative path.", mapOf("pluginId" to "string", "path" to "string"), listOf("pluginId")) { args ->
            val files = graph.store.readFiles(plugin(args)); if (args.has("path")) JSONObject().put("text", files.getValue(args.getString("path"))) else JSONObject().put("paths", JSONArray(files.keys))
        }
        add("write_plugin_file", "Save a text file as a new revision. The runtime entry (main.java or src/main.c) remains required. Native files belong in src/ or include/.", mapOf("pluginId" to "string", "path" to "string", "text" to "string", "delete" to "boolean"), listOf("pluginId", "path"), true) { args ->
            val item = plugin(args); val path = args.getString("path"); io.github.easylsp.app.data.PluginPaths.validate(path)
            val files = graph.store.readFiles(item).toMutableMap(); if (args.optBoolean("delete")) { require(path != item.entry); files.remove(path) } else files[path] = args.getString("text")
            graph.store.save(item.id, item.name, item.author, item.description, item.version, files.getValue(item.entry), files = files)
            JSONObject().put("revision", item.revision + 1)
        }
        add("inspect_target", "Read View tree/properties, capture creation stacks, select target UI overlays or sample performance. Operations: viewTree/creationStacks/inspectOverlay/performanceStart/performanceRead/performanceStop/memory. Always use an explicit project session.", mapOf("sessionId" to "string", "operation" to "string", "arguments" to "object"), listOf("sessionId", "operation"), true) { args ->
            val sessionId = args.getString("sessionId")
            val session = graph.bridge.sessions.value[sessionId] ?: error("目标会话 $sessionId 已离线；请调用 list_target_sessions 查询在线目标，或重新连接目标")
            require(session.packageName == project.packageName) { "目标会话 $sessionId 不属于当前项目" }
            val operation = args.getString("operation"); require(operation in setOf("viewTree", "creationStacks", "inspectOverlay", "performanceStart", "performanceRead", "performanceStop", "performanceOverlay", "memory"))
            val bundle = android.os.Bundle(); val values = args.optJSONObject("arguments") ?: JSONObject()
            values.keys().forEach { key -> when (val value = values.get(key)) { is String -> bundle.putString(key, value); is Boolean -> bundle.putBoolean(key, value); is Number -> bundle.putInt(key, value.toInt()) } }
            JSONObject(graph.bridge.commandSession(session.id, operation, arguments = bundle))
        }
        add("linux_status", "Read actual Ubuntu installation and device root status, not the Linux user's identity.") { JSONObject().put("installed", graph.linux.installed).put("status", graph.linux.status.value).put("rootGranted", graph.root.state.value.granted) }
        add("install_linux", "Download Ubuntu 24.04 arm64 from its official source and install into private storage using device root. Run only when Linux/Python/Node tools are needed.", writes = true) {
            val id = graph.executions.start(binding.workspace.id, "environment", "安装 Ubuntu 24.04 arm64") { _, output ->
                coroutineScope {
                    val watcher = launch { graph.linux.status.collect { output(it + "\n") } }
                    try { output(graph.linux.install().toString(2)); 0 } finally { watcher.cancel() }
                }
            }
            JSONObject().put("executionId", id).put("note", "下载与解压在后台执行，用 read_execution 查看进度")
        }
        add("run_linux", "Run Ubuntu bash in /workspace/<projectId>, sharing files with run_command's project workspace. Use for Python, Node, apt-get and Linux tools; check linux_status for installation. Android am/cmd/logcat and target app private paths belong in run_command. Returns an executionId; query with read_execution without rerunning the command. backend chroot (default) or installed Termux proot.", mapOf("command" to "string", "timeoutSeconds" to "integer", "backend" to "string"), listOf("command"), true) { args ->
            val command = graph.linux.command(binding.workspace.id, args.getString("command"), args.optString("backend", "chroot"))
            val id = graph.executions.start(binding.workspace.id, "linux", args.getString("command")) { id, output -> graph.executions.runner.execute(id, command, graph.executions.directory(binding.workspace.id), closeInput = true, timeoutSeconds = args.optInt("timeoutSeconds", 600), output = output).exitCode }
            JSONObject().put("executionId", id)
        }
        add("import_skill_github", "Import a SKILL.md package from a GitHub repository/tree URL. Imported package starts disabled; inspect before enabling.", mapOf("url" to "string"), listOf("url"), true) { args -> val skill = graph.skillPackages.github(args.getString("url")); JSONObject().put("skillId", skill.id).put("name", skill.name) }
        add("list_provider_models", "Fetch real available model IDs from an already configured provider. Secrets stay in Keystore.", mapOf("providerId" to "string"), listOf("providerId")) { args -> val provider = requireNotNull(graph.store.workflows.provider(args.getString("providerId"))); JSONObject().put("models", JSONArray(ProviderModels().list(provider, graph.secrets.get("provider:${provider.id}")).map { it.id })) }
        add("storage_summary", "Read storage usage by actual category. Sizes are bytes; Linux installation is reported separately.") { graph.storage.summary() }
        add("clear_storage", "Delete storage data. Categories chat/samples/drafts/executions apply to this project; viewers clears the app-wide reading cache across ALL projects. Current/referenced samples and active executions are preserved. Draft deletion discards unsaved work.", mapOf("category" to "string"), listOf("category"), true) { args -> graph.storage.clear(project.id, args.getString("category")) }
        add("set_project_group", "Set a user-friendly project group name without changing package or routing IDs.", mapOf("name" to "string"), listOf("name"), true) { args -> val current = requireNotNull(graph.store.dao.project(project.id)); graph.store.dao.updateProject(current.copy(groupName = args.getString("name"))); JSONObject().put("group", args.getString("name")) }
        return list
    }
}
