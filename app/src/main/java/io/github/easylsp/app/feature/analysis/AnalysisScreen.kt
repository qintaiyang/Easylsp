package io.github.easylsp.app.feature.analysis

import android.app.Application
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.easylsp.app.EasylspApp
import io.github.easylsp.app.data.ProjectEntity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject

class AnalysisViewModel(application: Application) : AndroidViewModel(application) {
    val graph = application as EasylspApp
    val result = MutableStateFlow<JSONObject?>(null)
    val status = MutableStateFlow("")
    val busy = MutableStateFlow(false)
    private var job: Job? = null
    private var last: Triple<ProjectEntity, String, JSONObject>? = null
    private var binding: Pair<String, Long>? = null
    fun bind(project: ProjectEntity): Boolean {
        val next = project.id to project.sampleRevision
        if (binding == next) return false
        binding = next; reset(); return true
    }
    fun capture(project: ProjectEntity) = launch {
        val updated = graph.samples.capture(project)
        status.value = "已采集样本 r${updated.sampleRevision}（含可用 split APK）"
    }
    fun run(project: ProjectEntity, action: String, args: JSONObject = JSONObject()) {
        last = Triple(project, action, args)
        launch { result.value = graph.analysis.call(project.id, project.sampleRevision, action, args) { status.value = it }; status.value = "完成 · 样本 r${project.sampleRevision}" }
    }
    fun page(first: Boolean = false) { val previous = last ?: return; val offset = if (first) 0 else result.value?.optInt("nextOffset") ?: return; run(previous.first, previous.second, JSONObject(previous.third.toString()).put("offset", offset)) }
    fun read(project: ProjectEntity, action: String, args: JSONObject, done: (String) -> Unit) = launch {
        var offset = 0
        val output = StringBuilder()
        do {
            val page = graph.analysis.call(project.id, project.sampleRevision, action, JSONObject(args.toString()).put("offset", offset).put("limit", 200)) { status.value = it }
            if (page.optBoolean("partial")) output.appendLine("// Java 还原不完整；请查看同一方法的 Smali。")
            if (output.isNotEmpty()) output.append('\n')
            output.append(page.optString("text", page.toString(2)))
            offset = if (page.isNull("nextOffset") || !page.has("nextOffset")) -1 else page.getInt("nextOffset")
        } while (offset >= 0)
        done(output.toString()); status.value = "完成 · 样本 r${project.sampleRevision}"
    }
    fun stop() { job?.cancel() }
    fun reset() { job?.cancel(); result.value = null; last = null; status.value = "" }
    private fun launch(block: suspend () -> Unit) {
        job?.cancel()
        job = viewModelScope.launch {
            busy.value = true
            try { block() }
            catch (error: CancellationException) { status.value = "任务已取消"; throw error }
            catch (error: Exception) { status.value = error.message ?: error.toString() }
            finally { busy.value = false }
        }
    }
}

