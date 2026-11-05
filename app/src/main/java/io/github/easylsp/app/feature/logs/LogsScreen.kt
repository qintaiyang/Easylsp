package io.github.easylsp.app.feature.logs

import io.github.easylsp.app.ui.icons.AppIcons
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.easylsp.app.MainViewModel
import io.github.easylsp.app.data.ProjectEntity
import io.github.easylsp.app.ui.components.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable fun LogsScreen(vm: MainViewModel, project: ProjectEntity, initialSession: String?, initialPlugin: String?, openOutput: (String, String) -> Unit, edit: (String) -> Unit) {
    val lines by vm.logs.collectAsStateWithLifecycle()
    val plugins by vm.plugins.collectAsStateWithLifecycle()
    val sessions by vm.sessions.collectAsStateWithLifecycle()
    var nativeLines by remember { mutableStateOf(emptyList<JSONObject>()) }
    LaunchedEffect(project.id) { nativeLines = withContext(Dispatchers.IO) { vm.graph.nativeInstances.events(project.id) } }
    var query by rememberSaveable { mutableStateOf("") }
    var level by rememberSaveable { mutableStateOf("全部") }
    var session by rememberSaveable { mutableStateOf(initialSession) }
    var plugin by rememberSaveable { mutableStateOf(initialPlugin) }
    var confirmClear by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val filter: (JSONObject) -> Boolean = { item -> item.optString("packageName") == project.packageName && (item.optString("runtime") == "NATIVE" || session == null || item.optString("sessionId") == session) && (plugin == null || item.optString("pluginId") == plugin) && (level == "全部" || item.optString("level").equals(level, true)) && item.toString().contains(query, true) }
    val nativeDisplay = nativeLines.map { row -> JSONObject(row.toString()).put("runtime", "NATIVE").put("packageName", project.packageName)
        .put("processName", "Native PID ${row.getInt("pid")} · ${row.getString("instanceId")}")
        .put("timestamp", row.optLong("timestamp")).put("level", if (row.getInt("code") < 0 || row.getString("phase").endsWith("failed")) "ERROR" else "INFO")
        .put("message", "${row.getString("phase")} · Hook ${row.getInt("hookId")} · ${row.getString("detail")}\n${row.getString("buildId")}") }
    val matching = (lines.map { JSONObject(it) } + nativeDisplay).filter(filter).sortedBy { it.optLong("timestamp") }.takeLast(500)
    suspend fun completeText(): String = withContext(Dispatchers.IO) {
        listOf(vm.graph.logs.text(filter), nativeDisplay.filter(filter).joinToString("\n")).filter { it.isNotBlank() }.joinToString("\n")
    }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/x-ndjson")) { uri ->
        if (uri != null) vm.action { val text = completeText(); withContext(Dispatchers.IO) { requireNotNull(vm.graph.contentResolver.openOutputStream(uri)).bufferedWriter().use { it.write(text) } }; "已导出当前筛选范围的完整日志" }
    }
    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(query, { query = it }, label = { Text("搜索日志文本") }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), singleLine = true)
        Row(Modifier.padding(horizontal = 16.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("全部", "INFO", "WARN", "ERROR", "DEBUG").forEach { name -> FilterChip(level == name, { level = name }, label = { Text(if (name == "全部") "全部级别" else name) }) }
        }
        Row(Modifier.padding(horizontal = 16.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(session == null, { session = null }, label = { Text("全部进程") })
            (sessions.values.filter { it.packageName == project.packageName }.map { it.id to it.processName } + lines.map { JSONObject(it) }.filter { it.optString("packageName") == project.packageName && it.optString("sessionId").isNotEmpty() }.map { it.optString("sessionId") to it.optString("processName") }).distinctBy { it.first }.forEach { (id, name) ->
                FilterChip(session == id, { session = id }, label = { Text(name.removePrefix(project.packageName).ifEmpty { "主进程" } + " · " + id.take(4)) })
            }
        }
        Row(Modifier.padding(horizontal = 16.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(plugin == null, { plugin = null }, label = { Text("全部插件") })
            plugins.filter { it.projectId == project.id }.forEach { item -> FilterChip(plugin == item.id, { plugin = item.id }, label = { Text(item.name) }) }
        }
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            TextButton(onClick = { vm.action { withContext(Dispatchers.IO) { vm.graph.nativeInstances.collect(project.id); nativeLines = vm.graph.nativeInstances.events(project.id) }; "已读取 Native 事件；历史 Java 会话不会屏蔽这些事件" } }) { Text("读取 Native 日志") }
            TextButton(onClick = { confirmClear = true }) { Text("清空 Java 日志") }
            TextButton(onClick = { export.launch("${project.packageName}-logs.jsonl") }) { Text("导出当前范围") }
            TextButton(onClick = { vm.action {
                val text = completeText()
                context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "分享当前范围日志")); "已打开分享"
            } }) { Text("分享") }
        }
        Text("列表显示最近 500 条中的匹配项（${matching.size}）；Native 事件独立于 Java 会话；无事件表示尚无执行证据。", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 16.dp))
        if (matching.isEmpty()) EmptyState("暂无日志", "加载插件并在目标中触发方法，或调整筛选范围。")
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            itemsIndexed(matching) { _, item ->
                var expanded by rememberSaveable(item.toString()) { mutableStateOf(false) }
                val message = item.getString("message")
                AppCard {
                    Text("${SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT).format(Date(item.getLong("timestamp")))} · ${item.optString("level")}", style = MaterialTheme.typography.labelMedium, color = if (item.optString("level").equals("ERROR", true)) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                    Text(item.optString("processName"), style = MaterialTheme.typography.labelSmall)
                    Text(if (expanded) message else message.lineSequence().take(3).joinToString("\n"), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    Row {
                        if (message.lines().size > 3) TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "折叠堆栈" else "展开堆栈") }
                        IconButton(onClick = { clipboard.setText(AnnotatedString(message)) }) { Icon(AppIcons.ContentCopy, "复制日志") }
                        plugins.firstOrNull { it.projectId == project.id && it.id == item.optString("pluginId") }?.let { plugin -> TextButton(onClick = { edit(plugin.id) }) { Text("打开插件") } }
                        TextButton(onClick = { openOutput("日志详情 · ${project.name}", item.toString(2)) }) { Text("详情") }
                    }
                }
            }
        }
    }
    if (confirmClear) AlertDialog(onDismissRequest = { confirmClear = false }, title = { Text("清空当前筛选范围？") }, text = { Text("仅删除 ${project.name} 中符合当前进程、插件、级别和搜索条件的日志。") }, confirmButton = { TextButton(onClick = { confirmClear = false; vm.action { withContext(Dispatchers.IO) { vm.graph.logs.clear(filter) }; "当前范围日志已清空" } }) { Text("清空") } }, dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("取消") } })
}
