package io.github.easylsp.app.feature.chat

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.easylsp.app.EasylspApp
import io.github.easylsp.app.data.*
import kotlinx.coroutines.*
import org.json.JSONObject

internal fun resultObject(text: String): JSONObject? = if (text.trimStart().startsWith("{")) runCatching { JSONObject(text) }.getOrNull() else null
internal fun activityName(name: String, args: JSONObject): String = when (name) {
    "inspect_target", "plugin_action" -> toolName(args.optString("operation"))
    else -> toolName(name)
}
internal fun toolSummary(run: ToolRunEntity?): String {
    if (run == null) return "等待执行"
    val result = resultObject(run.result)
    val state = result?.optString("state").orEmpty()
    val exit = result?.takeIf { it.has("exitCode") && !it.isNull("exitCode") }?.getInt("exitCode")
    return when {
        run.status == "ERROR" && run.name == "build_native_plugin" -> "Native 构建失败"
        run.name == "build_native_plugin" && state == "SUCCEEDED" -> "Native 构建成功 · r${result?.optLong("sourceRevision")}"
        run.name == "build_native_plugin" && state == "FAILED" -> "Native 构建失败"
        run.name == "build_native_plugin" && state == "BUILDING" -> "Native 构建进行中"
        run.name == "enable_native_plugin" && result?.has("generation") == true -> {
            val plugins = result.getJSONArray("plugins")
            val revisions = (0 until plugins.length()).map { "r${plugins.getJSONObject(it).getLong("sourceRevision")}" }.distinct().joinToString("、")
            val policy = when (result.getString("policy")) { "NATIVE_ONLY" -> "仅 Native"; "NATIVE_AND_JAVA" -> "Native 与 Java"; else -> "仅 Java" }
            "已发布 $revisions · $policy · 下次启动生效"
        }
        run.name == "get_native_plugin_status" && result?.has("instances") == true -> {
            val instances = result.getJSONArray("instances")
            val phases = (0 until instances.length()).map { when (instances.getJSONObject(it).optString("lifecycle")) {
                "plugin_loading" -> "加载中"; "plugin_initialized" -> "已初始化"; "plugin_init_failed" -> "初始化失败"; "plugin_load_failed" -> "加载失败"; else -> "阶段未知"
            } }.distinct().joinToString("、")
            if (result.optString("scope") == "current") {
                if (instances.length() == 0) "暂无当前实例证据 · 业务状态未知" else "${instances.length()} 个当前实例 · $phases · 功能待验证"
            } else if (instances.length() == 0) "尚无历史执行证据" else "${instances.length()} 个历史实例 · $phases"
        }
        exit != null && exit != 0 -> "命令失败 · 退出码 $exit"
        state in listOf("ERROR", "TIMEOUT", "CANCELLED", "INTERRUPTED", "UNKNOWN") -> statusName(state)
        run.status == "ERROR" && run.result.contains("目标") && (run.result.contains("离线") || run.result.contains("断开")) -> "目标已离线"
        run.status != "COMPLETE" -> statusName(run.status)
        exit == 0 && state == "COMPLETE" -> "执行成功 · 退出码 0"
        state == "RUNNING" && result?.has("executionId") == true -> "命令仍在运行"
        state == "RUNNING" -> "已加载 · 功能待验证"
        result?.has("executionId") == true -> "执行已提交 · 结果待查询"
        result?.has("loadedRevision") == true -> "已加载 r${result.optLong("loadedRevision")} · 功能待验证"
        result?.has("revision") == true -> "revision ${result.optLong("revision")}"
        result?.has("total") == true -> "${result.optInt("total")} 条结果"
        else -> "已完成"
    }
}

@Composable internal fun ToolRows(conversation: ConversationEntity, item: ChatTimelineItem.Tools, runs: List<ToolRunEntity>, graph: EasylspApp, openPlugin: (String) -> Unit, expandedTools: List<String>, setTools: (List<String>) -> Unit, expandedGroups: List<String>, setGroups: (List<String>) -> Unit) {
    val ids = item.calls.map { it.getString("id") }
    val expanded = ids.any { it in expandedGroups }
    val matching = item.calls.map { call -> call to runs.firstOrNull { it.toolCallId == call.getString("id") } }
    val failures = matching.count { it.second?.let { run -> run.status in listOf("ERROR", "UNKNOWN", "INTERRUPTED") || listOf("失败", "待确认", "中断", "超时", "停止", "离线").any { toolSummary(run).contains(it) } } == true }
    val running = matching.lastOrNull { it.second == null || it.second?.status == "RUNNING" }
    Column(Modifier.fillMaxWidth()) {
        if (matching.size > 1) Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { setGroups(if (expanded) expandedGroups - ids.toSet() else expandedGroups + ids) }, verticalAlignment = Alignment.CenterVertically) {
            Text(if (expanded) "▾" else "▸", modifier = Modifier.padding(end = 8.dp))
            val label = if (running != null) activityName(running.first.getJSONObject("function").getString("name"), JSONObject(running.first.getJSONObject("function").getString("arguments"))) + " · 进行中"
                else if (failures > 0) "${matching.size} 项操作" else "已完成 ${matching.size} 项操作"
            Text(label + if (failures > 0) " · $failures 项失败/待确认" else "", style = MaterialTheme.typography.bodySmall, maxLines = 2)
        }
        if (matching.size == 1 || expanded) {
            if (item.reasoning.isNotEmpty()) {
                var thinking by rememberSaveable(conversation.id, item.key) { mutableStateOf(false) }
                TextButton(onClick = { thinking = !thinking }) { Text(if (thinking) "收起思考" else "思考 · 展开") }
                if (thinking) SelectionContainer { Text(item.reasoning.joinToString("\n"), style = MaterialTheme.typography.bodySmall) }
            }
            matching.forEach { (call, run) -> key(call.getString("id")) {
                ToolActivity(conversation, call, run, graph, openPlugin, call.getString("id") in expandedTools) {
                    val id = call.getString("id"); setTools(if (id in expandedTools) expandedTools - id else expandedTools + id)
                }
            } }
        }
    }
}

