package io.github.easylsp.app.feature.inspect

import android.os.Bundle
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.easylsp.app.MainViewModel
import io.github.easylsp.app.ui.components.*
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable fun InspectScreen(vm: MainViewModel, sessionId: String?, openOutput: (String, String, String) -> Unit = { _, _, _ -> }) {
    var query by rememberSaveable { mutableStateOf("") }
    var treeJson by rememberSaveable(sessionId) { mutableStateOf<String?>(null) }
    var readingJson by rememberSaveable(sessionId) { mutableStateOf<String?>(null) }
    var selectedJson by rememberSaveable(sessionId) { mutableStateOf<String?>(null) }
    var collapsed by rememberSaveable(sessionId) { mutableStateOf(listOf<String>()) }
    var tab by rememberSaveable { mutableStateOf("tree") }
    fun call(operation: String, args: Bundle = Bundle()) { vm.action {
        val output = vm.graph.bridge.commandSession(requireNotNull(sessionId), operation, arguments = args)
        when (operation) { "viewTree" -> treeJson = output; "memory", "performanceStop" -> readingJson = output }
        if (operation == "performanceStart") "已开始采样；在目标页面操作后停止读取" else "检视已更新"
    } }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { FilterChip(tab == "tree", { tab = "tree" }, label = { Text("View 树") }); FilterChip(tab == "performance", { tab = "performance" }, label = { Text("性能读数") }) }
        if (sessionId == null) { EmptyState("目标进程离线", "启动目标并重新连接后，才能读取 View 树与性能数据。"); return }
        if (tab == "tree") {
            OutlinedTextField(query, { query = it }, label = { Text("搜索文案、类名或 View ID") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                TextButton(onClick = { call("inspectOverlay", Bundle().apply { putBoolean("enabled", true) }) }) { Text("目标内圈选") }
                TextButton(onClick = { call("inspectOverlay", Bundle().apply { putBoolean("enabled", false) }) }) { Text("关闭圈选") }
                TextButton(onClick = { collapsed = emptyList(); call("viewTree", Bundle().apply { putString("query", query) }) }) { Text("读取 / 搜索") }
                TextButton(onClick = { call("creationStacks", Bundle().apply { putBoolean("enabled", true) }) }) { Text("开启创建堆栈") }
                TextButton(onClick = { call("creationStacks", Bundle().apply { putBoolean("enabled", false) }) }) { Text("关闭堆栈") }
            }
            Text("创建堆栈仅覆盖开启后创建的 View。树按页读取；当前结果不含 Compose 语义树与 WebView DOM。", style = MaterialTheme.typography.labelSmall)
            val result = treeJson?.let { JSONObject(it) }
            val array = result?.optJSONArray("items")
            if (array != null) {
                Text("${result.optString("processName")} · ${array.length()} / ${result.optInt("total")} 个节点", style = MaterialTheme.typography.labelSmall)
                val rows = (0 until array.length()).map { array.getJSONObject(it) }
                LazyColumn(Modifier.weight(1f)) {
                    items(rows.filter { row -> collapsed.none { row.getString("path").startsWith("$it/") } }, key = { it.getString("path") }) { row ->
                        val path = row.getString("path")
                        val depth = path.count { it == '/' }
                        val hasChildren = rows.any { it.getString("path").startsWith("$path/") }
                        Row(Modifier.padding(start = (depth.coerceAtMost(6) * 12).dp)) {
                            if (hasChildren && query.isEmpty()) TextButton(onClick = { collapsed = if (path in collapsed) collapsed - path else collapsed + path }, contentPadding = PaddingValues(4.dp), modifier = Modifier.width(32.dp)) { Text(if (path in collapsed) "+" else "−") }
                            ListItem(headlineContent = { Text(row.getString("class").substringAfterLast('.'), style = MaterialTheme.typography.bodyMedium) }, supportingContent = { Text(row.optString("text").ifEmpty { row.optString("id", path) }, style = MaterialTheme.typography.bodySmall) }, modifier = Modifier.clickable { selectedJson = row.toString() })
                        }
                    }
                }
                Row { TextButton(enabled = result.optInt("offset") > 0, onClick = { call("viewTree", Bundle().apply { putString("query", query) }) }) { Text("首页") }; TextButton(enabled = !result.isNull("nextOffset"), onClick = { call("viewTree", Bundle().apply { putString("query", query); putInt("offset", result.getInt("nextOffset")) }) }) { Text("下一页") } }
            } else EmptyState("读取目标界面", "在目标打开待检视的页面，再点击读取。")
        } else {
            LivePerformance(vm, sessionId)
            Row { TextButton(onClick = { call("performanceStart") }) { Text("开始采样") }; TextButton(onClick = { call("performanceStop") }) { Text("停止并读取") }; TextButton(onClick = { call("memory") }) { Text("内存") } }
            val reading = readingJson?.let { JSONObject(it) }
            if (reading != null) {
                Text("${reading.optString("processName")} · PID ${reading.optInt("pid")}\n${SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(Date(reading.getLong("timestamp")))}", style = MaterialTheme.typography.bodySmall)
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    listOf("fps" to "FPS · Choreographer 回调频率", "cpuPercentOneCore" to "CPU % · 单核为 100%", "totalPssKb" to "PSS · kB", "elapsedMs" to "采样时长 · ms").forEach { (key, title) -> AppCard { Text(title, style = MaterialTheme.typography.labelMedium); Text(if (reading.has(key)) String.format(Locale.ROOT, "%.1f", reading.getDouble(key)) else "未提供", style = MaterialTheme.typography.headlineMedium) } }
                    TextButton(onClick = { openOutput("性能原始读数", reading.toString(2), "json") }) { Text("来源与完整数据") }
                }
            } else EmptyState("尚未采样", "开始采样后在目标页面操作，再停止并读取。")
        }
    }
    selectedJson?.let { value -> val row = JSONObject(value)
        AlertDialog(onDismissRequest = { selectedJson = null }, title = { Text(row.getString("class").substringAfterLast('.')) }, text = { Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
            row.keys().asSequence().filter { it != "creationStack" }.forEach { key -> Text("$key: ${row.get(key)}", style = MaterialTheme.typography.bodySmall) }
            if (row.has("creationStack")) TextButton(onClick = { openOutput("View 创建堆栈", row.getString("creationStack"), "text") }) { Text("查看创建堆栈") }
        } }, confirmButton = { TextButton(onClick = { selectedJson = null }) { Text("关闭") } })
    }
}
