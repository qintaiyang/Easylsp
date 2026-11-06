package io.github.easylsp.app.feature.diagnostics

import io.github.easylsp.app.util.readUpTo
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.easylsp.app.MainViewModel
import io.github.easylsp.app.data.ProjectEntity
import io.github.easylsp.app.platform.diagnostics.DiagnosticMode
import io.github.easylsp.app.platform.diagnostics.DiagnosticExport
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable fun DiagnosticsScreen(vm: MainViewModel, project: ProjectEntity, openOutput: (String, String, String) -> Unit) {
    val graph = vm.graph
    val diagnostics = remember { graph.diagnostics }
    val rows by remember(project.id) { graph.store.diagnostics.observe(project.id) }.collectAsStateWithLifecycle(emptyList())
    val busy by vm.busy.collectAsStateWithLifecycle()
    var mode by rememberSaveable { mutableStateOf(DiagnosticMode.EXTERNAL_ONLY.name) }
    var process by rememberSaveable(project.id) { mutableStateOf("*") }
    var duration by rememberSaveable { mutableStateOf("15") }
    var restart by rememberSaveable { mutableStateOf(false) }
    var exportId by rememberSaveable { mutableStateOf<String?>(null) }
    var exportPreview by remember { mutableStateOf(false) }
    var includeRaw by rememberSaveable { mutableStateOf(false) }
    var exportFiles by remember { mutableStateOf(emptyList<String>()) }
    val exporter = remember { DiagnosticExport(diagnostics.store) }
    val saveZip = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val id = exportId
        if (uri != null && id != null) vm.action {
            withContext(Dispatchers.IO) { exporter.write(project.id, id, includeRaw, requireNotNull(graph.contentResolver.openOutputStream(uri))) }
            "诊断 ZIP 已导出"
        }
    }
    LaunchedEffect(exportId, includeRaw, exportPreview) {
        val id = exportId
        if (id != null && exportPreview) exportFiles = withContext(Dispatchers.IO) { exporter.files(project.id, id, includeRaw).map { it.relativeTo(diagnostics.store.directory(project.id, id)).path } }
    }
    if (exportPreview) AlertDialog(onDismissRequest = { exportPreview = false }, title = { Text("导出内容预览") },
        text = { Column {
            Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(includeRaw, { includeRaw = it }); Text("附带目标 tombstone 和 maps 文本") }
            Text("应用私有目录替换为占位符。包含下列文件及导出清单：", style = MaterialTheme.typography.bodySmall)
            LazyColumn(Modifier.heightIn(max = 280.dp)) { items(exportFiles) { Text(it, style = MaterialTheme.typography.bodySmall) } }
        } }, confirmButton = { TextButton(onClick = { exportPreview = false; saveZip.launch("diagnostic-$exportId.zip") }) { Text("保存 ZIP") } },
        dismissButton = { TextButton(onClick = { exportPreview = false }) { Text("取消") } })
    var analysisId by remember { mutableStateOf<String?>(null) }
    var nativeModules by remember { mutableStateOf(emptyList<JSONObject>()) }
    var addresses by remember { mutableStateOf(emptyList<Pair<String, JSONObject>>()) }
    if (analysisId != null) AlertDialog(onDismissRequest = { analysisId = null }, title = { Text("SO 与指令定位") }, text = {
        LazyColumn(Modifier.heightIn(max = 420.dp)) {
            if (addresses.isEmpty()) item { Text("暂无可关联到 APK SO 的崩溃或退出地址，可查看模块元数据。") }
            items(addresses.indices.toList()) { index ->
                val (label, args) = addresses[index]
                TextButton(enabled = !busy, onClick = { vm.action {
                    val result = withContext(Dispatchers.IO) { graph.nativeAnalysis.disassemble(project.id, args.getLong("sampleRevision"), args.getString("moduleId"), args) }
                    openOutput(label, result.toString(2), "json"); ""
                } }) { Text(label) }
            }
            items(nativeModules.indices.toList()) { index ->
                val module = nativeModules[index]
                TextButton(enabled = !busy && !module.has("error"), onClick = { vm.action {
                    val result = withContext(Dispatchers.IO) { graph.nativeAnalysis.inspect(project.id, module.getLong("sampleRevision"), module.getString("moduleId")) }
                    openOutput(module.getString("name"), result.toString(2), "json"); ""
                } }) { Text("${module.getString("name")} · ${module.getString("matchStatus")}") }
            }
        }
    }, confirmButton = { TextButton(onClick = { analysisId = null }) { Text("关闭") } })
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.action {
            withContext(Dispatchers.IO) {
                val text = requireNotNull(graph.contentResolver.openInputStream(uri)).use { input ->
                    val bytes = input.readUpTo(4 * 1024 * 1024 + 1)
                    require(bytes.size <= 4 * 1024 * 1024) { "tombstone 文本不能超过 4 MiB" }; bytes.toString(Charsets.UTF_8)
                }
                diagnostics.importTombstone(project, text)
            }
            "已导入目标 tombstone"
        }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("启动诊断", style = MaterialTheme.typography.titleMedium)
            Text("先采集，再启动；目标离线也能读取结果。", style = MaterialTheme.typography.bodySmall)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                DiagnosticMode.entries.forEach { value -> FilterChip(mode == value.name, { mode = value.name; if (value != DiagnosticMode.EXTERNAL_ONLY) restart = true }, label = { Text(value.label) }) }
            }
            Text(when (DiagnosticMode.valueOf(mode)) {
                DiagnosticMode.EXTERNAL_ONLY -> "仅采集系统证据，目标仍沿用原有的插件自动加载行为。"
                DiagnosticMode.NATIVE_OBSERVE -> "应用创建前观测库构造与退出调用；本次跳过 Java hook 和插件。"
                DiagnosticMode.ENTRY_ONLY -> "本次新进程只保留模块入口。"
                DiagnosticMode.JAVA_NO_PLUGINS -> "保留 Java 初始化和桥接，跳过连接时的插件自动加载。"
                DiagnosticMode.NORMAL -> "按保存配置自动加载已启用插件。"
            }, style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(process, { process = it }, label = { Text("进程名（* 为本应用全部进程）") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedTextField(duration, { duration = it }, label = { Text("采集秒数（5–120）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(restart, { restart = it }, enabled = mode == DiagnosticMode.EXTERNAL_ONLY.name)
                Text("先停止目标全部进程再启动")
            }
            Text("入口对照仅对本次启动生效，保存的插件启用配置保持不变。系统证据通常需要 root；不会修改框架作用域。", style = MaterialTheme.typography.bodySmall)
            Button(enabled = !busy && project.kind == "INSTALLED" && rows.none { it.finishedAt == null }, onClick = { vm.action {
                diagnostics.start(project, project.sampleRevision, DiagnosticMode.valueOf(mode), process, duration.toInt(), restart)
                "诊断已开始，可离开页面，达到时限自动整理报告"
            } }) { Text(if (restart) "停止目标后诊断启动" else "诊断启动") }
            TextButton(enabled = !busy, onClick = { importer.launch(arrayOf("text/*", "application/octet-stream")) }) { Text("导入目标 tombstone 文本") }
        }
        if (rows.isEmpty()) item { Text("尚无诊断记录。") }
        items(rows, key = { it.id }) { row ->
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("${row.mode} · ${row.state}", style = MaterialTheme.typography.titleSmall)
                    Text("${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(row.startedAt))} · 样本 r${row.sampleRevision}", style = MaterialTheme.typography.bodySmall)
                    Text(row.id, style = MaterialTheme.typography.labelSmall)
                    Row(Modifier.horizontalScroll(rememberScrollState())) {
                        TextButton(onClick = { vm.action {
                            val text = withContext(Dispatchers.IO) { diagnostics.read(project.id, row.id); java.io.File(diagnostics.store.directory(project.id, row.id), "report.md").readText() }
                            openOutput("启动诊断", text, "markdown"); ""
                        } }) { Text("查看报告") }
                        TextButton(onClick = { vm.action {
                            val text = withContext(Dispatchers.IO) { diagnostics.read(project.id, row.id).toString(2) }
                            openOutput("诊断证据", text, "json"); ""
                        } }) { Text("结构化证据") }
                        if (row.finishedAt != null) TextButton(onClick = { exportId = row.id; exportPreview = true }) { Text("导出 ZIP") }
                        if (row.sampleRevision > 0 && row.finishedAt != null) TextButton(onClick = { vm.action {
                            val loaded = withContext(Dispatchers.IO) {
                                val report = diagnostics.read(project.id, row.id)
                                val all = graph.nativeAnalysis.modules(project.id, row.sampleRevision, row.id).getJSONArray("modules")
                                val modules = (0 until all.length()).map { all.getJSONObject(it) }
                                val candidates = mutableListOf<Pair<String, JSONObject>>()
                                fun address(path: String, pc: String, kind: String, buildId: String?) {
                                    val module = modules.singleOrNull { it.getString("name") == path.substringAfterLast('/') && !it.has("error") } ?: return
                                    val args = JSONObject().put("moduleId", module.getString("moduleId")).put("sampleRevision", row.sampleRevision).put("diagnosticRunId", row.id)
                                        .put("address", pc).put("addressKind", kind).put("expectedBuildId", buildId)
                                    candidates += "${module.getString("name")} + $pc" to args
                                }
                                val tombstones = report.getJSONArray("tombstones")
                                for (i in 0 until tombstones.length()) {
                                    val frames = tombstones.getJSONObject(i).getJSONArray("frames")
                                    for (j in 0 until frames.length()) { val f = frames.getJSONObject(j); address(f.getString("module"), f.getString("pc"), "TOMBSTONE_RELATIVE_PC", f.optString("buildId").takeIf { it != "null" && it.isNotEmpty() }) }
                                }
                                report.optJSONObject("nativeObservation")?.getJSONArray("exitCalls")?.let { calls ->
                                    for (i in 0 until calls.length()) { val c = calls.getJSONObject(i); if (c.has("callerModule")) address(c.getString("callerModule"), c.getString("callerElfVaddr"), "ELF_VADDR", null) }
                                }
                                modules to candidates
                            }
                            nativeModules = loaded.first; addresses = loaded.second; analysisId = row.id; ""
                        } }) { Text("SO / 反汇编") }
                        if (row.finishedAt == null) TextButton(onClick = { vm.action { withContext(Dispatchers.IO) { diagnostics.stop(project.id, row.id) }; "正在整理已取得的证据" } }) { Text("结束采集") }
                    }
                }
            }
        }
    }
}
