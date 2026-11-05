package io.github.easylsp.app.feature.settings

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.easylsp.app.MainViewModel
import kotlinx.coroutines.*
import org.json.JSONObject

@Composable fun StorageScreen(vm: MainViewModel) {
    val graph = vm.graph
    val projects by vm.projects.collectAsStateWithLifecycle()
    var projectId by remember { mutableStateOf<String?>(null) }
    var summary by remember { mutableStateOf(JSONObject()) }
    var confirm by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf("") }
    val project = projects.firstOrNull { it.id == projectId }
    LaunchedEffect(result) { summary = withContext(Dispatchers.IO) { graph.storage.summary() } }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("存储用量", style = MaterialTheme.typography.titleMedium)
        listOf("diagnostics" to "启动诊断", "samples" to "样本", "editorDrafts" to "编辑草稿", "viewerCache" to "阅读缓存", "executions" to "执行记录", "temporaryCache" to "临时缓存").forEach { (key, label) -> Text("$label · %.2f MiB".format(summary.optLong(key) / 1048576.0)) }
        Text("会话 ${summary.optInt("chatConversations")} · Linux ${if (summary.optBoolean("linuxInstalled")) "已安装" else "未安装"}")
        Row(Modifier.horizontalScroll(rememberScrollState())) { FilterChip(projectId == null, { projectId = null }, label = { Text("快速对话") }); projects.forEach { p -> FilterChip(project?.id == p.id, { projectId = p.id }, label = { Text(p.name) }) } }
        listOf("diagnostics" to "清理已结束启动诊断", "chat" to "清理此项目聊天", "samples" to "清理未引用的旧样本", "drafts" to "清理此项目草稿", "executions" to "清理已结束执行记录", "viewers" to "清理全部阅读缓存", "linux" to "卸载 Linux 环境").forEach { (id, label) -> TextButton(enabled = project != null || id in setOf("chat", "executions", "viewers", "linux"), onClick = { confirm = id }) { Text(label) } }
        Text("当前样本、仍被会话或诊断引用的样本和运行中的执行会保留。清理草稿会丢弃未保存编辑。", style = MaterialTheme.typography.bodySmall)
        if (result.isNotEmpty()) Text(result)
    }
    confirm?.let { category -> AlertDialog(onDismissRequest = { confirm = null }, title = { Text("确认清理所选范围？") }, text = { Text("${project?.name} · $category。此操作会删除对应记录或文件。") }, confirmButton = { TextButton(onClick = { vm.action {
        result = withContext(Dispatchers.IO) { if (category == "linux") { graph.linux.remove(); "Linux 已卸载" } else if (project == null && category in setOf("chat", "executions")) graph.storage.clearGlobal(category).toString(2) else graph.storage.clear(project?.id.orEmpty(), category).toString(2) }; confirm = null; "清理完成"
    } }) { Text("清理") } }, dismissButton = { TextButton(onClick = { confirm = null }) { Text("取消") } }) }
}
