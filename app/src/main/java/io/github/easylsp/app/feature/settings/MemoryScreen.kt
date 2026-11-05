package io.github.easylsp.app.feature.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.easylsp.app.EasylspApp
import io.github.easylsp.app.data.MemoryEntity
import kotlinx.coroutines.*
import java.util.UUID

@Composable fun MemoryScreen(graph: EasylspApp, projectId: String? = null) {
    val rows by remember(projectId) { graph.store.workflows.observeMemories(projectId) }.collectAsStateWithLifecycle(emptyList())
    var enabled by remember { mutableStateOf(graph.memories.enabled()) }
    var automatic by remember { mutableStateOf(graph.memories.preferences.getBoolean("automatic", false)) }
    var query by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<MemoryEntity?>(null) }
    var status by remember { mutableStateOf(graph.memories.preferences.getString("lastError", "").orEmpty()) }
    val scope = rememberCoroutineScope()
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri -> if (uri != null) scope.launch(Dispatchers.IO) {
        graph.contentResolver.openOutputStream(uri)!!.bufferedWriter().use { it.write(graph.memories.export(projectId)) }
    } }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) scope.launch(Dispatchers.IO) {
        try { val text = graph.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }; graph.memories.import(projectId, text); status = "已导入为记忆" } catch (e: Exception) { status = e.message.orEmpty() }
    } }
    Column(Modifier.heightIn(max = 600.dp).verticalScroll(rememberScrollState()).padding(12.dp)) {
        ListItem(headlineContent = { Text("使用记忆") }, supportingContent = { Text("允许模型按需检索，正文不自动加入系统提示词。") }, trailingContent = { Switch(enabled, { enabled = it; graph.memories.preferences.edit().putBoolean("enabled", it).apply() }) })
        ListItem(headlineContent = { Text("自动整理记忆") }, supportingContent = { Text("任务完成后独立整理可复用结论；使用当前模型，计入独立任务用量。") }, trailingContent = { Switch(automatic, { automatic = it; graph.memories.preferences.edit().putBoolean("automatic", it).apply() }) })
        OutlinedTextField(query, { query = it }, label = { Text("搜索记忆与规则") }, modifier = Modifier.fillMaxWidth())
        Row {
            TextButton(onClick = { editing = MemoryEntity(UUID.randomUUID().toString(), projectId, "", "") }) { Text("新增记忆") }
            TextButton(onClick = { editing = MemoryEntity(UUID.randomUUID().toString(), projectId, "", "", rule = true) }) { Text("新增常驻规则") }
        }
        Row { TextButton(onClick = { exporter.launch("easylsp-memory.md") }) { Text("导出 Markdown") }; TextButton(onClick = { importer.launch(arrayOf("text/*")) }) { Text("导入 Markdown") } }
        rows.filter { (it.title + it.text).contains(query, true) }.forEach { row ->
            ListItem(headlineContent = { Text(row.title) }, supportingContent = { Text("${if (row.projectId == null) "全局" else "项目"} · ${if (row.rule) "常驻规则" else "记忆"}\n${row.text.take(180)}") }, modifier = Modifier.clickable { editing = row })
        }
        if (status.isNotBlank()) Text(status)
    }
    editing?.let { row ->
        var title by remember(row.id) { mutableStateOf(row.title) }
        var text by remember(row.id) { mutableStateOf(row.text) }
        var global by remember(row.id) { mutableStateOf(row.projectId == null) }
        EditorDialog(if (row.rule) "常驻规则" else "记忆", { editing = null }, { scope.launch {
            val updated = row.copy(projectId = if (global) null else projectId, title = title, text = text)
            withContext(Dispatchers.IO) { graph.memories.edit(updated) }; editing = null
        } }) {
            Field("标题", title) { title = it }
            if (projectId != null) ListItem(headlineContent = { Text("全局适用") }, trailingContent = { Switch(global, { global = it }) })
            OutlinedTextField(text, { text = it }, label = { Text(if (row.rule) "始终适用的明确指令" else "正文") }, modifier = Modifier.fillMaxWidth().height(240.dp))
            TextButton(onClick = { scope.launch { withContext(Dispatchers.IO) { graph.memories.removeFromSettings(row.id) }; editing = null } }) { Text("删除") }
        }
    }
}
