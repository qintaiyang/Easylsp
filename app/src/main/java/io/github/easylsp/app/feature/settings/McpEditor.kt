package io.github.easylsp.app.feature.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import io.github.easylsp.app.MainViewModel
import io.github.easylsp.app.data.McpServerEntity
import kotlinx.coroutines.*
import org.json.*

@Composable fun McpEditor(vm: MainViewModel, item: McpServerEntity, close: () -> Unit) {
    val graph = vm.graph
    val initial = remember(item.id) { JSONObject(item.config) }
    var id by remember(item.id) { mutableStateOf<String?>(null) }
    var name by remember { mutableStateOf(item.name) }; var url by remember { mutableStateOf(item.url) }; var transport by remember { mutableStateOf(item.transport) }
    var token by remember { mutableStateOf("") }; var oauth by remember { mutableStateOf(initial.optBoolean("oauth")) }
    var command by remember { mutableStateOf(initial.optString("command")) }; var args by remember { mutableStateOf(initial.optJSONArray("args")?.toString() ?: "[]") }
    var cwd by remember { mutableStateOf(initial.optString("cwd")) }; var env by remember { mutableStateOf(initial.optJSONObject("envRefs")?.toString() ?: "{}") }
    var timeout by remember { mutableStateOf(initial.optInt("timeoutSeconds", 60).toString()) }
    var status by remember { mutableStateOf("") }; var tools by remember { mutableStateOf(JSONArray()) }
    var disabled by remember { mutableStateOf(initial.optJSONArray("disabledTools")?.let { array -> (0 until array.length()).map { array.getString(it) }.toSet() } ?: emptySet()) }
    LaunchedEffect(item.id) { id = withContext(Dispatchers.IO) { graph.store.workflows.server(item.id)?.id } }
    suspend fun save(): String = withContext(Dispatchers.IO) {
        val patch = JSONObject().put("name", name).put("url", url).put("transport", transport).put("oauth", oauth).put("timeoutSeconds", timeout.toInt())
            .put("command", command).put("args", JSONArray(args)).put("cwd", cwd).put("envRefs", JSONObject(env)).put("disabledTools", JSONArray(disabled))
        val row = graph.mcp.configure(id, patch); id = row.id
        if (token.isNotEmpty()) graph.secrets.put("mcp:${row.id}", token)
        row.id
    }
    EditorDialog("MCP 服务", close, { vm.action { save(); close(); "MCP 配置已保存" } }) {
        Field("名称", name) { name = it }
        Row { listOf("http", "sse", "stdio").forEach { type -> FilterChip(transport == type, { transport = type }, label = { Text(type) }) } }
        if (transport == "stdio") {
            Field("Ubuntu 中的命令", command) { command = it }; Field("args（JSON 数组）", args) { args = it }; Field("cwd", cwd) { cwd = it }
            Field("环境凭据引用 envRefs（JSON）", env) { env = it }
        } else {
            Field("endpoint", url) { url = it }
            OutlinedTextField(token, { token = it }, label = { Text("Bearer Token（留空保留）") }, visualTransformation = PasswordVisualTransformation())
            ListItem(headlineContent = { Text("OAuth 登录") }, trailingContent = { Switch(oauth, { oauth = it }) })
        }
        Field("调用超时（秒）", timeout) { timeout = it }
        Row {
            TextButton(onClick = { vm.action { val serverId = save(); status = graph.mcp.request(serverId, "status").toString(2); "已读取连接状态" } }) { Text("测试连接") }
            TextButton(onClick = { vm.action { val serverId = save(); tools = graph.mcp.request(serverId, "tools").getJSONArray("tools"); "目录已刷新" } }) { Text("刷新工具") }
        }
        if (oauth && transport != "stdio") Row {
            TextButton(onClick = { vm.action {
                val serverId = save(); val result = graph.mcp.request(serverId, "login"); status = result.toString(2)
                result.optString("authorizationURL").takeIf { it.isNotEmpty() }?.let { graph.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                "OAuth 状态已更新"
            } }) { Text("登录") }
            TextButton(onClick = { vm.action { val serverId = requireNotNull(id); graph.mcp.request(serverId, "logout"); graph.secrets.delete("mcp-oauth:$serverId"); "已退出登录" } }) { Text("退出登录") }
        }
        for (i in 0 until tools.length()) { val tool = tools.getJSONObject(i); val toolName = tool.getString("name")
            ListItem(headlineContent = { Text(toolName) }, supportingContent = { Text(tool.optString("description")) }, trailingContent = { Switch(toolName !in disabled, { enabled -> disabled = if (enabled) disabled - toolName else disabled + toolName }) })
        }
        if (status.isNotBlank()) Text(status)
    }
}
