package io.github.easylsp.app.feature.home

import io.github.easylsp.app.ui.icons.AppIcons
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.easylsp.app.MainViewModel
import io.github.easylsp.app.ui.components.*

@Composable fun HomeScreen(vm: MainViewModel, openProject: (String) -> Unit, addApp: () -> Unit, quickChat: () -> Unit, models: () -> Unit, openConversation: (String) -> Unit = {}, history: () -> Unit = {}) {
    val projects by vm.projects.collectAsStateWithLifecycle()
    val sessions by vm.sessions.collectAsStateWithLifecycle()
    val framework by vm.graph.framework.state.collectAsStateWithLifecycle()
    val providers by remember { vm.graph.models.available() }.collectAsStateWithLifecycle(emptyList())
    val conversations by remember { vm.graph.store.workflows.observeConversations() }.collectAsStateWithLifecycle(emptyList())
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            AppCard {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(AppIcons.VerifiedUser, null, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.primary)
                    Column(Modifier.weight(1f)) { Text("用对话开发你的想法", style = MaterialTheme.typography.titleLarge); Text(framework.description, style = MaterialTheme.typography.bodySmall) }
                }
                StatusBadge(if (framework.active) "模块已激活" else "模块未激活", framework.active)
                Text("在框架中启用 Easylsp、勾选目标作用域并重启目标。连接状态在项目内单独显示。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(Triple(projects.size, "项目", addApp), Triple(sessions.size, "在线进程", {}), Triple(providers.size, "模型", models)).forEach { (count, label, click) ->
                    Card(Modifier.weight(1f).clickable(onClick = click), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                        Column(Modifier.padding(16.dp)) { Text("$count", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary); Text(label, style = MaterialTheme.typography.labelLarge) }
                    }
                }
            }
        }
        item { SectionHeader("选择应用，告诉 AI 你想做什么") }
        item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = addApp, modifier = Modifier.weight(1f)) { Icon(AppIcons.Add, null); Spacer(Modifier.width(6.dp)); Text("添加应用") }
            OutlinedButton(onClick = quickChat, modifier = Modifier.weight(1f)) { Icon(AppIcons.FileOpen, null); Spacer(Modifier.width(6.dp)); Text("快速对话") }
        } }
        if (conversations.any { it.projectId == null }) {
            item { SectionHeader("最近快速对话") }
            items(conversations.filter { it.projectId == null }.take(8), key = { "chat:${it.id}" }) { conversation ->
                Card(onClick = { openConversation(conversation.id) }) { ListItem(headlineContent = { Text(conversation.title) }, supportingContent = { Text(conversation.model) }) }
            }
        }
        item { TextButton(onClick = history) { Text("全部会话") } }
        item { SectionHeader("最近项目") }
        if (projects.isEmpty()) item { EmptyState("还没有项目", "选择设备应用或导入 APK。分析、写代码、运行和验证交给 AI。") }
        items(projects.sortedByDescending { it.lastOpenedAt }.take(6), key = { it.id }) { project ->
            Card(onClick = { openProject(project.id) }, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                ListItem(leadingContent = { ProjectIcon(project, vm) }, headlineContent = { Text(project.name) }, supportingContent = { Text("${project.packageName}\n${project.version} · ${if (project.kind == "IMPORTED") "导入 APK" else "设备应用"}") }, trailingContent = { Icon(AppIcons.ChevronRight, null) }, colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow))
            }
        }
    }
}