@Composable private fun ToolActivity(conversation: ConversationEntity, call: JSONObject, run: ToolRunEntity?, graph: EasylspApp, openPlugin: (String) -> Unit, expanded: Boolean, toggle: () -> Unit) {
    val fn = call.getJSONObject("function")
    val args = remember(fn.toString()) { JSONObject(fn.getString("arguments")) }
    val name = activityName(fn.getString("name"), args)
    val target = listOf("query", "document", "command", "path", "className").firstNotNullOfOrNull { args.optString(it).takeIf(String::isNotBlank) }.orEmpty().lineSequence().first()
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { toggle() }, verticalAlignment = Alignment.CenterVertically) {
            Text(if (expanded) "▾" else "▸", modifier = Modifier.padding(end = 8.dp))
            Column(Modifier.weight(1f)) {
                Text("$name · ${toolSummary(run)}", style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (target.isNotEmpty()) Text(target, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            run?.finishedAt?.let { Text("${(it - run.startedAt) / 1000.0}s", style = MaterialTheme.typography.labelSmall) }
        }
        if (expanded) ToolDetail(conversation, fn.getString("arguments"), run, graph, openPlugin)
    }
}

@Composable private fun ToolDetail(conversation: ConversationEntity, arguments: String, run: ToolRunEntity?, graph: EasylspApp, openPlugin: (String) -> Unit) {
    val scope = rememberCoroutineScope(); val clipboard = LocalClipboardManager.current
    val result = remember(run?.result) { resultObject(run?.result.orEmpty()) }
    val outputId = result?.optString("outputId").orEmpty()
    val executionId = result?.optString("executionId").orEmpty().ifEmpty { result?.optString("id").orEmpty().takeIf { result?.has("kind") == true }.orEmpty() }
    var text by remember(run?.result) { mutableStateOf(result?.optString("text", run?.result.orEmpty()) ?: run?.result.orEmpty()) }
    var next by remember(run?.result) { mutableStateOf(result?.takeIf { it.has("nextOffset") && !it.isNull("nextOffset") }?.getInt("nextOffset")) }
    var lines by rememberSaveable { mutableIntStateOf(12) }
    var params by rememberSaveable { mutableStateOf(false) }
    var failure by remember { mutableStateOf("") }
    suspend fun page(offset: Int): JSONObject = withContext(Dispatchers.IO) {
        if (outputId.isNotEmpty()) graph.toolOutputs.page(conversation.id, outputId, offset, 6000)
        else graph.executions.store.page(conversation.projectId ?: conversation.id, executionId, offset, 6000)
    }
    LaunchedEffect(executionId) {
        if (executionId.isNotEmpty()) try {
            val first = page(0); text = first.getString("text"); next = first.takeUnless { it.isNull("nextOffset") }?.getInt("nextOffset")
        } catch (error: Exception) { failure = error.message.orEmpty() }
    }
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, bottom = 8.dp)) {
        result?.optJSONObject("image")?.optString("path")?.takeIf { it.isNotBlank() }?.let { path ->
            var bitmap by remember(path) { mutableStateOf<android.graphics.Bitmap?>(null) }
            LaunchedEffect(path) { bitmap = withContext(Dispatchers.IO) { android.graphics.BitmapFactory.decodeFile(path) } }
            bitmap?.let { Image(it.asImageBitmap(), "工具截图", modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp)) }
        }
        Text(toolSummary(run), style = MaterialTheme.typography.labelMedium)
        if (result?.has("state") == true) Text("状态 ${result.optString("state")} · ${if (result.isNull("exitCode")) "退出码未提供" else "退出码 ${result.opt("exitCode")}"}", style = MaterialTheme.typography.labelSmall)
        SelectionContainer { Text(text.lineSequence().take(lines).joinToString("\n").ifEmpty { "等待执行结果" }, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, modifier = Modifier.horizontalScroll(rememberScrollState())) }
        if (failure.isNotEmpty()) Text(failure, style = MaterialTheme.typography.labelSmall)
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            if (text.lineSequence().count() > lines || next != null) TextButton(onClick = { scope.launch {
                if (text.lineSequence().count() <= lines && next != null) try { val more = page(next!!); text += more.getString("text"); next = more.takeUnless { it.isNull("nextOffset") }?.getInt("nextOffset") } catch (error: Exception) { failure = error.message.orEmpty() }
                lines += 24
            } }) { Text("显示更多") }
            TextButton(onClick = { params = !params }) { Text(if (params) "收起参数" else "参数") }
            TextButton(onClick = { scope.launch {
                try {
                    var full = text
                    if (outputId.isNotEmpty() || executionId.isNotEmpty()) {
                        full = ""; var offset: Int? = 0
                        while (offset != null) { val part = page(offset); full += part.getString("text"); offset = part.takeUnless { it.isNull("nextOffset") }?.getInt("nextOffset") }
                    }
                    clipboard.setText(AnnotatedString(full))
                } catch (error: Exception) { failure = error.message.orEmpty() }
            } }) { Text("复制完整结果") }
            result?.optString("pluginId")?.takeIf(String::isNotEmpty)?.let { id -> TextButton(onClick = { openPlugin(id) }) { Text("打开插件") } }
        }
        if (params) SelectionContainer { Text(arguments, style = MaterialTheme.typography.bodySmall, modifier = Modifier.horizontalScroll(rememberScrollState())) }
    }
}
