package io.github.easylsp.app.feature.settings

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.easylsp.app.MainViewModel
import io.github.easylsp.app.platform.tools.*
import kotlinx.coroutines.*
import org.json.*

@Composable fun ToolDirectory(vm: MainViewModel) {
    val graph = vm.graph
    val projects by vm.projects.collectAsStateWithLifecycle()
    val conversations by remember { graph.store.workflows.observeConversations() }.collectAsStateWithLifecycle(emptyList())
    var conversationId by remember { mutableStateOf<String?>(null) }
    val hosts by graph.bridge.sessions.collectAsStateWithLifecycle()
    var projectId by remember { mutableStateOf<String?>(null) }
    var sessionId by remember { mutableStateOf<String?>(null) }
    var tools by remember { mutableStateOf(emptyList<ToolDefinition>()) }
    var search by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<ToolDefinition?>(null) }
    var output by remember { mutableStateOf("") }
    val project = projects.firstOrNull { it.id == projectId }
    val currentTools by rememberUpdatedState(tools)
    DisposableEffect(Unit) { onDispose { currentTools.forEach { it.close() } } }
    Text("常用工具直接可用，其他工具由 AI 按需搜索和选择；这里可以检查开关或手动试运行。", style = MaterialTheme.typography.bodySmall)
    Row(Modifier.horizontalScroll(rememberScrollState())) { FilterChip(projectId == null, { projectId = null }, label = { Text("通用工具") }); projects.forEach { p -> FilterChip(p.id == project?.id, { projectId = p.id }, label = { Text(p.name) }) } }
    Row(Modifier.horizontalScroll(rememberScrollState())) { hosts.values.filter { it.packageName == project?.packageName }.forEach { host -> FilterChip(sessionId == host.id, { sessionId = host.id }, label = { Text(host.processName) }) } }
    Row(Modifier.horizontalScroll(rememberScrollState())) { conversations.filter { it.projectId == projectId }.forEach { row -> FilterChip(conversationId == row.id, { conversationId = row.id }, label = { Text("试运行：${row.title}") }) } }
    TextButton(onClick = { vm.action {
        tools.forEach { it.close() }
        tools = withContext(Dispatchers.IO) { graph.tools.build(RunBinding(project, project?.sampleRevision ?: 0, sessionId, conversations.firstOrNull { it.id == conversationId && it.projectId == projectId }?.id ?: "00000000-0000-4000-8000-000000000001"), true).filter { it.name != "ask_user" } }
        "已读取 ${tools.size} 个工具"
    } }) { Text("刷新工具目录") }
    Field("搜索工具", search) { search = it }
    Row { TextButton(onClick = { tools.forEach { graph.tools.enable(it.name, true) }; tools = tools.toList() }) { Text("全部启用") }; TextButton(onClick = { tools.forEach { graph.tools.enable(it.name, false) }; tools = tools.toList() }) { Text("全部停用") } }
    tools.filter { (it.name + it.description).contains(search, true) }.groupBy { it.source }.forEach { (source, rows) ->
        Text(source, style = MaterialTheme.typography.titleMedium)
        rows.forEach { tool ->
            var enabled by remember(tool.name, tools) { mutableStateOf(graph.tools.enabled(tool.name)) }
            ListItem(headlineContent = { Text(tool.name) }, supportingContent = { Text(tool.description, maxLines = 2) }, trailingContent = { Switch(enabled, { enabled = it; graph.tools.enable(tool.name, it) }) }, modifier = Modifier.clickable { selected = tool })
        }
    }
    if (output.isNotEmpty()) androidx.compose.foundation.text.selection.SelectionContainer { Text(output, style = MaterialTheme.typography.bodySmall) }
    selected?.let { tool ->
        var values by remember(tool.name) { mutableStateOf(mapOf<String,String>()) }
        var result by remember(tool.name) { mutableStateOf("") }
        val properties = tool.schema.optJSONObject("properties") ?: JSONObject()
        AlertDialog(onDismissRequest = { selected = null }, title = { Text(tool.name) }, text = {
            Column(Modifier.heightIn(max = 450.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(tool.description)
                properties.keys().forEach { key -> val spec = properties.getJSONObject(key); Field("$key · ${spec.optString("type")}", values[key].orEmpty()) { values = values + (key to it) } }
                Text("原始 schema\n${tool.schema.toString(2)}", style = MaterialTheme.typography.labelSmall)
                if (tool.writes) Text("试运行将按上述参数写入或执行。", color = MaterialTheme.colorScheme.primary)
                if (result.isNotEmpty()) androidx.compose.foundation.text.selection.SelectionContainer { Text(result) }
            }
        }, confirmButton = { TextButton(onClick = { vm.action {
            val args = JSONObject()
            values.filterValues { it.isNotEmpty() }.forEach { (key, value) -> args.put(key, when (properties.getJSONObject(key).optString("type")) { "integer" -> value.toLong(); "number" -> value.toDouble(); "boolean" -> value.toBooleanStrict(); "object" -> JSONObject(value); "array" -> JSONArray(value); else -> value }) }
            ToolArguments.validate(tool.schema, args)
            result = withContext(Dispatchers.IO) { tool.invoke(args).toString(2) }; output = result; "工具执行完成"
        } }) { Text("试运行") } }, dismissButton = { TextButton(onClick = { selected = null }) { Text("关闭") } })
    }
}
