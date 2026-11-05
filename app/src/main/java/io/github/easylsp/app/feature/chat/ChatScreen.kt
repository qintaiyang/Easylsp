package io.github.easylsp.app.feature.chat

import android.app.Application
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.easylsp.app.EasylspApp
import io.github.easylsp.app.data.*
import io.github.easylsp.app.ui.components.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import io.github.easylsp.app.feature.settings.ProviderEditor
import org.json.JSONObject
import org.json.JSONArray
import java.util.UUID
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ChatViewModel(application: Application) : AndroidViewModel(application) {
    val graph = application as EasylspApp
    val error = MutableStateFlow("")
    fun create(project: ProjectEntity, provider: ProviderEntity, done: (String) -> Unit) { viewModelScope.launch {
        val id = withContext(Dispatchers.IO) { graph.conversations.create(project.id, provider.id).id }
        done(id)
    } }
    fun rename(id: String, name: String) { viewModelScope.launch(Dispatchers.IO) { graph.store.workflows.renameConversation(id, name.trim(), System.currentTimeMillis()) } }
    fun delete(projectId: String, id: String) { viewModelScope.launch(Dispatchers.IO) {
        check(graph.chat.activeConversation.value != id)
        graph.store.transaction {
            require(graph.store.workflows.conversation(id)?.projectId == projectId)
            graph.store.workflows.branchHistory(id).forEach { branch ->
                graph.store.workflows.deleteMessages(branch.id); graph.store.workflows.deleteToolRuns(branch.id)
                graph.store.workflows.deleteTurns(branch.id); graph.store.workflows.deleteInteractions(branch.id); graph.store.workflows.clearPlan(branch.id)
            }
            graph.store.workflows.deleteBranches(id); graph.store.workflows.deleteUsage(id)
            graph.store.workflows.deleteMessages(id); graph.store.workflows.deleteToolRuns(id); graph.store.workflows.clearPlan(id); graph.store.workflows.deleteTurns(id); graph.store.workflows.deleteDraft(id); graph.store.workflows.deleteConversation(projectId, id)
            graph.store.workflows.deleteInputs(id); graph.store.workflows.deleteInteractions(id); graph.store.workflows.deleteReading(id)
        }
    } }
    fun send(id: String, text: String, sessionId: String?, allow: Boolean, attachments: JSONArray = JSONArray(), accepted: () -> Unit = {}, rejected: () -> Unit = {}, intent: String = "NEW_TASK", editedMessageId: Long? = null, branchId: String? = null) {
        viewModelScope.launch {
            try { graph.chat.submit(id, text, sessionId, allow, attachments, intent, editedMessageId, branchId).await(); error.value = ""; accepted() }
            catch (failure: Exception) { error.value = failure.message ?: failure.toString(); rejected() }
        }
    }
    fun saveDraft(id: String, text: String, attachments: List<JSONObject>, editedMessageId: Long? = null, branchId: String? = null) {
        viewModelScope.launch(Dispatchers.IO) { graph.store.workflows.saveDraft(ChatDraftEntity(id, text, JSONArray(attachments).toString(), editedMessageId, branchId)) }
    }
}

@Composable fun ChatScreen(project: ProjectEntity, sessionId: String?, vm: ChatViewModel = viewModel(), open: (String) -> Unit = {}) {
    val dao = vm.graph.store.workflows
    val providers by remember { vm.graph.models.available() }.collectAsStateWithLifecycle(emptyList())
    val loadedConversations by remember(project.id) { dao.conversations(project.id) }.collectAsStateWithLifecycle<List<ConversationEntity>?>(null)
    val conversations = loadedConversations.orEmpty()
    val active by vm.graph.chat.activeConversation.collectAsStateWithLifecycle()
    var configureModel by remember { mutableStateOf(false) }
    var sampleStatus by remember { mutableStateOf("") }
    var entered by rememberSaveable(project.id) { mutableStateOf(false) }
    var preparing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(project.id, project.sampleRevision) {
        if (project.sampleRevision == 0L && project.kind == "INSTALLED") {
            preparing = true; sampleStatus = "正在自动准备分析样本…"
            try { vm.graph.samples.capture(project); sampleStatus = "" }
            catch (e: CancellationException) { throw e } catch (e: Exception) { sampleStatus = e.message ?: "样本准备失败" }
            finally { preparing = false }
        }
    }
    LaunchedEffect(project.sampleRevision, providers, conversations) {
        if (!entered && loadedConversations != null && providers.isNotEmpty() && project.sampleRevision > 0) {
            entered = true
            conversations.firstOrNull()?.let { open(it.id) } ?: vm.create(project, providers.first(), open)
        }
    }
    var providerId by rememberSaveable { mutableStateOf<String?>(null) }
    var renameId by rememberSaveable { mutableStateOf<String?>(null) }
    var name by rememberSaveable { mutableStateOf("") }
    var deleteId by rememberSaveable { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = 16.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            providers.forEach { provider -> FilterChip((providerId ?: providers.firstOrNull()?.id) == provider.id, { providerId = provider.id }, label = { Text(provider.name) }) }
        }
        Button(enabled = providers.isNotEmpty() && project.sampleRevision > 0, onClick = { vm.create(project, providers.firstOrNull { it.id == providerId } ?: providers.first(), open) }, modifier = Modifier.padding(horizontal = 16.dp)) { Text("新建会话") }
        if (providers.isEmpty()) EmptyState("连接你的 AI", "选择供应商并填写 API Key，模型列表会自动获取。之后直接描述想实现的效果。", "连接 AI", { configureModel = true })
        else if (project.sampleRevision == 0L) {
            if (preparing) LoadingState(sampleStatus)
            else EmptyState("还没准备好分析样本", sampleStatus, "重新准备", { scope.launch {
                preparing = true
                try { vm.graph.samples.capture(project); sampleStatus = "" } catch (e: Exception) { sampleStatus = e.message ?: "样本准备失败" } finally { preparing = false }
            } })
        }
        else if (conversations.isEmpty()) EmptyState("开始项目对话", "会话固定样本版本，每轮发送时绑定所选目标进程。")
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(conversations, key = { it.id }) { item -> AppCard {
                Column(Modifier.fillMaxWidth().clickable { open(item.id) }) {
                    Text(item.title, style = MaterialTheme.typography.titleMedium)
                    Text("${item.model} · 样本 r${item.sampleRevision}", style = MaterialTheme.typography.bodySmall)
                    Text(SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(item.updatedAt)), style = MaterialTheme.typography.labelSmall)
                }
                Row { TextButton(onClick = { renameId = item.id; name = item.title }) { Text("重命名") }; TextButton(enabled = active != item.id, onClick = { deleteId = item.id }) { Text("删除") }; if (active == item.id) StatusBadge("正在响应", true) }
            } }
        }
    }
    val newProvider = remember(configureModel) { ProviderEntity(UUID.randomUUID().toString(), "OpenAI", "openai", "", "", maxSteps = 0, timeoutSeconds = 300) }
    if (configureModel) ProviderEditor(androidx.lifecycle.viewmodel.compose.viewModel(), newProvider) { configureModel = false }
    if (renameId != null) AlertDialog(onDismissRequest = { renameId = null }, title = { Text("会话名称") }, text = { OutlinedTextField(name, { name = it }) }, confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { vm.rename(requireNotNull(renameId), name); renameId = null }) { Text("保存") } })
    if (deleteId != null) AlertDialog(onDismissRequest = { deleteId = null }, title = { Text("删除此会话？") }, text = { Text("同时删除它的消息、工具记录与计划项。") }, confirmButton = { TextButton(onClick = { vm.delete(project.id, requireNotNull(deleteId)); deleteId = null }) { Text("删除") } }, dismissButton = { TextButton(onClick = { deleteId = null }) { Text("取消") } })
}

