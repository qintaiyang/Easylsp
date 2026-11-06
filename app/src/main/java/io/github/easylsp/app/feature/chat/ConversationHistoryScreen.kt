package io.github.easylsp.app.feature.chat

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.easylsp.app.MainViewModel
import io.github.easylsp.app.data.ConversationEntity
import io.github.easylsp.app.ui.components.EmptyState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalFoundationApi::class)
@Composable fun ConversationHistoryScreen(vm: MainViewModel, globalOnly: Boolean = false, newChat: (() -> Unit)? = null, open: (String) -> Unit) {
    val rows by remember { vm.graph.store.workflows.observeAllConversations() }.collectAsStateWithLifecycle(emptyList())
    var archived by rememberSaveable { mutableStateOf(false) }
    var sort by rememberSaveable { mutableStateOf("recent") }
    var query by rememberSaveable { mutableStateOf("") }
    val busy by vm.busy.collectAsStateWithLifecycle()
    var selected by remember { mutableStateOf<ConversationEntity?>(null) }
    var deleting by remember { mutableStateOf<ConversationEntity?>(null) }
    var name by remember { mutableStateOf("") }
    val matching = rows.filter { (!globalOnly || it.projectId == null) && it.parentConversationId == null && it.task != "__memory_consolidation__" && it.archived == archived && (it.title + it.model).contains(query, true) }
        .let { if (sort == "name") it.sortedBy { row -> row.title.lowercase() } else it }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        OutlinedTextField(query, { query = it }, label = { Text("搜索会话标题或模型") }, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(archived, { archived = !archived }, label = { Text("已归档") })
            TextButton(onClick = { sort = if (sort == "recent") "name" else "recent" }) { Text(if (sort == "recent") "最近更新" else "名称排序") }
            if (newChat != null) Button(enabled = !busy, onClick = newChat) { Text("新建会话") }
        }
        if (matching.isEmpty()) EmptyState(if (archived) "没有归档会话" else "还没有会话", "独立聊天无需选择应用或导入 APK。")
        LazyColumn {
            items(matching, key = { it.id }) { row ->
                ListItem(headlineContent = { Text(row.title) }, supportingContent = { Text("${if (row.projectId == null) "聊天会话" else "项目会话"} · ${row.model}") },
                    modifier = Modifier.combinedClickable(onClick = { open(row.id) }, onLongClick = { selected = row; name = row.title }),
                    trailingContent = { TextButton(onClick = { selected = row; name = row.title }) { Text("管理") } })
            }
        }
    }
    selected?.let { row -> AlertDialog(onDismissRequest = { selected = null }, title = { Text("管理会话") }, text = { Column {
        OutlinedTextField(name, { name = it }, label = { Text("名称") })
        TextButton(enabled = !busy, onClick = { vm.action { withContext(Dispatchers.IO) { vm.graph.store.workflows.archiveConversation(row.id, !row.archived) }; selected = null; "已更新归档状态，历史和运行任务保留" } }) { Text(if (row.archived) "取消归档" else "归档") }
        TextButton(onClick = { selected = null; deleting = row }) { Text("删除…") }
    } }, confirmButton = { TextButton(enabled = !busy && name.isNotBlank(), onClick = { vm.action { withContext(Dispatchers.IO) { vm.graph.store.workflows.renameConversation(row.id, name.trim(), System.currentTimeMillis()) }; selected = null; "名称已保存" } }) { Text("保存") } }, dismissButton = { TextButton(onClick = { selected = null }) { Text("关闭") } }) }
    deleting?.let { row -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text("删除“${row.title}”？") }, text = { Text("将停止此会话及其执行子任务，删除消息、工具记录、草稿、计划和独占附件。其他会话与共享项目工作目录保留。") }, confirmButton = { TextButton(enabled = !busy, onClick = { vm.action { vm.graph.localManagement.deleteConversation(row.id); deleting = null; "会话已删除" } }) { Text("停止并删除") } }, dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } }) }
}
