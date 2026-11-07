package io.github.easylsp.app.feature.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.easylsp.app.data.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun ConversationModels(conversation: ConversationEntity, providers: List<ProviderEntity>, busy: Boolean, dismiss: () -> Unit, select: (ProviderEntity) -> Unit, manage: () -> Unit, effort: String = "", setEffort: (String) -> Unit = {}) {
    // Choice rows carry model IDs and their actual parent connection.
    val groups = providers.groupBy { it.connectionId }
    var selectedProvider by remember(groups.keys) { mutableStateOf(groups.keys.singleOrNull()) }
    var query by remember { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = dismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
            Text(if (selectedProvider == null) "选择供应商" else "选择模型", style = MaterialTheme.typography.titleMedium)
            Text(if (busy) "当前任务继续使用原模型，所选模型从下一轮生效。" else "保留当前会话历史。", style = MaterialTheme.typography.bodySmall)
            if (groups.size > 1 && selectedProvider != null) TextButton(onClick = { selectedProvider = null; query = "" }) { Text("← 返回供应商") }
            OutlinedTextField(query, { query = it }, placeholder = { Text("搜索") }, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), singleLine = true)
            LazyColumn(Modifier.heightIn(max = 360.dp)) {
                if (selectedProvider == null) items(groups.entries.filter { it.value.first().name.contains(query, true) }, key = { it.key }) { group ->
                    ListItem(headlineContent = { Text(group.value.first().name) }, supportingContent = { Text("${group.value.size} 个模型 · ${group.value.first().baseUrl}") }, modifier = Modifier.clickable { selectedProvider = group.key; query = "" })
                } else items(groups[selectedProvider].orEmpty().filter { (it.model.contains(query, true) || it.modelDisplayName.contains(query, true)) }, key = { it.id }) { provider ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable { select(provider) }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(provider.id == conversation.modelConfigId.ifEmpty { conversation.providerId }, onClick = null)
                        Column { Text(provider.modelDisplayName, style = MaterialTheme.typography.bodyMedium); Text(provider.model, style = MaterialTheme.typography.labelSmall) }
                    }
                }
            }
            val current = providers.firstOrNull { it.id == conversation.modelConfigId.ifEmpty { conversation.providerId } }
            val levels = current?.let { org.json.JSONObject(it.options).optJSONArray("reasoningEfforts") } ?: org.json.JSONArray()
            Text("推理强度（下一轮生效）")
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                FilterChip(effort.isEmpty(), { setEffort("") }, label = { Text("自动") })
                for (i in 0 until levels.length()) { val level = levels.getString(i); FilterChip(effort == level, { setEffort(level) }, label = { Text(level) }) }
            }
            TextButton(onClick = manage) { Text("管理供应商与模型") }
            Spacer(Modifier.height(16.dp))
        }
    }
}
