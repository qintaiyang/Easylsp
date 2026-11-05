package io.github.easylsp.app.feature.settings

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.easylsp.app.MainViewModel
import io.github.easylsp.app.data.*
import io.github.easylsp.app.platform.ai.ModelTransport
import io.github.easylsp.app.platform.mcp.McpClient
import io.github.easylsp.app.platform.tasks.RemoteTaskClient
import io.github.easylsp.app.platform.tools.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.*
import java.io.File
import java.util.UUID

@Composable fun ExtensionsScreen(vm: MainViewModel, initialTab: String = "模型") {
    val graph = vm.graph; val dao = graph.store.workflows
    val providers by remember { dao.observeProviders() }.collectAsStateWithLifecycle(emptyList())
    val modelConfigs by remember { dao.observeModels() }.collectAsStateWithLifecycle(emptyList())
    val servers by remember { dao.observeServers() }.collectAsStateWithLifecycle(emptyList())
    val plugins by remember { dao.observeToolPlugins() }.collectAsStateWithLifecycle(emptyList())
    val skills by remember { dao.observeSkills() }.collectAsStateWithLifecycle(emptyList())
    var tab by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(initialTab) }
    var providerEditor by remember { mutableStateOf<ProviderEntity?>(null) }
    var serverEditor by remember { mutableStateOf<McpServerEntity?>(null) }
    var pluginEditor by remember { mutableStateOf<ToolPluginEntity?>(null) }
    var skillEditor by remember { mutableStateOf<SkillEntity?>(null) }
    var listed by remember { mutableStateOf<List<ToolDefinition>>(emptyList()) }
    var output by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(12.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState())) { listOf("模型", "MCP", "工具插件", "Skills", "工具开关", "规则与提示词").forEach { name -> FilterChip(selected = tab == name, onClick = { tab = name }, label = { Text(name) }); Spacer(Modifier.width(4.dp)) } }
        when (tab) {
            "规则与提示词" -> KnowledgeScreen(vm, promptsOnly = true)
            "模型" -> {
                Text("选择供应商、填写密钥即可获取模型，可批量添加。API Key 由 Android Keystore 加密保存。", fontSize = 12.sp)
                TextButton(onClick = { providerEditor = ProviderEntity(UUID.randomUUID().toString(), "OpenAI", "openai", "", "", maxSteps = 0, timeoutSeconds = 300) }) { Text("连接供应商 / 获取模型") }
                providers.forEach { item -> ListItem(headlineContent = { Text(item.name) }, supportingContent = { Text("${item.protocol} · ${modelConfigs.count { it.providerId == item.id && it.enabled }} 个已启用模型\n${item.baseUrl}") }, modifier = Modifier.clickable { providerEditor = item }) }
            }
            "MCP" -> {
                Text("支持 HTTP、SSE、Ubuntu stdio 与 OAuth；按需连接服务、读取工具和资源。凭据由 Keystore 保存。", fontSize = 12.sp)
                TextButton(onClick = { serverEditor = McpServerEntity(UUID.randomUUID().toString(), "新服务", "", false) }) { Text("新增服务") }
                servers.forEach { item -> ListItem(headlineContent = { Text(item.name) }, supportingContent = { Text(item.url) }, trailingContent = { Switch(item.enabled, { vm.action { withContext(Dispatchers.IO) { graph.mcp.setEnabled(item.id, it) }; "MCP 配置已保存" } }) }, modifier = Modifier.clickable { serverEditor = item }) }
            }
            "工具插件" -> {
                Text("脚本运行于管理端 :tools 进程，与目标 Hook 插件分开。脚本可执行管理端权限内的 Java 代码；仅启用自己认可的源码。停用会清除已装载工具。", fontSize = 12.sp)
                TextButton(onClick = { pluginEditor = ToolPluginEntity(UUID.randomUUID().toString(), "新工具插件") }) { Text("新增工具脚本") }
                plugins.forEach { item -> ListItem(headlineContent = { Text(item.name) }, supportingContent = { Text("r${item.revision}") }, trailingContent = { Switch(item.enabled, { value -> vm.action { withContext(Dispatchers.IO) { dao.saveToolPlugin(item.copy(enabled = value)) }; val result = RemoteTaskClient(graph, ToolPluginService::class.java).call(JSONObject().put("action", "list")); output = result.toString(2); "工具插件状态已更新" } }) }, modifier = Modifier.clickable { pluginEditor = item }) }
            }
            "Skills" -> {
                KnowledgeScreen(vm, skillsOnly = true)
                Text("文本说明按需读取；shell / BeanShell 源码需显式执行。", fontSize = 12.sp)
                TextButton(onClick = { skillEditor = SkillEntity(UUID.randomUUID().toString(), "新 Skill", "", false) }) { Text("新增 Skill") }
                skills.filter { it.packagePath.isEmpty() }.forEach { item -> ListItem(headlineContent = { Text(item.name) }, trailingContent = { Switch(item.enabled, { vm.action { withContext(Dispatchers.IO) { graph.skillPackages.save(item.copy(enabled = it)) }; "Skill 状态已保存" } }) }, modifier = Modifier.clickable { skillEditor = item }) }
            }
            "工具开关" -> ToolDirectory(vm)
        }
        if (output.isNotEmpty()) Text(output, fontFamily = FontFamily.Monospace, fontSize = 11.sp, modifier = Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState()))
    }
    providerEditor?.let { item -> ProviderEditor(vm, item) { providerEditor = null } }
    serverEditor?.let { item -> McpEditor(vm, item) { serverEditor = null } }
    pluginEditor?.let { item ->
        var name by remember(item.id) { mutableStateOf(item.name) }; var source by remember(item.id) { mutableStateOf("") }
        LaunchedEffect(item.id) { source = withContext(Dispatchers.IO) { val file = File(graph.filesDir, "tool-plugins/${item.id}/${item.revision}.java"); if (file.exists()) file.readText() else graph.assets.open("tool-examples/echo.java").bufferedReader().use { it.readText() } } }
        EditorDialog("BeanShell 工具插件", { pluginEditor = null }, { vm.action {
            withContext(Dispatchers.IO) { val existing = dao.toolPlugin(item.id); val next = item.copy(name = name, revision = if (existing == null) 1 else existing.revision + 1); File(graph.filesDir, "tool-plugins/${next.id}/${next.revision}.java").apply { parentFile!!.mkdirs(); writeText(source) }; dao.saveToolPlugin(next) }
            pluginEditor = null; "工具脚本已保存；启用后注册"
        } }) {
            Field("名称", name) { name = it }; OutlinedTextField(source, { source = it }, textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp), modifier = Modifier.fillMaxWidth().height(300.dp))
            TextButton(onClick = { vm.action { withContext(Dispatchers.IO) { dao.deleteToolPlugin(item.id); File(graph.filesDir, "tool-plugins/${item.id}").deleteRecursively() }; RemoteTaskClient(graph, ToolPluginService::class.java).call(JSONObject().put("action", "list")); pluginEditor = null; "工具插件已删除" } }) { Text("删除") }
        }
    }
    skillEditor?.let { item ->
        var name by remember(item.id) { mutableStateOf(item.name) }; var text by remember(item.id) { mutableStateOf(item.text) }
        var runtime by remember(item.id) { mutableStateOf(item.runtime) }
        var source by remember(item.id) { mutableStateOf(item.source) }
        var seconds by remember(item.id) { mutableStateOf(item.timeoutSeconds.toString()) }
        EditorDialog("Skill", { skillEditor = null }, { vm.action { withContext(Dispatchers.IO) { require(name.isNotBlank() && seconds.toInt() in 1..3600); graph.skillPackages.save(item.copy(name = name, text = text, runtime = runtime, source = source, timeoutSeconds = seconds.toInt())) }; skillEditor = null; "Skill 已保存" } }) {
            Row { listOf("text", "shell", "beanshell").forEach { value -> FilterChip(runtime == value, { runtime = value }, label = { Text(value) }) } }
            if (runtime != "text") {
                Field("执行超时（秒）", seconds) { seconds = it }
                OutlinedTextField(source, { source = it }, label = { Text("源码") }, textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp), modifier = Modifier.fillMaxWidth().height(240.dp))
            }
            Field("名称", name) { name = it }; OutlinedTextField(text, { text = it }, label = { Text("文本说明") }, modifier = Modifier.fillMaxWidth().height(240.dp))
            TextButton(onClick = { vm.action { withContext(Dispatchers.IO) { dao.deleteSkill(item.id) }; skillEditor = null; "Skill 已删除" } }) { Text("删除") }
        }
    }
}
@Composable internal fun Field(label: String, value: String, change: (String) -> Unit) { OutlinedTextField(value, change, label = { Text(label) }, modifier = Modifier.fillMaxWidth(), singleLine = true) }
@Composable internal fun EditorDialog(title: String, close: () -> Unit, save: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    AlertDialog(onDismissRequest = close, title = { Text(title) }, text = { Column(Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp), content = content) }, confirmButton = { TextButton(onClick = save) { Text("保存") } }, dismissButton = { TextButton(onClick = close) { Text("关闭") } })
}
