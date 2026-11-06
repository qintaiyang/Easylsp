package io.github.easylsp.app.feature.projects

import io.github.easylsp.app.ui.icons.AppIcons
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.easylsp.app.MainViewModel
import io.github.easylsp.app.data.ProjectEntity
import io.github.easylsp.app.feature.analysis.AnalysisScreen
import io.github.easylsp.app.feature.chat.ChatScreen
import io.github.easylsp.app.feature.inspect.InspectScreen
import io.github.easylsp.app.feature.p3.WorkspaceScreen
import io.github.easylsp.app.feature.plugins.PluginsScreen
import io.github.easylsp.app.ui.components.*

@Composable fun ProjectScreen(vm: MainViewModel, project: ProjectEntity, edit: (String) -> Unit, importPlugin: () -> Unit, logs: (String?, String?) -> Unit, openOutput: (String, String, String) -> Unit, openChat: (String, String?) -> Unit, template: (String) -> Unit) {
    val sessions by vm.sessions.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val hosts = sessions.values.filter { it.packageName == project.packageName }
    var sessionId by rememberSaveable(project.id) { mutableStateOf<String?>(null) }
    var tab by rememberSaveable(project.id, project.sampleRevision) { mutableStateOf(if (project.kind == "IMPORTED") "analysis" else "chat") }
    var menu by remember { mutableStateOf(false) }
    var advanced by rememberSaveable(project.id, project.sampleRevision) { mutableStateOf(project.kind == "IMPORTED") }
    val host = hosts.firstOrNull { it.id == sessionId }
    LaunchedEffect(hosts.map { it.id }) { if (host == null) sessionId = hosts.firstOrNull { it.processName == project.packageName }?.id ?: hosts.firstOrNull()?.id }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            ProjectIcon(project, vm)
            Column(Modifier.weight(1f)) {
                Text(project.packageName, style = MaterialTheme.typography.bodySmall)
                Text("${project.version} · 样本 r${project.sampleRevision}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            StatusBadge(if (project.kind == "IMPORTED") "静态项目" else if (host != null) "目标在线" else "目标离线", host != null)
        }
        Row(Modifier.padding(horizontal = 12.dp)) {
            TextButton(onClick = { advanced = !advanced; if (!advanced) tab = "chat" }) { Text(if (advanced) "收起高级工具" else "高级工具") }
            TextButton(onClick = { advanced = true; tab = "diagnostics" }) { Text("启动诊断") }
        }
        if (advanced && project.kind == "INSTALLED") {
            Row(Modifier.padding(horizontal = 16.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                hosts.forEach { item -> FilterChip(sessionId == item.id, { sessionId = item.id }, label = { Text(item.processName.removePrefix(project.packageName).ifEmpty { "主进程" }) }) }
            }
            Row(Modifier.padding(horizontal = 8.dp)) {
                TextButton(onClick = { vm.start(project) }, enabled = !busy) { Icon(AppIcons.PlayArrow, null); Text("启动") }
                TextButton(onClick = { vm.reconnect(project) }, enabled = !busy) { Text("重新连接") }
                TextButton(onClick = { logs(sessionId, null) }) { Text("日志") }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(AppIcons.MoreVert, "更多目标操作") }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem(text = { Text("Ping 所选进程") }, enabled = host != null, onClick = { menu = false; vm.action { vm.graph.bridge.commandSession(requireNotNull(host).id, "ping") } })
                        DropdownMenuItem(text = { Text("${host?.processName ?: "暂无在线目标"} · PID ${host?.pid ?: "—"}") }, onClick = { menu = false })
                    }
                }
            }
        }
        val tabs = if (!advanced) listOf("chat" to "AI 助手") else if (project.kind == "INSTALLED") listOf("plugins" to "插件", "analysis" to "分析", "diagnostics" to "诊断", "chat" to "聊天", "inspect" to "检视", "workspace" to "工作台") else listOf("analysis" to "分析", "diagnostics" to "诊断", "chat" to "聊天", "workspace" to "工作台")
        if (advanced) ScrollableTabRow(selectedTabIndex = tabs.indexOfFirst { it.first == tab }.coerceAtLeast(0), edgePadding = 8.dp) {
            tabs.forEach { (id, label) -> Tab(tab == id, { tab = id }, text = { Text(label) }) }
        }
        Spacer(Modifier.height(8.dp))
        val stateHolder = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
        stateHolder.SaveableStateProvider(tab) {
            when (tab) {
                "diagnostics" -> io.github.easylsp.app.feature.diagnostics.DiagnosticsScreen(vm, project, openOutput)
                "analysis" -> AnalysisScreen(project, viewModel(key = "analysis:${project.id}"), openOutput, template)
                "chat" -> ChatScreen(project, sessionId, viewModel(key = "chat:${project.id}")) { openChat(it, sessionId) }
                "inspect" -> InspectScreen(vm, sessionId, openOutput)
                "workspace" -> WorkspaceScreen(vm, project, sessionId, openOutput)
                else -> PluginsScreen(vm, project, edit, importPlugin) { logs(null, it) }
            }
        }
    }
}
