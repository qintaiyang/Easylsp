package io.github.easylsp.app.feature.settings

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.easylsp.app.MainViewModel
import io.github.easylsp.app.data.*
import io.github.easylsp.app.platform.ai.*
import kotlinx.coroutines.*
import org.json.*
import java.util.UUID

@Composable fun ProviderEditor(vm: MainViewModel, item: ProviderEntity, close: () -> Unit) {
    val graph = vm.graph
    val initial = remember(item.id) { JSONObject(item.options) }
    var name by remember(item.id) { mutableStateOf(item.name) }
    var protocol by remember(item.id) { mutableStateOf(item.protocol) }
    var url by remember(item.id) { mutableStateOf(item.baseUrl.ifBlank { ProviderPresets.all.first().url }) }
    var enabled by remember(item.id) { mutableStateOf(item.enabled) }
    var key by remember(item.id) { mutableStateOf("") }
    var storedKey by remember { mutableStateOf("") }
    var requestPath by remember(item.id) { mutableStateOf(initial.optString("requestPath", "/chat/completions")) }
    var modelsPath by remember(item.id) { mutableStateOf(initial.optString("modelsPath", "/models")) }
    var timeout by remember(item.id) { mutableStateOf(item.timeoutSeconds.toString()) }
    var configured by remember(item.id) { mutableStateOf<List<ProviderModelEntity>>(emptyList()) }
    var remote by remember { mutableStateOf<List<RemoteModel>>(emptyList()) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var manual by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf("") }
    var fetching by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(item.id) {
        withContext(Dispatchers.IO) { graph.models.migrateConnections(); storedKey = graph.secrets.get(graph.models.credential(item)); configured = graph.store.workflows.models().filter { it.providerId == item.id } }
    }
    fun connection() = item.copy(name = name, protocol = protocol, baseUrl = url.trimEnd('/'), model = "", enabled = enabled,
        timeoutSeconds = timeout.toInt(), options = JSONObject().put("requestPath", requestPath).put("modelsPath", modelsPath).toString())
    fun addModels(ids: Set<String>) {
        configured = configured + ids.filter { id -> configured.none { it.remoteModelId == id } }.map { id ->
            val capacity = remote.firstOrNull { it.id == id }?.contextWindow ?: 0
            ProviderModelEntity(UUID.randomUUID().toString(), item.id, id, id, options = JSONObject().apply { if (capacity > 0) { put("contextWindow", capacity); put("contextBudget", capacity) } }.toString())
        }
        selected = emptySet(); manual = ""
    }
    AlertDialog(onDismissRequest = close, title = { Text("供应商连接") }, text = {
        Column(Modifier.heightIn(max = 620.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.horizontalScroll(rememberScrollState())) { ProviderPresets.all.forEach { preset -> FilterChip(url == preset.url, {
                name = preset.name; protocol = preset.protocol; url = preset.url; requestPath = preset.requestPath; modelsPath = preset.modelsPath; remote = emptyList()
            }, label = { Text(preset.name) }) } }
            Field("连接名称", name) { name = it }; Field("API 基础地址", url) { url = it }
            Row { listOf("openai", "anthropic", "gemini").forEach { p -> FilterChip(protocol == p, { protocol = p; requestPath = when (p) { "anthropic" -> "/messages"; "gemini" -> "/models/{model}:streamGenerateContent?alt=sse"; else -> "/chat/completions" } }, label = { Text(p) }) } }
            OutlinedTextField(key, { key = it }, label = { Text(if (storedKey.isBlank()) "API Key" else "API Key（已保存，留空保留）") }, visualTransformation = PasswordVisualTransformation(), singleLine = true)
            Field("请求路径", requestPath) { requestPath = it }; Field("模型目录路径", modelsPath) { modelsPath = it }; Field("请求超时（秒）", timeout) { timeout = it }
            ListItem(headlineContent = { Text("启用连接") }, trailingContent = { Switch(enabled, { enabled = it }) })
            TextButton(enabled = !fetching, onClick = { scope.launch {
                fetching = true
                try { remote = ProviderModels().list(connection(), key.ifEmpty { storedKey }); status = "已获取 ${remote.size} 个模型" }
                catch (e: CancellationException) { throw e } catch (e: Exception) { status = e.message.orEmpty() } finally { fetching = false }
            } }) { Text("刷新远端目录") }
            TextButton(enabled = !fetching && configured.isNotEmpty(), onClick = { scope.launch {
                fetching = true
                try {
                    val model = configured.first { it.enabled }
                    val resolved = connection().copy(model = model.remoteModelId, options = JSONObject(model.options).put("requestPath", requestPath).put("modelsPath", modelsPath).toString(), maxSteps = model.maxSteps)
                    val reply = ModelTransport().stream(resolved, key.ifEmpty { storedKey }, JSONArray().put(JSONObject().put("role", "user").put("content", "Reply OK.")), JSONArray()) {}
                    status = "${model.remoteModelId}：${reply.text}"
                } catch (e: CancellationException) { throw e } catch (e: Exception) { status = e.message.orEmpty() } finally { fetching = false }
            } }) { Text("测试首个启用模型") }
            if (status.isNotEmpty()) Text(status)
            Field("搜索模型", query) { query = it }
            if (remote.isNotEmpty()) {
                Column(Modifier.heightIn(max = 180.dp).verticalScroll(rememberScrollState())) {
                    remote.filter { (it.id + it.name).contains(query, true) }.forEach { model ->
                        val added = configured.any { it.remoteModelId == model.id }
                        Row(Modifier.fillMaxWidth().clickable(enabled = !added) { selected = if (model.id in selected) selected - model.id else selected + model.id }) {
                            Checkbox(added || model.id in selected, null, enabled = !added)
                            Column { Text(model.id); Text(if (model.contextWindow > 0) "容量 ${model.contextWindow}" else "容量待填写", style = MaterialTheme.typography.labelSmall) }
                        }
                    }
                }
                TextButton(enabled = selected.isNotEmpty(), onClick = { addModels(selected) }) { Text("添加所选 ${selected.size} 个模型") }
            }
            Field("手动模型 ID", manual) { manual = it }
            TextButton(enabled = manual.isNotBlank(), onClick = { addModels(setOf(manual.trim())) }) { Text("添加模型") }
            configured.filter { (it.displayName + it.remoteModelId).contains(query, true) }.forEach { model ->
                ListItem(headlineContent = { Text(model.displayName) }, supportingContent = { Text(model.remoteModelId) }, modifier = Modifier.clickable { editing = model.id },
                    trailingContent = { Switch(model.enabled, { value -> configured = configured.map { if (it.id == model.id) it.copy(enabled = value) else it } }) })
            }
        }
    }, confirmButton = { TextButton(onClick = { vm.action {
        val c = connection()
        withContext(Dispatchers.IO) {
            val provider = graph.models.configureProvider(item.id.takeIf { graph.store.workflows.provider(it) != null }, JSONObject(c.options)
                .put("name", c.name).put("protocol", c.protocol).put("baseUrl", c.baseUrl).put("enabled", c.enabled).put("timeoutSeconds", c.timeoutSeconds))
            if (key.isNotEmpty()) graph.secrets.put(graph.models.credential(provider), key)
            configured.forEach { model -> graph.models.configureModel(model.id.takeIf { graph.store.workflows.model(it) != null }, provider.id, model.remoteModelId,
                JSONObject(model.options).put("displayName", model.displayName).put("enabled", model.enabled).put("maxSteps", model.maxSteps), replaceOptions = true) }
        }
        close(); "已保存 1 个连接与 ${configured.size} 个模型"
    } }) { Text("保存") } }, dismissButton = { TextButton(onClick = close) { Text("取消") } })
    configured.firstOrNull { it.id == editing }?.let { model -> ModelParameterEditor(model, { editing = null }) { updated -> configured = configured.map { if (it.id == updated.id) updated else it }; editing = null } }
}

