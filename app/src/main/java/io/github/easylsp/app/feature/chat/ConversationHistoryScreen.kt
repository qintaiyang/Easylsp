package io.github.easylsp.app.feature.chat

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.easylsp.app.MainViewModel
import io.github.easylsp.app.data.ConversationEntity
import kotlinx.coroutines.*

@Composable fun ConversationHistoryScreen(vm: MainViewModel, open: (String) -> Unit) {
    val rows by remember { vm.graph.store.workflows.observeAllConversations() }.collectAsStateWithLifecycle(emptyList())
    var archived by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<ConversationEntity?>(null) }
    var name by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        OutlinedTextField(query, { query = it }, label = { Text("搜索会话标题或模型") }, modifier = Modifier.fillMaxWidth())
        FilterChip(archived, { archived = !archived }, label = { Text("显示归档会话") })
        LazyColumn {
            items(rows.filter { (archived || !it.archived) && (it.title + it.model).contains(query, true) }, key = { it.id }) { row ->
                ListItem(headlineContent = { Text(row.title) }, supportingContent = { Text("${if (row.projectId == null) "快速对话" else "项目"} · ${row.model}" + if (row.parentConversationId != null) " · 子代理" else "") },
                    modifier = Modifier.clickable { open(row.id) }, trailingContent = { TextButton(onClick = { selected = row; name = row.title }) { Text("管理") } })
            }
        }
    }
    selected?.let { row -> AlertDialog(onDismissRequest = { selected = null }, title = { Text("管理会话") }, text = { Column {
        OutlinedTextField(name, { name = it }, label = { Text("名称") })
        TextButton(onClick = { vm.action { withContext(Dispatchers.IO) { vm.graph.store.workflows.saveConversation(row.copy(archived = !row.archived)) }; selected = null; "已更新归档状态，历史保留" } }) { Text(if (row.archived) "取消归档" else "归档") }
    } }, confirmButton = { TextButton(onClick = { vm.action { withContext(Dispatchers.IO) { vm.graph.store.workflows.renameConversation(row.id, name, System.currentTimeMillis()) }; selected = null; "名称已保存" } }) { Text("保存") } }, dismissButton = { TextButton(onClick = { selected = null }) { Text("关闭") } }) }
}
