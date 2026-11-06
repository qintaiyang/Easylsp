package io.github.easylsp.app.feature.plugins

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
import io.github.easylsp.app.data.*
import io.github.easylsp.app.ui.components.*

fun runtimeLabel(state: String?): String = when (state) {
    "OFFLINE" -> "目标离线"; "RUNNING" -> "运行中"; "LOADING" -> "加载中"; "UNLOADING" -> "卸载中"; "FAILED", "ERROR" -> "运行错误"; "UNLOADED", "STOPPED" -> "已卸载"; else -> "未加载"
}

@Composable fun PluginsScreen(vm: MainViewModel, project: ProjectEntity, edit: (String) -> Unit, importPlugin: () -> Unit, logs: (String?) -> Unit) {
    val plugins by vm.plugins.collectAsStateWithLifecycle()
    val sessions by vm.sessions.collectAsStateWithLifecycle()
    val instances by vm.instances.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    var create by rememberSaveable { mutableStateOf(false) }
    val items = plugins.filter { it.projectId == project.id }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Button(onClick = { create = true }, enabled = !busy) { Icon(AppIcons.Add, null); Text("新建插件") }
            TextButton(onClick = importPlugin, enabled = !busy) { Text("导入 ZIP") }
        }
        if (items.isEmpty()) EmptyState("开始编写第一个插件", "选择空白插件或 before / after / replace 模板，保存后显式加载到目标。")
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(items, key = { it.id }) { plugin ->
                val host = sessions.values.firstOrNull { it.packageName == project.packageName && it.processName == plugin.processName.ifEmpty { project.packageName } }
                val instance = host?.let { instances["${it.id}/${plugin.id}"] }
                AppCard {
                    Row {
                        Column(Modifier.weight(1f).clickable { edit(plugin.id) }) {
                            Text(plugin.name, style = MaterialTheme.typography.titleMedium)
                            Text("${plugin.author.ifEmpty { "未填写作者" }} · ${plugin.version}", style = MaterialTheme.typography.bodySmall)
                        }
                        if (plugin.runtime == "BEANSHELL") Switch(plugin.desiredEnabled, { vm.setEnabled(project, plugin, it) }, enabled = !busy)
                    }
                    if (plugin.description.isNotBlank()) Text(plugin.description, style = MaterialTheme.typography.bodyMedium)
                    if (plugin.runtime == "NATIVE") NativePluginActions(vm, project, plugin)
                    else Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        StatusBadge(if (plugin.desiredEnabled) "期望启用" else "期望停用")
                        StatusBadge(if (host == null) "目标离线" else runtimeLabel(instance?.state), instance?.state == "RUNNING", instance?.state in listOf("FAILED", "ERROR"))
                        if (instance?.state == "RUNNING" && plugin.revision != instance.revision) StatusBadge("待重载")
                    }
                    PluginReleaseActions(vm, project, plugin)
                    Row {
                        TextButton(onClick = { edit(plugin.id) }) { Text("编辑") }
                        TextButton(onClick = { logs(plugin.id) }) { Text("日志") }
                    }
                }
            }
        }
    }
    if (create) CreatePluginDialog(vm, project, { create = false }, { create = false; edit(it) })
}

@Composable private fun CreatePluginDialog(vm: MainViewModel, project: ProjectEntity, close: () -> Unit, done: (String) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var author by rememberSaveable { mutableStateOf("") }
    var version by rememberSaveable { mutableStateOf("1.0") }
    var description by rememberSaveable { mutableStateOf("") }
    var runtime by rememberSaveable { mutableStateOf("BEANSHELL") }
    var template by rememberSaveable { mutableStateOf("blank") }
    AlertDialog(onDismissRequest = close, title = { Text("新建插件") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(name, { name = it }, label = { Text("名称") }, singleLine = true)
            OutlinedTextField(author, { author = it }, label = { Text("作者") }, singleLine = true)
            OutlinedTextField(version, { version = it }, label = { Text("版本") }, singleLine = true)
            OutlinedTextField(description, { description = it }, label = { Text("用途说明") })
            Row { listOf("BEANSHELL" to "Java", "NATIVE" to "Native C").forEach { (id, label) -> FilterChip(runtime == id, { runtime = id }, label = { Text(label) }) } }
            Text(if (runtime == "NATIVE") "创建 C17 源码；保存、构建并发布后下次启动生效" else "初始模板", style = MaterialTheme.typography.labelLarge)
            if (runtime == "BEANSHELL") Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("blank" to "空白", "before-add" to "before", "after-add" to "after", "replace-add" to "replace").forEach { (id, label) -> FilterChip(template == id, { template = id }, label = { Text(label) }) }
            }
        }
    }, confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { vm.create(project, template.takeUnless { it == "blank" }, name, author, description, version, runtime) { done(it.id) } }) { Text("创建") } }, dismissButton = { TextButton(onClick = close) { Text("取消") } })
}