fun usageLabel(raw: String): String {
    val summary = summarizeUsage(listOf(ChatUsageEntity("", 0, "", "", "", 0, raw)))
    return "输入总量 ${summary.totalInput.label()} · 输入（未缓存）${summary.input.label()} · 输出 ${summary.output.label()} · 缓存读取 ${summary.cacheRead.label()} · 缓存写入 ${summary.cacheWrite.label()} · 合计 ${summary.total.label()}"
}
internal fun toolName(name: String): String = mapOf("build_native_plugin" to "构建 Native 插件", "enable_native_plugin" to "发布 Native 插件", "disable_native_plugin" to "停用 Native 插件", "get_native_plugin_status" to "查询 Native 运行状态", "start_native_diagnostic" to "采集启动诊断", "read_native_diagnostic" to "读取启动诊断", "list_native_diagnostics" to "列出启动诊断", "stop_native_diagnostic" to "结束启动诊断", "prepare_native_build_tools" to "准备 Native 构建工具", "prepare_native_tools" to "准备 Native 分析工具", "list_native_modules" to "列出 Native 模块", "inspect_native_module" to "检查 Native 模块", "symbolize_native_address" to "解析 Native 地址", "disassemble_native" to "查看 Native 汇编", "start_target" to "启动目标应用", "run_linux" to "执行 Linux 命令", "inspect_target" to "检查目标", "plugin_action" to "操作插件", "read_development_reference" to "读取开发资料", "loadPlugin" to "加载插件", "reloadPlugin" to "重载插件", "unloadPlugin" to "卸载插件", "getPluginState" to "查询加载状态", "inspectOverlay" to "选择界面", "performanceRead" to "读取性能采样", "export_project_logs" to "导出项目日志", "search_classes" to "查询类", "find_method" to "查询方法", "find_field" to "查询字段", "search_strings" to "查询字符串", "decompile_class" to "查看 Java", "get_method_smali" to "查看 Smali", "find_references" to "查询引用", "read_manifest" to "查看 Manifest", "list_resources" to "列出资源", "read_resource" to "读取资源", "create_plugin" to "创建插件", "edit_plugin" to "保存插件", "load_plugin" to "加载插件", "reload_plugin" to "重载插件", "unload_plugin" to "卸载插件", "read_plugin_logs" to "读取日志", "read_tool_output" to "读取工具输出", "run_command" to "执行命令", "read_execution" to "读取执行输出", "get_project_info" to "读取项目信息", "get_plugin_status" to "读取插件状态", "list_plugins" to "列出插件", "read_plugin" to "读取插件", "update_plan" to "更新计划", "viewTree" to "读取 View 树", "memory" to "读取内存", "performanceStart" to "开始性能采样", "performanceStop" to "停止性能采样", "creationStacks" to "设置创建堆栈", "get_root_status" to "读取 root 状态", "export_plugin_apk" to "导出独立 APK", "list_skills" to "列出 Skills", "read_skill" to "读取 Skill", "run_skill" to "执行 Skill", "spawn_agent" to "派生只读子任务", "frida_attach" to "Frida 附加", "write_stdin" to "写入终端输入", "stop_execution" to "停止执行")[name] ?: name
internal fun statusName(state: String) = when (state) { "COMPLETE" -> "已完成"; "RUNNING", "STREAMING" -> "进行中"; "ERROR" -> "失败"; "UNKNOWN" -> "结果待确认"; "AWAITING_APPROVAL" -> "等待授权"; "TIMEOUT" -> "执行超时"; "CANCELLED" -> "已停止"; else -> "已中断" }

