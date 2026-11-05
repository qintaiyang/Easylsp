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

@Composable fun ProjectsScreen(vm: MainViewModel, open: (String) -> Unit, add: () -> Unit, importApk: () -> Unit) {
    val projects by vm.projects.collectAsStateWithLifecycle()
    val sort by vm.graph.settings.projectSort.collectAsStateWithLifecycle("recent")
    val filter by vm.graph.settings.projectFilter.collectAsStateWithLifecycle("all")
    val scope = rememberCoroutineScope()
    var query by rememberSaveable { mutableStateOf("") }
    var group by rememberSaveable { mutableStateOf("") }
    val matching = projects.filter { (filter == "all" || it.kind == filter) && (group.isEmpty() || it.groupName == group) && (it.name.contains(query, true) || it.packageName.contains(query, true)) }
        .let { if (sort == "recent") it.sortedByDescending { p -> p.lastOpenedAt } else it.sortedBy { p -> p.name.lowercase() } }
    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(query, { query = it }, label = { Text("搜索项目名称或包名") }, leadingIcon = { Icon(AppIcons.Search, null) }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp))
        Row(Modifier.padding(horizontal = 16.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("all" to "全部", "INSTALLED" to "设备应用", "IMPORTED" to "导入 APK").forEach { (value, label) -> FilterChip(filter == value, { scope.launchSetting { vm.graph.settings.projectFilter(value) } }, label = { Text(label) }) }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = { scope.launchSetting { vm.graph.settings.projectSort(if (sort == "recent") "name" else "recent") } }) { Icon(AppIcons.Sort, null); Text(if (sort == "recent") "最近访问" else "名称排序") }
            Row { TextButton(onClick = importApk) { Text("导入 APK") }; TextButton(onClick = add) { Icon(AppIcons.Add, null); Text("添加") } }
        }
        if (projects.any { it.groupName.isNotEmpty() }) Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            (listOf("") + projects.map { it.groupName }.filter { it.isNotEmpty() }.distinct()).forEach { name -> FilterChip(group == name, { group = name }, label = { Text(name.ifEmpty { "全部分组" }) }) }
        }
        if (matching.isEmpty()) EmptyState("没有匹配的项目", "添加设备应用或导入 APK 后，即可在这里再次打开。", "添加应用", add)
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(matching, key = { it.id }) { item ->
                Card(onClick = { open(item.id) }) {
                    ListItem(leadingContent = { ProjectIcon(item, vm) }, headlineContent = { Text(item.name) }, supportingContent = { Text("${item.packageName}\n${item.version} · ${if (item.kind == "IMPORTED") "导入 APK · 样本 r${item.sampleRevision}" else "设备应用"}") }, trailingContent = { Icon(AppIcons.ChevronRight, null) })
                }
            }
        }
    }
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
