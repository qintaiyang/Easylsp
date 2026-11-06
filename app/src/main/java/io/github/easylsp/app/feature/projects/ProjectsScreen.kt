package io.github.easylsp.app.feature.projects

import io.github.easylsp.app.ui.icons.AppIcons
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.easylsp.app.MainViewModel
import io.github.easylsp.app.ui.components.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalFoundationApi::class)
@Composable fun ProjectsScreen(vm: MainViewModel, open: (String) -> Unit, add: () -> Unit, importApk: () -> Unit, openConversation: (String) -> Unit, newChat: () -> Unit) {
    val projects by vm.projects.collectAsStateWithLifecycle()
    val sort by vm.graph.settings.projectSort.collectAsStateWithLifecycle("recent")
    val filter by vm.graph.settings.projectFilter.collectAsStateWithLifecycle("all")
    val scope = rememberCoroutineScope()
    var query by rememberSaveable { mutableStateOf("") }
    var group by rememberSaveable { mutableStateOf("") }
    var archived by rememberSaveable { mutableStateOf(false) }
    val busy by vm.busy.collectAsStateWithLifecycle()
    var selected by remember { mutableStateOf<io.github.easylsp.app.data.ProjectEntity?>(null) }
    var deleting by remember { mutableStateOf<io.github.easylsp.app.data.ProjectEntity?>(null) }
    var name by remember { mutableStateOf("") }
    val matching = projects.filter { it.archived == archived && (filter == "all" || it.kind == filter) && (group.isEmpty() || it.groupName == group) && (it.name.contains(query, true) || it.packageName.contains(query, true)) }
        .let { if (sort == "recent") it.sortedByDescending { p -> p.lastOpenedAt } else it.sortedBy { p -> p.name.lowercase() } }
    Column(Modifier.fillMaxSize()) {
        if (filter != "CHAT") OutlinedTextField(query, { query = it }, label = { Text("搜索项目名称或包名") }, leadingIcon = { Icon(AppIcons.Search, null) }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp))
        Row(Modifier.padding(horizontal = 16.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("all" to "全部", "INSTALLED" to "设备应用", "IMPORTED" to "导入 APK（静态分析）", "CHAT" to "聊天会话").forEach { (value, label) -> FilterChip(filter == value, { scope.launchSetting { vm.graph.settings.projectFilter(value) } }, label = { Text(label) }) }
        }
        if (filter == "CHAT") {
            io.github.easylsp.app.feature.chat.ConversationHistoryScreen(vm, globalOnly = true, newChat = newChat, open = openConversation)
        } else {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = { scope.launchSetting { vm.graph.settings.projectSort(if (sort == "recent") "name" else "recent") } }) { Text(if (sort == "recent") "最近访问" else "名称排序") }
                FilterChip(archived, { archived = !archived }, label = { Text("已归档") })
            }
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (filter != "INSTALLED") Button(enabled = !busy, onClick = importApk) { Text("导入 APK（静态分析）") }
                if (filter != "IMPORTED") OutlinedButton(enabled = !busy, onClick = add) { Text("添加应用") }
            }
            if (projects.any { it.groupName.isNotEmpty() }) Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
                (listOf("") + projects.map { it.groupName }.filter { it.isNotEmpty() }.distinct()).forEach { value -> FilterChip(group == value, { group = value }, label = { Text(value.ifEmpty { "全部分组" }) }) }
            }
            if (matching.isEmpty()) EmptyState(if (archived) "没有归档项目" else "没有匹配的项目", "选择 APK 后可直接进行静态分析；导入不会安装应用。")
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(matching, key = { it.id }) { item ->
                    Card(Modifier.combinedClickable(onClick = { open(item.id) }, onLongClick = { selected = item; name = item.name })) {
                        ListItem(leadingContent = { ProjectIcon(item, vm) }, headlineContent = { Text(item.name) }, supportingContent = { Text("${item.packageName}\n${item.version} · 用户 ${item.userId} · ${if (item.kind == "IMPORTED") "静态分析 · 样本 r${item.sampleRevision}" else "设备应用"}") }, trailingContent = { TextButton(onClick = { selected = item; name = item.name }) { Text("管理") } })
                    }
                }
            }
        }
    }
    selected?.let { item -> AlertDialog(onDismissRequest = { selected = null }, title = { Text("管理项目") }, text = { Column {
        OutlinedTextField(name, { name = it }, label = { Text("名称") })
        TextButton(enabled = !busy, onClick = { vm.action { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { vm.graph.store.dao.archiveProject(item.id, !item.archived) }; selected = null; "归档状态已更新，发布与会话保留" } }) { Text(if (item.archived) "取消归档" else "归档") }
        TextButton(onClick = { selected = null; deleting = item }) { Text("删除…") }
    } }, confirmButton = { TextButton(enabled = !busy && name.isNotBlank(), onClick = { vm.action { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { vm.graph.store.dao.updateProject(item.copy(name = name.trim())) }; selected = null; "名称已保存" } }) { Text("保存") } }, dismissButton = { TextButton(onClick = { selected = null }) { Text("关闭") } }) }
    deleting?.let { item -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text("删除“${item.name}”？") }, text = { Text("将停止所属任务、撤销下次启动发布并删除会话、样本与本地开发内容。Native 已加载代码仍需目标退出；正在使用的产物暂存以供清理。不会卸载目标应用。撤销失败时保留项目。") }, confirmButton = { TextButton(enabled = !busy, onClick = { vm.action { vm.graph.localManagement.deleteProject(item.id); deleting = null; "项目已删除；旧 Native 进程需退出" } }) { Text("停止并删除") } }, dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } }) }

}

@Composable fun AddAppScreen(vm: MainViewModel, open: (String) -> Unit) {
    val installed by vm.installed.collectAsStateWithLifecycle()
    val showSystem by vm.showSystem.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var sort by rememberSaveable { mutableStateOf("name") }
    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(query, { query = it }, label = { Text("搜索设备应用") }, leadingIcon = { Icon(AppIcons.Search, null) }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), singleLine = true)
        ListItem(headlineContent = { Text("显示系统应用") }, trailingContent = { Switch(showSystem, { vm.action { vm.graph.settings.showSystem(it); "筛选已保存" } }) }, leadingContent = { IconButton(onClick = { vm.refreshApps() }) { Icon(AppIcons.Refresh, "刷新应用") } })
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp)) { listOf("name" to "名称", "install" to "安装时间", "update" to "更新时间").forEach { (value, title) -> FilterChip(sort == value, { sort = value }, label = { Text(title) }) } }
        LazyColumn(Modifier.weight(1f)) {
            items(installed.let { rows -> when (sort) { "install" -> rows.sortedByDescending { it.installedAt }; "update" -> rows.sortedByDescending { it.updatedAt }; else -> rows } }.filter { (showSystem || !it.system) && (it.name.contains(query, true) || it.packageName.contains(query, true)) }, key = { it.packageName }) { app ->
                ListItem(leadingContent = { AppIcon(app.packageName) }, headlineContent = { Text(app.name) }, supportingContent = { Text("${app.packageName}\n${app.version} · ${if (app.system) "系统应用" else "用户应用"}") }, trailingContent = { Icon(AppIcons.Add, null) }, modifier = Modifier.clickable(enabled = !busy) { vm.select(app) { open(it.id) } })
            }
        }
    }
}
private fun kotlinx.coroutines.CoroutineScope.launchSetting(block: suspend () -> Unit) = launch(block = { block() })
