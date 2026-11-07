package io.github.easylsp.app.platform.tools

import android.os.Bundle
import io.github.easylsp.app.EasylspApp
import io.github.easylsp.app.data.*
import io.github.easylsp.app.platform.mcp.McpRegistry
import io.github.easylsp.app.platform.tasks.RemoteTaskClient
import kotlinx.coroutines.*
import org.json.*
import java.util.UUID

data class RunBinding(val projectBinding: ProjectEntity?, val sampleRevision: Long, val sessionId: String?, val conversationId: String,
    val askUser: suspend (JSONObject) -> JSONObject = { error("此执行入口不支持用户提问") }) {
    val project get() = requireNotNull(projectBinding) { "当前会话没有项目绑定" }
    val workspace get() = io.github.easylsp.app.platform.terminal.Workspace(projectBinding?.id ?: conversationId, if (projectBinding == null) "GLOBAL" else "PROJECT")
}
data class ToolDefinition(val name: String, val description: String, val schema: JSONObject, val source: String = "内置", val writes: Boolean = false,
    val preview: suspend (JSONObject) -> String = { it.toString(2) }, val invoke: suspend (JSONObject) -> JSONObject, val close: () -> Unit = {},
    val groupId: String = "other", val defaultAvailable: Boolean = false, val writesFor: (JSONObject) -> Boolean = { writes }) {
    fun json() = JSONObject().put("type", "function").put("function", JSONObject().put("name", name).put("description", "[$source] $description").put("parameters", schema))
}
object ToolArguments {
    fun validate(schema: JSONObject, args: JSONObject) {
        val required = schema.optJSONArray("required") ?: JSONArray()
        for (i in 0 until required.length()) require(args.has(required.getString(i)) && !args.isNull(required.getString(i))) { "缺少参数 ${required.getString(i)}" }
        val properties = schema.optJSONObject("properties") ?: JSONObject()
        args.keys().forEach { key ->
            require(properties.has(key) || schema.optBoolean("additionalProperties", true)) { "未知参数 $key" }
            val spec = properties.optJSONObject(key) ?: return@forEach
            val value = args.get(key)
            val valid = when (spec.optString("type")) { "string" -> value is String; "integer" -> value is Number && value.toDouble() == value.toLong().toDouble(); "number" -> value is Number; "boolean" -> value is Boolean; "object" -> value is JSONObject; "array" -> value is JSONArray; else -> true }
            require(valid) { "参数 $key 类型应为 ${spec.optString("type")}" }
            spec.optJSONArray("enum")?.let { choices ->
                require((0 until choices.length()).any { choices.get(it) == value }) { "参数 $key 必须为 $choices 中的一项" }
            }
            if (value is Number) {
                if (spec.has("minimum")) require(value.toDouble() >= spec.getDouble("minimum")) { "参数 $key 必须 >= ${spec.opt("minimum")}" }
                if (spec.has("maximum")) require(value.toDouble() <= spec.getDouble("maximum")) { "参数 $key 必须在 ${spec.opt("minimum")}..${spec.opt("maximum")} 范围内" }
            }
        }
    }
}
class ToolCatalog(private val graph: EasylspApp) {
    private val groups by lazy {
        val manifest = JSONObject(graph.assets.open("tool-groups.json").bufferedReader().use { it.readText() })
        val rows = manifest.getJSONArray("groups")
        buildMap<String, String> {
            for (i in 0 until rows.length()) {
                val group = rows.getJSONObject(i); val names = group.getJSONArray("tools")
                for (j in 0 until names.length()) put(names.getString(j), group.getString("id"))
            }
        }
    }
    fun unavailableReason(tool: ToolDefinition, binding: RunBinding): String? = when {
        tool.groupId.startsWith("project.") && binding.projectBinding == null -> "需要真实项目绑定；请从设备应用或导入 APK 项目进入会话。独立聊天仍可使用文件、终端和明确 pluginId 的发行工具。"
        tool.groupId == "project.analysis" && binding.sampleRevision == 0L && tool.name !in setOf("capture_sample", "get_project_info") -> "需要先采集或导入 APK 样本。"
        else -> null
    }
    fun groupTools(): Map<String, List<String>> = groups.entries.groupBy({ it.value }, { it.key })
    private val defaultTools = setOf("search_tools", "select_tools", "list_skills", "read_skill", "ask_user", "read_tool_output", "update_plan")
    private val prefs = graph.getSharedPreferences("tool-settings", 0)
    fun enabled(name: String) = !prefs.getStringSet("disabled", emptySet())!!.contains(name)
    fun enable(name: String, enabled: Boolean) {
        val set = prefs.getStringSet("disabled", emptySet())!!.toMutableSet(); if (enabled) set.remove(name) else set.add(name)
        prefs.edit().putStringSet("disabled", set).apply()
    }
    suspend fun build(binding: RunBinding, includeDisabled: Boolean = false): List<ToolDefinition> = builtins(binding).filter { includeDisabled || enabled(it.name) }
    private suspend fun extensionTools(id: String, binding: RunBinding): List<JSONObject> {
        val server = graph.mcp.available(binding.projectBinding?.id).firstOrNull { it.id == id }
        if (server != null) return graph.mcp.tools(id, binding.projectBinding?.id, binding.conversationId)
        require(graph.store.workflows.enabledToolPlugins().any { it.id == id }) { "扩展未启用或不存在" }
        val tools = RemoteTaskClient(graph, ToolPluginService::class.java).call(JSONObject().put("action", "list")).getJSONArray("tools")
        return (0 until tools.length()).map { tools.getJSONObject(it) }.filter { it.getString("pluginId") == id }
    }
    fun builtins(binding: RunBinding): List<ToolDefinition> {
        val project by lazy { binding.project }
        val definitions = mutableListOf<ToolDefinition>()
        fun add(name: String, description: String, fields: Map<String,String> = emptyMap(), required: List<String> = emptyList(), writes: Boolean = false, preview: suspend (JSONObject) -> String = { it.toString(2) }, invoke: suspend (JSONObject) -> JSONObject) {
            val parameters = BuiltinToolSchemas.create(name, fields, required)
            definitions += ToolDefinition(name, description, parameters, writes = writes, preview = preview, invoke = invoke)
        }
        fun plugin(args: JSONObject): PluginEntity {
            val item = graph.store.dao.plugin(args.getString("pluginId")) ?: error("插件不存在")
            require(item.projectId == project.id) { "插件不属于当前项目" }; return item
        }
        fun session(): String {
            require(project.kind == "INSTALLED") { "导入 APK 项目不支持动态执行" }
            return graph.bridge.resolve(project, binding.sessionId).id
        }
        val paging = mapOf("offset" to "integer", "limit" to "integer")
        add("ask_user", "Ask the user a question. Provide options for single/multiple choice or omit options for text. Cancellation returns a cancelled result.", mapOf("question" to "string", "options" to "array", "multiple" to "boolean"), listOf("question")) { binding.askUser(it) }
        add("get_project_info", "Read conversation, bound/latest immutable samples, installed version and current target identities") { ProjectContext.read(graph, binding) }
        fun analysis(name: String, action: String, description: String, fields: Map<String, String>, required: List<String>) {
            add(name, description, fields + paging, required) { graph.analysis.call(project.id, binding.sampleRevision, action, it) }
        }
        analysis("read_manifest", "manifest", "Read Manifest in pages", emptyMap(), emptyList())
        analysis("search_classes", "classes", "Find classes; returns full DEX signatures and APK/DEX source", mapOf("query" to "string"), listOf("query"))
        analysis("search_strings", "strings", "Find methods referencing a string", mapOf("query" to "string"), listOf("query"))
        analysis("find_method", "methods", "Find methods by name/full signature; use returned parameterTypes for hooks", mapOf("query" to "string", "owner" to "string"), listOf("query"))
        analysis("find_field", "fields", "Find fields", mapOf("query" to "string", "owner" to "string"), listOf("query"))
        analysis("find_references", "references", "Find exact DEX member/type references", mapOf("target" to "string"), listOf("target"))
        analysis("decompile_class", "java", "Read Java class or selected method; partial decompilation is labelled", mapOf("className" to "string", "signature" to "string", "source" to "string"), listOf("className"))
        analysis("get_method_smali", "smali", "Read exact method Smali", mapOf("className" to "string", "signature" to "string", "source" to "string"), listOf("className", "signature"))
        analysis("list_resources", "resources", "List APK resource paths", mapOf("query" to "string"), emptyList())
        analysis("read_resource", "resource", "Read supported text/XML resource or binary metadata", mapOf("apk" to "string", "entry" to "string"), listOf("apk", "entry"))
        add("list_plugins", "List project plugin configurations and process bindings") { JSONObject().put("plugins", JSONArray(graph.store.dao.plugins(project.id).map { JSONObject().put("pluginId", it.id).put("name", it.name).put("revision", it.revision).put("runtime", it.runtime).put("entry", it.entry).put("enabled", it.desiredEnabled).put("processName", it.processName.ifEmpty { project.packageName }) })) }
        add("read_plugin", "Read plugin source with line pagination", mapOf("pluginId" to "string") + paging, listOf("pluginId")) { args ->
            val item = plugin(args); val lines = graph.store.read(item.id).lines(); val offset = args.optInt("offset", 0); val limit = args.optInt("limit", 100)
            require(offset >= 0 && limit in 1..200) { "offset 必须 >= 0；limit 必须在 1..200 范围内" }
            JSONObject().put("pluginId", item.id).put("revision", item.revision).put("runtime", item.runtime).put("entry", item.entry).put("startLine", offset + 1).put("text", lines.drop(offset).take(limit).joinToString("\n")).put("nextOffset", if (offset + limit < lines.size) offset + limit else JSONObject.NULL)
        }
        add("create_plugin", "Create a disabled plugin. runtime=BEANSHELL (default) or NATIVE; nativeEntry=src/main.c (C17, default) or src/main.cpp (C++17 with static STL). Read native-api; exceptions and STL objects stay inside the plugin.", mapOf("name" to "string", "source" to "string", "processName" to "string", "runtime" to "string", "nativeEntry" to "string"), listOf("name", "source"), true) { args ->
            val process = args.optString("processName", project.packageName)
            val item = graph.store.create(project, args.getString("name"), args.getString("source"), processName = process, runtime = args.optString("runtime", "BEANSHELL").uppercase(), nativeEntry = args.optString("nativeEntry", "src/main.c"))
            JSONObject().put("pluginId", item.id).put("revision", item.revision).put("runtime", item.runtime).put("entry", item.entry).put("enabled", false)
        }
        add("validate_plugin", "Validate BeanShell syntax without executing, saving, publishing or connecting to a target. Supply exactly one of pluginId (saved source) or source (draft text). Returns valid, line/column and nearby source on errors. Does not validate runtime classes or behavior. Native plugins use build_native_plugin.", mapOf("pluginId" to "string", "source" to "string")) { args ->
            require(args.has("pluginId") xor args.has("source")) { "必须且只能指定 pluginId 或 source" }
            val item = if (args.has("pluginId")) plugin(args) else null
            if (item != null) require(item.runtime == "BEANSHELL") { "Native 插件请使用 build_native_plugin" }
            PluginSyntax.validate(if (item != null) graph.store.read(item.id) else args.getString("source")).apply {
                if (item != null) { put("pluginId", item.id); put("revision", item.revision) }
            }
        }
        add("edit_plugin", "Save a new revision; does not change the running instance", mapOf("pluginId" to "string", "source" to "string"), listOf("pluginId", "source"), true,
            preview = { args -> val item = plugin(args); "${item.name} r${item.revision}\n--- 当前源码\n${graph.store.read(item.id)}\n+++ 待保存源码\n${args.getString("source")}" }) { args ->
            val item = plugin(args); graph.store.save(item.id, item.name, item.author, item.description, item.version, args.getString("source")); JSONObject().put("pluginId", item.id).put("revision", item.revision + 1)
        }
        for ((name, operation) in listOf("load_plugin" to "loadPlugin", "unload_plugin" to "unloadPlugin", "reload_plugin" to "reloadPlugin", "get_plugin_status" to "getPluginState")) {
            add(name, "Send $operation to the session bound to this run and wait for its actual result", mapOf("pluginId" to "string"), listOf("pluginId"), name != "get_plugin_status",
                preview = { args -> val item = plugin(args); "$operation · ${item.name} r${item.revision} · ${item.processName.ifEmpty { project.packageName }}\n${if (name == "load_plugin" || name == "reload_plugin") graph.store.read(item.id) else ""}" }) { args ->
                val item = plugin(args); require(item.runtime == "BEANSHELL") { "native 请使用 build_native_plugin / enable_native_plugin / disable_native_plugin / get_native_plugin_status；不支持 Java 热加载" }; val id = session(); val host = graph.bridge.sessions.value.getValue(id)
                require(PluginProcesses.matches(item, project.packageName, host.processName)) { "插件绑定进程与本轮会话不匹配" }
                if (name == "load_plugin" || name == "reload_plugin") graph.store.enable(item.id, true)
                val result = graph.bridge.commandSession(id, operation, item.id)
                graph.bridge.identity(host).put("message", result).put("sessionId", id).put("pluginId", item.id).put("state", graph.bridge.instances.value["$id/${item.id}"]?.state ?: "UNKNOWN").put("loadedRevision", graph.bridge.instances.value["$id/${item.id}"]?.revision ?: 0)
            }
        }
        add("read_plugin_logs", "Read Java logs for the bound session and native events across independent process instances. instanceId filters native evidence. Use this for plugin log output instead of guessing target app file paths.", mapOf("pluginId" to "string", "query" to "string", "instanceId" to "string") + paging) { args ->
            val offset = args.optInt("offset", 0); val limit = args.optInt("limit", 50); require(offset >= 0 && limit in 1..200) { "offset 必须 >= 0；limit 必须在 1..200 范围内" }
            val pluginId = if (args.has("pluginId")) plugin(args).id else null
            val query = args.optString("query")
            if (pluginId != null && graph.store.dao.plugin(pluginId)?.runtime == "BEANSHELL") {
                val rows = graph.logs.range(offset, limit + 1) { it.optInt("uid") / 100000 == project.userId && it.optString("packageName") == project.packageName && (binding.sessionId == null || it.optString("sessionId") == binding.sessionId) && it.optString("pluginId") == pluginId && it.toString().contains(query, true) }
                return@add JSONObject().put("items", JSONArray(rows.take(limit))).put("unit", "entries").put("nextOffset", if (rows.size > limit) offset + limit else JSONObject.NULL)
            }
            val includeNative = pluginId == null || graph.store.dao.plugin(pluginId)?.runtime == "NATIVE"
            val nativeRows = if (includeNative) {
                graph.nativeInstances.collect(project.id)
                graph.nativeInstances.events(project.id).filter { (pluginId == null || it.getString("pluginId") == pluginId) && (!args.has("instanceId") || it.getString("instanceId") == args.getString("instanceId")) && it.toString().contains(query, true) }
            } else emptyList()
            val javaRows = graph.logs.text { it.optString("packageName") == project.packageName && (binding.sessionId == null || it.optString("sessionId") == binding.sessionId) && (pluginId == null || it.optString("pluginId") == pluginId) && !args.has("instanceId") && it.toString().contains(query, true) }.lineSequence().filter { it.isNotEmpty() }.map(::JSONObject).toList()
            val rows = (javaRows + nativeRows).sortedBy { it.optLong("timestamp") }
            JSONObject().put("items", JSONArray(rows.drop(offset).take(limit))).put("total", rows.size).put("nextOffset", if (offset + limit < rows.size) offset + limit else JSONObject.NULL)
        }
        for (operation in listOf("viewTree", "memory", "performanceStart", "performanceStop", "creationStacks")) {
            val fields = when (operation) { "viewTree" -> mapOf("query" to "string") + paging; "creationStacks" -> mapOf("enabled" to "boolean"); else -> emptyMap() }
            add(operation, "Target process inspection: $operation. FPS reports Choreographer cadence; View tree excludes Compose semantics/WebView DOM.", fields, if (operation == "creationStacks") listOf("enabled") else emptyList(), operation in listOf("creationStacks", "performanceStart", "performanceStop")) { args ->
                val bundle = Bundle(); args.keys().forEach { key -> when (val value = args.get(key)) { is String -> bundle.putString(key, value); is Boolean -> bundle.putBoolean(key, value); is Number -> bundle.putInt(key, value.toInt()) } }
                JSONObject(graph.bridge.commandSession(session(), operation, arguments = bundle))
            }
        }
        add("read_tool_output", "Read a long result using its outputId and character cursor; scoped to this conversation", mapOf("outputId" to "string", "field" to "string", "equals" to "object") + paging, listOf("outputId")) { args ->
            if (args.has("field") || args.has("equals")) graph.toolOutputs.select(binding.conversationId, args.getString("outputId"), args.optString("field"), args.optJSONObject("equals")) else graph.toolOutputs.page(binding.conversationId, args.getString("outputId"), args.optInt("offset", 0), args.optInt("limit", 12000))
        }
        add("read_development_reference", "Read before writing hooks. document: plugin-api/example for Java; native-api/native-header/native-symbol-example/native-vaddr-example/native-lifecycle-example/native-jni-example/native-chain-example/native-register-example/native-intercept-example for C ABI and build; plugin-release for v3 local release preparation; paged by line", mapOf("document" to "string") + paging, listOf("document")) { args ->
            val path = when (args.getString("document")) { "plugin-api" -> "plugin-api.md"; "example" -> "replace-add/main.java"; "plugin-release" -> "plugin-release.md"; "native-api" -> "native-sdk/native-plugin-api.md"; "native-header" -> "native-sdk/easylsp_native_plugin.h"; "native-symbol-example" -> "early-symbol/src/main.c"; "native-vaddr-example" -> "elf-vaddr/src/main.c"; "native-lifecycle-example" -> "module-lifecycle/src/main.c"; "native-jni-example" -> "jni-onload/src/main.c"; "native-chain-example" -> "chain-a/src/main.c"; "native-register-example" -> "jni-register/src/main.c"; "native-intercept-example" -> "instruction-intercept/src/main.c"; else -> error("未知开发参考 document") }
            val lines = graph.assets.open(path).bufferedReader().use { it.readLines() }
            val offset = args.optInt("offset", 0); val limit = args.optInt("limit", 100); require(offset >= 0 && limit in 1..200) { "offset 必须 >= 0；limit 必须在 1..200 范围内" }
            JSONObject().put("text", lines.drop(offset).take(limit).joinToString("\n")).put("nextOffset", if (offset + limit < lines.size) offset + limit else JSONObject.NULL)
        }
        add("prepare_native_build_tools", "Prepare phone arm64 Clang/LLD and the Android API 31 target SDK. Separate from LLVM analysis tools. Returns executionId; read that execution without rerunning preparation.", writes = true) { graph.nativeBuilds.prepare(project.id) }
        add("build_native_plugin", "Compile a fixed saved C sourceRevision with Android headers and system libraries. No target session needed. Returns buildId/executionId; compiler success is not runtime evidence.", mapOf("pluginId" to "string", "sourceRevision" to "integer", "sampleRevision" to "integer"), listOf("pluginId", "sourceRevision"), true) { args ->
            graph.nativeBuilds.build(plugin(args), args.getLong("sourceRevision"), args.optLong("sampleRevision", binding.sampleRevision))
        }
        add("enable_native_plugin", "Publish this exact successful buildId. policy must explicitly select NATIVE_ONLY (skip module Java/Inspector) or NATIVE_AND_JAVA. Applies to the next target process; no session needed.", mapOf("pluginId" to "string", "buildId" to "string", "policy" to "string"), listOf("pluginId", "buildId", "policy"), true) { args ->
            graph.nativeDeployments.enable(plugin(args), args.getString("buildId"), args.getString("policy"))
        }
        add("disable_native_plugin", "Remove a native plugin from the next startup snapshot. Current processes retain their loaded code until exit; does not hot-unload or restart.", mapOf("pluginId" to "string"), listOf("pluginId"), true) { args -> graph.nativeDeployments.disable(plugin(args)) }
        add("get_native_plugin_status", "Read saved/published versions and compact instance summaries. scope=current (default) matches observed UID/PID/startTicks independently of Java sessions; scope=history pages recorded instances. No evidence means unknown, not non-execution. Detailed events/sources are available through each evidenceRead reference.", mapOf("pluginId" to "string", "scope" to "string", "processName" to "string", "instanceId" to "string", "cursor" to "integer", "limit" to "integer"), listOf("pluginId")) { args -> graph.nativeInstances.status(plugin(args), args) }
        add("list_extensions", "List enabled remote MCP servers and tool plugins without connecting. Discover only an extension needed for the task.") {
            JSONObject().put("extensions", JSONArray(graph.mcp.available(binding.projectBinding?.id).map { JSONObject().put("id", it.id).put("name", it.name).put("kind", "MCP") } + graph.store.workflows.enabledToolPlugins().map { JSONObject().put("id", it.id).put("name", it.name).put("kind", "script") }))
        }
        add("list_extension_tools", "Connect to the selected extension and read its cached tool schemas", mapOf("extensionId" to "string"), listOf("extensionId")) { args ->
            JSONObject().put("tools", JSONArray(extensionTools(args.getString("extensionId"), binding)))
        }
        add("call_extension_tool", "Invoke a discovered extension tool with its exact schema. Extensions may modify external state.", mapOf("extensionId" to "string", "name" to "string", "arguments" to "object"), listOf("extensionId", "name", "arguments"), true) { args ->
            val id = args.getString("extensionId"); val name = args.getString("name"); val arguments = args.getJSONObject("arguments")
            val server = graph.mcp.available(binding.projectBinding?.id).firstOrNull { it.id == id }
            if (server != null) ToolPayload(graph.mcp.call(id, binding.projectBinding?.id, binding.conversationId, name, arguments)) else {
                val tool = extensionTools(id, binding).first { it.getString("name") == name }
                ToolArguments.validate(tool.getJSONObject("inputSchema"), arguments)
                RemoteTaskClient(graph, ToolPluginService::class.java).call(JSONObject().put("action", "call").put("pluginId", id).put("revision", tool.getLong("revision")).put("name", name).put("arguments", arguments))
            }
        }
        add("update_plan", "Replace the conversation plan; items have description and status pending/in_progress/completed", mapOf("items" to "array"), listOf("items")) { args ->
            val items = args.getJSONArray("items")
            val rows = (0 until items.length()).map { i -> val item = items.getJSONObject(i); val status = item.getString("status"); require(status in setOf("pending", "in_progress", "completed")); PlanItemEntity(UUID.randomUUID().toString(), binding.conversationId, i, item.getString("description"), status) }
            graph.store.transaction { graph.store.workflows.clearPlan(binding.conversationId); rows.forEach(graph.store.workflows::savePlanItem) }
            JSONObject().put("items", items)
        }
        add("get_root_status", "Read actual su grant status and root execution preference") {
            graph.root.useRoot()
            val state = graph.root.state.value
            JSONObject().put("status", state.status).put("granted", state.granted).put("preferred", graph.root.preferred.value).put("detail", state.detail)
        }
        add("export_plugin_apk", "Generate a signed standalone Xposed APK from a saved plugin; does not install it", mapOf("pluginId" to "string"), listOf("pluginId"), true) { args ->
            val item = plugin(args); val file = graph.apkExporter.export(item.id)
            JSONObject().put("path", file.absolutePath).put("packageName", io.github.easylsp.app.platform.export.ApkExporter.packageName(item.id)).put("revision", item.revision)
        }
        add("run_command", "Run Android /system/bin/sh in the project workspace, including am/cmd/logcat and Android file paths. Python, apt-get and other Ubuntu tools belong in run_linux. Does not accept a pagination limit; page long output with read_execution. Check installed command options rather than assuming strace -k support. Returns an executionId; read_execution queries that same command without rerunning it. Uses granted su when root preference is enabled, otherwise app UID; shares a PTY with the visible terminal.", mapOf("command" to "string", "cwd" to "string", "timeoutSeconds" to "integer", "interactive" to "boolean", "waitSeconds" to "integer"), listOf("command"), true) { args ->
            val id = graph.executions.command(binding.workspace.id, args.getString("command"), args.optString("cwd", "."), args.optInt("timeoutSeconds", 120), args.optBoolean("interactive"))
            graph.executions.waitFor(binding.workspace.id, id, args.optInt("waitSeconds", 1)).put("executionId", id)
        }
        add("read_execution", "Read paged terminal, Skill, subagent or Frida output in this project", mapOf("executionId" to "string", "waitSeconds" to "integer") + paging, listOf("executionId")) { args ->
            val id = args.getString("executionId")
            if (args.has("waitSeconds")) graph.executions.waitUntil(binding.workspace.id, id, args.getInt("waitSeconds"))
            graph.executions.store.page(binding.workspace.id, id, args.optInt("offset", 0), args.optInt("limit", 12000))
        }
        add("write_stdin", "Write stdin to an interactive execution", mapOf("executionId" to "string", "text" to "string", "close" to "boolean"), listOf("executionId", "text"), true) { args -> graph.executions.input(binding.workspace.id, args.getString("executionId"), args.getString("text"), args.optBoolean("close")); JSONObject().put("written", true) }
        add("stop_execution", "Cancel a project execution, including its process or child model request", mapOf("executionId" to "string"), listOf("executionId"), true) { args -> graph.executions.stop(binding.workspace.id, args.getString("executionId")); JSONObject().put("cancelRequested", true) }
        add("list_skills", "Search Skills by name and description, with pagination. Guidance is read only when requested.", mapOf("query" to "string", "cursor" to "integer", "limit" to "integer", "includeDisabled" to "boolean")) { graph.skillPackages.list(binding.projectBinding?.id, it) }
        add("read_skill", "Read Skill instructions or a resource, with line pagination. Reading does not select tools.", mapOf("skillId" to "string", "path" to "string", "offset" to "integer", "limit" to "integer"), listOf("skillId")) { graph.skillPackages.readResult(binding.projectBinding?.id, it) }
        add("run_skill_script", "Execute a scripts/ resource in an enabled Skill package. shell uses Android; python/node/bash require the installed Linux environment.", mapOf("skillId" to "string", "path" to "string", "runtime" to "string", "arguments" to "object"), listOf("skillId", "path", "runtime", "arguments"), true) { args -> graph.skillPackages.requireSkill(args.getString("skillId"), binding.projectBinding?.id, true); JSONObject().put("executionId", graph.skillPackages.execute(binding.workspace.id, args.getString("skillId"), args.getString("path"), args.getJSONObject("arguments"), args.getString("runtime"))) }
        add("run_skill", "Start an enabled executable Skill with JSON arguments", mapOf("skillId" to "string", "arguments" to "object"), listOf("skillId", "arguments"), true,
            preview = { args -> val skill = graph.store.workflows.skill(args.getString("skillId")) ?: error("Skill 不存在"); "${skill.name} · ${skill.runtime}\n${skill.source}\n参数：${args.getJSONObject("arguments")}" }) { args -> graph.skillPackages.requireSkill(args.getString("skillId"), binding.projectBinding?.id, true); JSONObject().put("executionId", graph.skills.start(binding.workspace.id, args.getString("skillId"), args.getJSONObject("arguments"))) }
        add("frida_attach", "Attach user-installed frida-inject to an explicit PID with JavaScript. Requires actual device injector/permissions; no BeanShell conversion. Stop with stop_execution.", mapOf("pid" to "integer", "source" to "string", "binary" to "string", "root" to "boolean", "timeoutSeconds" to "integer"), listOf("pid", "source", "binary", "root"), true) { args -> JSONObject().put("executionId", graph.frida.start(project.id, args.getInt("pid"), args.getString("source"), args.getString("binary"), args.getBoolean("root"), args.optInt("timeoutSeconds", 3600))) }
        definitions += ReleaseTools(graph).definitions(binding)
        definitions += GitHubAuthorTools(graph).definitions(binding)
        definitions += WorkflowTools(graph).definitions(binding)
        if (binding.projectBinding != null) definitions += NativeTools(graph).definitions(binding)
        definitions += WorkspaceTools(graph, binding).definitions()
        definitions += PlatformTools(graph, binding).definitions()
        definitions += graph.mcp.restoredTools(binding)
        return definitions.map { tool ->
            val group = groups[tool.name] ?: tool.groupId
            tool.copy(groupId = group, defaultAvailable = tool.name in defaultTools || group in setOf("workspace.files", "workspace.execution"),
                invoke = { args -> withContext(io.github.easylsp.app.platform.terminal.ExecutionOwner.conversation.asContextElement(binding.conversationId)) { tool.invoke(args) } })
        }.sortedBy { it.name }
    }
}