@Composable private fun ModelParameterEditor(model: ProviderModelEntity, close: () -> Unit, save: (ProviderModelEntity) -> Unit) {
    val initial = remember(model.id) { JSONObject(model.options) }
    var name by remember { mutableStateOf(model.displayName) }
    var capacity by remember { mutableStateOf(initial.optString("contextWindow")) }
    var contextBudget by remember { mutableStateOf(initial.optString("contextBudget")) }
    var output by remember { mutableStateOf(initial.optString("maxOutputTokens")) }
    var summary by remember { mutableStateOf(initial.optString("summaryMaxTokens")) }
    var effort by remember { mutableStateOf(initial.optString("reasoningEffort")) }
    var thinking by remember { mutableStateOf(initial.optString("thinkingBudget")) }
    var tokenField by remember { mutableStateOf(initial.optString("outputTokenField")) }
    var steps by remember { mutableStateOf(model.maxSteps.toString()) }
    var parallel by remember { mutableStateOf(initial.optString("maxParallelToolCalls")) }
    var images by remember { mutableStateOf(initial.optBoolean("supportsImages")) }
    var error by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = close, title = { Text(model.remoteModelId) }, text = {
        Column(Modifier.heightIn(max = 500.dp).verticalScroll(rememberScrollState())) {
            Field("显示名称", name) { name = it }; Field("实际上下文容量（待填写）", capacity) { capacity = it }; Field("上下文预算（留空使用容量）", contextBudget) { contextBudget = it }
            Field("回答输出上限（留空按容量分配）", output) { output = it }; Field("压缩摘要上限（留空 16384）", summary) { summary = it }
            Field("每轮请求上限（0 不限）", steps) { steps = it }; Field("工具并发（留空按设备资源）", parallel) { parallel = it }
            Field("reasoning_effort", effort) { effort = it }; Field("思考预算", thinking) { thinking = it }; Field("输出字段 max_tokens / max_completion_tokens", tokenField) { tokenField = it }
            ListItem(headlineContent = { Text("支持图片") }, trailingContent = { Switch(images, { images = it }) })
            if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
        }
    }, confirmButton = { TextButton(onClick = {
        try {
            val options = JSONObject().put("supportsImages", images)
            mapOf("contextWindow" to capacity, "contextBudget" to contextBudget, "maxOutputTokens" to output, "summaryMaxTokens" to summary, "thinkingBudget" to thinking, "maxParallelToolCalls" to parallel).forEach { (key, text) -> if (text.isNotBlank()) options.put(key, text.toInt().also { require(it > 0) }) }
            if (effort.isNotBlank()) options.put("reasoningEffort", effort)
            if (tokenField.isNotBlank()) options.put("outputTokenField", tokenField)
            require(steps.toInt() >= 0)
            save(model.copy(displayName = name, options = options.toString(), maxSteps = steps.toInt()))
        } catch (e: Exception) { error = e.message ?: "参数格式错误" }
    }) { Text("保存参数") } }, dismissButton = { TextButton(onClick = close) { Text("取消") } })
}
