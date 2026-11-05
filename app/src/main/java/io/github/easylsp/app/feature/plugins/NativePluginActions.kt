package io.github.easylsp.app.feature.plugins

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.easylsp.app.MainViewModel
import io.github.easylsp.app.data.PluginEntity
import io.github.easylsp.app.data.ProjectEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

@Composable fun NativePluginActions(vm: MainViewModel, project: ProjectEntity, plugin: PluginEntity, saved: Boolean = true, locate: ((String, Int) -> Unit)? = null) {
    val builds by vm.nativeBuilds.collectAsStateWithLifecycle()
    val deployments by vm.nativeDeployments.collectAsStateWithLifecycle()
    val reports by vm.nativeReports.collectAsStateWithLifecycle()
    val executions by vm.graph.executions.store.records.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val rows = builds.filter { it.pluginId == plugin.id }
    val publication = deployments.firstOrNull { it.projectId == project.id && it.state == "PUBLISHED" }
    val items = publication?.let { JSONObject(it.snapshot).getJSONArray("plugins") }
    val published = items?.let { array -> (0 until array.length()).map { array.getJSONObject(it) }.firstOrNull { it.getString("pluginId") == plugin.id } }
    var details by rememberSaveable { mutableStateOf(false) }
    var selected by rememberSaveable { mutableStateOf("") }
    var policy by rememberSaveable { mutableStateOf("") }
    var output by remember { mutableStateOf("") }
    var logBuild by rememberSaveable { mutableStateOf("") }
    val build = rows.firstOrNull { it.buildId == logBuild }
    val execution = executions.firstOrNull { it.id == build?.executionId }
    LaunchedEffect(build?.executionId, execution?.output) {
        output = if (build == null || build.executionId.isEmpty()) "" else withContext(Dispatchers.IO) { vm.graph.executions.store.file(build.executionId).takeIf { it.exists() }?.readText().orEmpty().takeLast(24000) }
    }
    Text("Native C · 源码 r${plugin.revision} · ${published?.let { "已发布 r${it.getLong("sourceRevision")}" } ?: "未启用"}", style = MaterialTheme.typography.labelSmall)
    Row(Modifier.horizontalScroll(rememberScrollState())) {
        TextButton(enabled = !busy && saved, onClick = { vm.buildNative(project, plugin) }) { Text("构建") }
        TextButton(onClick = { details = true }) { Text("构建与发布") }
        if (published != null) TextButton(enabled = !busy, onClick = { vm.setEnabled(project, plugin, false) }) { Text("停用") }
        TextButton(enabled = !busy && project.kind == "INSTALLED", onClick = { details = true; vm.restartNative(project, plugin) }) { Text("重启并验证") }
    }
    if (details) AlertDialog(onDismissRequest = { details = false }, title = { Text("Native 构建与执行") }, text = {
        Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("发布和停用在目标新进程生效；当前进程保留已加载版本。")
            TextButton(enabled = !busy, onClick = { vm.action { withContext(Dispatchers.IO) { vm.graph.nativeBuilds.prepare(project.id) }.toString() } }) { Text("准备 Android 编译工具") }
            rows.forEach { row ->
                Text("r${row.sourceRevision} · ${row.state} · ${row.buildId}", style = MaterialTheme.typography.bodySmall)
                Row {
                    if (row.state == "SUCCEEDED") FilterChip(selected == row.buildId, { selected = row.buildId }, label = { Text("选择发布") })
                    if (row.executionId.isNotEmpty()) TextButton(onClick = { logBuild = row.buildId }) { Text("编译输出") }
                }
                if (row.error.isNotEmpty()) Text(row.error, color = MaterialTheme.colorScheme.error)
            }
            if (rows.isEmpty()) Text("尚未构建")
            Text("项目运行策略（影响该项目全部 Native 插件）")
            listOf("NATIVE_ONLY" to "仅 Native：跳过 Java 插件和 Inspector", "NATIVE_AND_JAVA" to "Native 与 Java 插件共同运行").forEach { (id, label) ->
                FilterChip(policy == id, { policy = id }, label = { Text(label) })
            }
            Button(enabled = !busy && selected.isNotEmpty() && policy.isNotEmpty(), onClick = { vm.enableNative(plugin, selected, policy) }) { Text("启用，下次启动生效") }
            TextButton(enabled = !busy, onClick = { vm.nativeStatus(plugin) }) { Text("查询实际执行结果") }
            reports[plugin.id]?.let { Text(it, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
            if (output.isNotEmpty()) {
                Text("编译输出 · ${build?.buildId}")
                Text(output, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                if (locate != null && build?.sourceRevision == plugin.revision) Regex("(?:^|\\s)(?:/[^\\s:]+/)?((?:src|include)/[^:\\s]+):(\\d+):\\d+:").findAll(output).map { it.groupValues[1] to it.groupValues[2].toInt() }.distinct().forEach { (path, line) ->
                    TextButton(onClick = { details = false; locate(path, line) }) { Text("定位 $path:$line") }
                }
            }
        }
    }, confirmButton = { TextButton(onClick = { details = false }) { Text("关闭") } })
}
