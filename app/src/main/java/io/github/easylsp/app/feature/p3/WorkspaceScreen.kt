package io.github.easylsp.app.feature.p3

import android.content.Intent
import androidx.core.content.FileProvider
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.easylsp.app.MainViewModel
import io.github.easylsp.app.data.ProjectEntity
import io.github.easylsp.app.platform.tools.RunBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

@Composable fun WorkspaceScreen(vm: MainViewModel, project: ProjectEntity, sessionId: String?, openOutput: (String, String, String) -> Unit = { _, _, _ -> }) {
    val graph = vm.graph
    val rootState by graph.root.state.collectAsStateWithLifecycle()
    val rootPreferred by graph.root.preferred.collectAsStateWithLifecycle()
    val records by graph.executions.store.records.collectAsStateWithLifecycle()
    val providers by remember { graph.models.available() }.collectAsStateWithLifecycle(emptyList())
    val skills by remember { graph.store.workflows.observeSkills() }.collectAsStateWithLifecycle(emptyList())
    val plugins by vm.plugins.collectAsStateWithLifecycle()
    var tab by rememberSaveable(project.id) { mutableStateOf("文件") }
    var command by rememberSaveable(project.id) { mutableStateOf("pwd; id") }
    var cwd by rememberSaveable(project.id) { mutableStateOf(".") }
    var timeout by rememberSaveable(project.id) { mutableStateOf("120") }
    var interactive by rememberSaveable { mutableStateOf(false) }
    var input by rememberSaveable { mutableStateOf("") }
    var args by rememberSaveable { mutableStateOf("{}") }
    var task by rememberSaveable { mutableStateOf("") }
    var taskConversation by rememberSaveable { mutableStateOf<String?>(null) }
    val context = androidx.compose.ui.platform.LocalContext.current
    var providerId by remember { mutableStateOf<String?>(null) }
    var binary by rememberSaveable { mutableStateOf(graph.frida.config().getString("binary")) }
    var root by rememberSaveable { mutableStateOf(graph.frida.config().getBoolean("root")) }
    var pid by rememberSaveable(sessionId) { mutableStateOf(graph.bridge.sessions.value[sessionId]?.pid?.toString() ?: "") }
    var source by rememberSaveable(project.id) { mutableStateOf("") }
    LaunchedEffect(project.id) { source = withContext(Dispatchers.IO) { val file = File(graph.executions.directory(project.id), "frida-editor.js"); if (file.exists()) file.readText() else "console.log('Attached to PID ' + Process.id);\nProcess.enumerateModules().slice(0, 10).forEach(m => console.log(m.name + ' ' + m.base));" } }
    var selected by remember(project.id) { mutableStateOf<String?>(null) }
    var output by rememberSaveable(project.id) { mutableStateOf("") }
    var nextOffset by remember { mutableStateOf<Int?>(null) }
    var apk by remember { mutableStateOf<File?>(null) }
    val selectedState = records.firstOrNull { it.id == selected }?.state
    LaunchedEffect(selected, selectedState) {
        output = ""; nextOffset = null
        selected?.let { id -> if (selectedState != "RUNNING") {
            val page = withContext(Dispatchers.IO) { graph.executions.store.page(project.id, id, 0, 12000) }
            output = page.getString("text"); nextOffset = if (page.isNull("nextOffset")) null else page.getInt("nextOffset")
        } }
    }
    val saveApk = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/vnd.android.package-archive")) { uri ->
        if (uri != null) vm.action { withContext(Dispatchers.IO) { requireNotNull(apk).inputStream().use { input -> requireNotNull(graph.contentResolver.openOutputStream(uri)).use { input.copyTo(it) } } }; "APK 已保存" }
    }
    if (tab == "文件" || tab == "PTY" || tab == "Linux") {
        Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
            TextButton(onClick = { tab = "终端" }) { Text("返回工作台") }
            if (tab == "文件") WorkspaceFilesScreen(vm, project.id, true, openOutput)
            else TerminalScreen(vm, project, tab == "Linux")
        }
        return
    }
    Column(Modifier.fillMaxSize().padding(12.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("root：${rootState.status} · ${if (rootPreferred && rootState.granted) "优先 root 执行" else "应用 UID / 未授权"}", fontSize = 12.sp)
        Row(Modifier.horizontalScroll(rememberScrollState())) { listOf("文件", "终端", "PTY", "Linux", "Skills", "子任务", "Frida", "APK").forEach { name -> FilterChip(tab == name, { tab = name }, label = { Text(name) }) } }
        when (tab) {
            "终端" -> {
                Text("命令使用 PTY 执行，与 AI 共用项目空间和记录；PTY 标签可查看和交互。Linux 环境可由 AI 安装与调用。", fontSize = 12.sp)
                Edit("工作目录（项目相对路径）", cwd) { cwd = it }
                Edit("超时秒数（1–3600）", timeout) { timeout = it }
                Script(command) { command = it }
                Row { Checkbox(interactive, { interactive = it }); Text("保留标准输入") }
                Button(onClick = { vm.action { selected = withContext(Dispatchers.IO) { graph.executions.command(project.id, command, cwd, timeout.toInt(), interactive) }; "命令已启动" } }) { Text("执行命令") }
            }
            "Skills" -> {
                Text("在设置 → Skills 编辑和启用。点击执行表示授权当前脚本及参数。", fontSize = 12.sp)
                Edit("JSON 参数", args) { args = it }
                skills.filter { it.enabled && it.runtime != "text" }.forEach { skill ->
                    Text("${skill.name} · ${skill.runtime}"); Text(skill.source, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                    Button(onClick = { vm.action { selected = withContext(Dispatchers.IO) { graph.skills.start(project.id, skill.id, JSONObject(args)) }; "Skill 已启动" } }) { Text("执行 ${skill.name}") }
                }
            }
            "子任务" -> {
                Text("使用所选模型创建独立任务会话，按设备资源排队。工具与授权遵循会话设置，离开页面继续运行。", fontSize = 12.sp)
                providers.forEach { provider -> FilterChip(providerId == provider.id, { providerId = provider.id }, label = { Text("${provider.name} · ${provider.model}") }) }
                Edit("子任务目标", task) { task = it }
                Button(onClick = { vm.action { taskConversation = withContext(Dispatchers.IO) { val provider = providers.firstOrNull { it.id == providerId } ?: error("请选择模型"); val conversation = graph.conversations.create(project.id, provider.id, task.lineSequence().first().take(30)); graph.chat.submit(conversation.id, task, sessionId, false, org.json.JSONArray()).await(); conversation.id }; "子任务已加入执行队列" } }) { Text("创建任务") }
                taskConversation?.let { id -> TextButton(onClick = { context.startActivity(Intent(context, io.github.easylsp.app.MainActivity::class.java).putExtra("route", "chat/$id").addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)) }) { Text("打开任务会话") } }
            }
            "Frida" -> {
                Text("需要设备上已安装可执行 frida-inject 及注入权限。JS 与 BeanShell 独立；不预置 Java bridge。停止将结束注入器并卸载脚本。", fontSize = 12.sp)
                Edit("frida-inject 绝对路径", binary) { binary = it }; Edit("明确 PID", pid) { pid = it }
                Row { Checkbox(root, { root = it }); Text("本次强制 su；已授权且优先 root 时自动使用") }
                Script(source) { source = it }
                Row {
                    TextButton(onClick = { vm.action { withContext(Dispatchers.IO) { File(graph.executions.directory(project.id), "frida-editor.js").writeText(source); graph.frida.save(binary, root) }; "Frida 源码和配置已保存" } }) { Text("保存") }
                    Button(onClick = { vm.action { selected = withContext(Dispatchers.IO) { graph.frida.start(project.id, pid.toInt(), source, binary, root) }; "Frida 已启动；请查看实际输出" } }) { Text("附加进程") }
                }
            }
            "APK" -> {
                Text("将已保存插件嵌入独立 Xposed 模块，同一插件使用固定包名和本机签名。包含单个 Java 插件及 DEX/APK 库，不包含联合发行。更新时覆盖安装同一包名；启用独立模块前先停用此处插件，然后重启目标以免双重 Hook。", fontSize = 12.sp)
                plugins.filter { it.projectId == project.id && it.runtime == "BEANSHELL" && (it.releaseManifest == "{}" || !it.releaseManifest.contains("JAVA_NATIVE")) && io.github.easylsp.app.data.ReleaseDependencies.installedOrder(it, plugins.filter { row -> row.projectId == project.id }).size == 1 }.forEach { plugin -> TextButton(onClick = { vm.action { apk = graph.apkExporter.export(plugin.id); "已生成 ${apk!!.name}" } }) { Text("生成 ${plugin.name} r${plugin.revision}") } }
                apk?.let { file ->
                    Text(file.name)
                    Row {
                        Button(onClick = { saveApk.launch(file.name) }) { Text("另存 APK") }
                        TextButton(onClick = { vm.action {
                            val uri = FileProvider.getUriForFile(graph, "${graph.packageName}.exports", file)
                            graph.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)); "已打开系统安装界面"
                        } }) { Text("系统安装") }
                    }
                }
            }
        }
        HorizontalDivider(); Text("执行记录", style = MaterialTheme.typography.titleMedium)
        records.filter { it.projectId == project.id }.take(30).forEach { row ->
            Row {
                TextButton(onClick = { selected = row.id }, modifier = Modifier.weight(1f)) { Text("${row.kind} · ${row.state} · ${row.exitCode ?: "—"}\n${row.title.take(90)}") }
                if (row.state == "RUNNING") TextButton(onClick = { graph.executions.stop(project.id, row.id) }) { Text("停止") }
            }
        }
        selected?.let { id ->
            val row = records.firstOrNull { it.id == id }
            TextButton(onClick = { vm.action {
                val text = withContext(Dispatchers.IO) {
                    val result = StringBuilder(); var offset = 0
                    do {
                        val page = graph.executions.store.page(project.id, id, offset, 12000)
                        result.append(page.getString("text")); offset = if (page.isNull("nextOffset")) -1 else page.getInt("nextOffset")
                    } while (offset >= 0)
                    result.toString()
                }
                openOutput("执行输出 · ${project.name} · ${row?.title.orEmpty()}", text, "text"); "完整输出已打开"
            } }) { Text("在查看器中打开完整输出") }
            Text("当前 ${id.take(8)} · ${row?.state} · exit=${row?.exitCode ?: "—"}")
            if (row?.state == "RUNNING") {
                Edit("标准输入", input) { input = it }
                Row {
                    TextButton(onClick = { vm.action { withContext(Dispatchers.IO) { graph.executions.input(project.id, id, input + "\n", false) }; input = ""; "已写入" } }) { Text("发送") }
                    TextButton(onClick = { vm.action { withContext(Dispatchers.IO) { graph.executions.input(project.id, id, "", true) }; "输入已结束" } }) { Text("EOF") }
                }
                Text(row.output, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
            } else Text(output, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
            nextOffset?.let { offset -> TextButton(onClick = { vm.action { val page = withContext(Dispatchers.IO) { graph.executions.store.page(project.id, id, offset, 12000) }; output = page.getString("text"); nextOffset = if (page.isNull("nextOffset")) null else page.getInt("nextOffset"); "下一页" } }) { Text("下一页 $offset") } }
        }
    }
}
@Composable private fun Edit(label: String, value: String, change: (String) -> Unit) { OutlinedTextField(value, change, label = { Text(label) }, modifier = Modifier.fillMaxWidth()) }
@Composable private fun Script(value: String, change: (String) -> Unit) { OutlinedTextField(value, change, textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp), modifier = Modifier.fillMaxWidth().height(190.dp)) }