@Composable fun AnalysisScreen(project: ProjectEntity, vm: AnalysisViewModel = viewModel(), openCode: (String, String, String) -> Unit = { _, _, _ -> }, template: (String) -> Unit = {}) {
    val result by vm.result.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    var action by rememberSaveable { mutableStateOf("classes") }
    var query by rememberSaveable { mutableStateOf("") }
    var selectedJson by rememberSaveable { mutableStateOf<String?>(null) }
    val selected = selectedJson?.let { JSONObject(it) }
    LaunchedEffect(project.sampleRevision) { if (vm.bind(project)) selectedJson = null }
    fun read(operation: String, args: JSONObject) { vm.read(project, operation, args) { code -> openCode("${project.name} · r${project.sampleRevision} · ${args.optString("source", operation)}", code, if (operation == "manifest") "xml" else operation) } }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            if (project.kind == "INSTALLED") TextButton(enabled = !busy, onClick = { vm.capture(project) }) { Text("采集 / 更新 APK") }
            TextButton(enabled = !busy && project.sampleRevision > 0, onClick = { read("info", JSONObject()) }) { Text("样本来源") }
            TextButton(enabled = !busy && project.sampleRevision > 0, onClick = { read("manifest", JSONObject()) }) { Text("Manifest") }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("classes" to "类", "methods" to "方法", "fields" to "字段", "strings" to "字符串", "references" to "引用", "resources" to "资源").forEach { (value, label) -> FilterChip(action == value, { action = value }, label = { Text(label) }) }
        }
        OutlinedTextField(query, { query = it }, label = { Text(if (action == "references") "完整 DEX 签名" else "搜索名称或签名") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        Row {
            Button(enabled = !busy && project.sampleRevision > 0, onClick = { selectedJson = null; vm.run(project, action, JSONObject().put(if (action == "references") "target" else "query", query)) }) { Text("查询") }
            TextButton(enabled = busy, onClick = vm::stop) { Text("取消") }
        }
        if (status.isNotEmpty()) Text(status, style = MaterialTheme.typography.labelSmall)
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (project.sampleRevision == 0L) io.github.easylsp.app.ui.components.EmptyState("还没有分析样本", "采集 APK 后，可离线浏览类、成员、字符串和资源。")
        val current = result
        if (current != null) {
            val array = current.optJSONArray("items")
            if (array != null) {
                Text("样本 r${current.optLong("sampleRevision")} · 共 ${current.optInt("total")} 项 · 第 ${current.optInt("offset") + 1} 项起", style = MaterialTheme.typography.labelSmall)
                LazyColumn(Modifier.weight(1f)) {
                    items((0 until array.length()).map { array.getJSONObject(it) }) { item ->
                        ListItem(headlineContent = { Text(item.optString("javaSignature", item.optString("name", item.optString("signature"))), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }, supportingContent = { Text(item.optString("source") + "\n" + item.optString("signature"), style = MaterialTheme.typography.labelSmall) }, modifier = Modifier.clickable { selectedJson = item.toString() })
                        HorizontalDivider()
                    }
                }
                Row { TextButton(enabled = !busy && current.optInt("offset") > 0, onClick = { vm.page(true) }) { Text("首页") }; TextButton(enabled = !busy && !current.isNull("nextOffset"), onClick = { vm.page() }) { Text("下一页") } }
            }
        }
    }
    if (selected != null) AlertDialog(onDismissRequest = { selectedJson = null }, title = { Text(if (selected.has("target")) "引用位置" else "分析结果") }, text = {
        Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
            Text(selected.optString("signature", selected.optString("name")), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            Text("${selected.optString("source")} · 样本 r${project.sampleRevision}", style = MaterialTheme.typography.labelSmall)
            if (selected.has("target")) Text("引用 ${selected.getString("target")} · 指令偏移 ${selected.optInt("codeOffset")}")
            if (selected.has("owner")) {
                Row {
                    TextButton(enabled = !busy, onClick = { read("java", JSONObject().put("className", selected.getString("owner")).put("source", selected.getString("source")).put("signature", selected.optString("signature"))) }) { Text("Java") }
                    TextButton(enabled = !busy, onClick = { read("smali", JSONObject().put("className", selected.getString("owner")).put("source", selected.getString("source")).put("signature", if (selected.optString("kind") == "method") selected.getString("signature") else "")) }) { Text("Smali") }
                }
                TextButton(enabled = !busy, onClick = { query = selected.getString("signature"); action = "references"; selectedJson = null; vm.run(project, "references", JSONObject().put("target", query)) }) { Text("查找引用") }
                if (selected.optString("kind") == "method") TextButton(onClick = { template(selected.getString("signature")) }) { Text("生成 Hook 模板") }
            } else if (selected.has("name")) TextButton(enabled = !busy, onClick = { read("resource", JSONObject().put("apk", selected.getString("source")).put("entry", selected.getString("name"))) }) { Text("查看资源") }
        }
    }, confirmButton = { TextButton(onClick = { selectedJson = null }) { Text("关闭") } })
}
