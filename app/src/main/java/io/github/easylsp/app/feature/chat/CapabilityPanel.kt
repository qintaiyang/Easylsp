package io.github.easylsp.app.feature.chat

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.easylsp.app.EasylspApp
import io.github.easylsp.app.data.ConversationEntity
import kotlinx.coroutines.*
import org.json.*

@Composable internal fun CapabilityPanel(graph: EasylspApp, conversation: ConversationEntity, kind: String, close: () -> Unit, openConversation: (String) -> Unit) {
    var query by remember { mutableStateOf("") }
    var rows by remember { mutableStateOf(emptyList<JSONObject>()) }
    var snapshot by remember { mutableStateOf(JSONObject()) }
    var cursor by remember { mutableIntStateOf(0) }
    var next by remember { mutableStateOf<Int?>(null) }
    var revision by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    LaunchedEffect(kind, query, cursor, revision) {
        android.util.Log.d("CapabilityPanel", "Load $kind for ${conversation.id}")
        try {
            val page = withContext(Dispatchers.IO) {
                when (kind) {
                    "skills" -> { val result = graph.skillPackages.list(conversation.projectId, JSONObject().put("query", query).put("cursor", cursor).put("limit", 30)); Triple(result.getJSONArray("skills"), graph.chat.selection(conversation.id).snapshot(), if (result.isNull("nextCursor")) null else result.getInt("nextCursor")) }
                    "tools" -> { val caps = graph.chat.selection(conversation.id); val result = caps.search(JSONObject().put("query", query).put("cursor", cursor).put("limit", 30)); Triple(result.getJSONArray("tools"), caps.snapshot(), if (result.isNull("nextCursor")) null else result.getInt("nextCursor")) }
                    "agents" -> Triple(graph.agents.list(conversation.id).getJSONArray("agents"), JSONObject(), null)
                    else -> error("未知能力面板：$kind")
                }
            }
            withContext(Dispatchers.Main.immediate) {
                rows = (0 until page.first.length()).map { page.first.getJSONObject(it) }
                snapshot = page.second
                next = page.third
                android.util.Log.d("CapabilityPanel", "Loaded $kind for ${conversation.id}: ${rows.size} rows")
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message.orEmpty() }
    }
    AlertDialog(onDismissRequest = close, title = { Text(when (kind) { "skills" -> "Skills"; "tools" -> "工具选择"; else -> "子代理" }) }, text = {
        SideEffect { android.util.Log.d("CapabilityPanel", "Rendered $kind for ${conversation.id}: ${rows.size} rows") }
        Column(Modifier.heightIn(max = 500.dp).verticalScroll(rememberScrollState())) {
            OutlinedTextField(query, { query = it; cursor = 0 }, label = { Text("搜索") })
            for (row in rows) {
                when (kind) {
                    "skills" -> {
                        val id = row.getString("id"); val selected = snapshot.optJSONObject("skills")?.has(id) == true
                        ListItem(headlineContent = { Text(row.getString("name")) }, supportingContent = { Text(row.getString("description")) }, trailingContent = { Checkbox(selected, { enabled -> scope.launch {
                            try { withContext(Dispatchers.IO) { graph.chat.pinSkill(conversation.id, id, enabled) }; revision++ } catch (e: Exception) { error = e.message.orEmpty() }
                        } }) })
                    }
                    "tools" -> {
                        val name = row.getString("name"); val available = row.getJSONObject("availability").getBoolean("available")
                        ListItem(headlineContent = { Text(name) }, supportingContent = { Text(if (available) row.getString("description") else row.getJSONObject("availability").optString("reason")) }, trailingContent = { Checkbox(row.getBoolean("selected"), { selected -> scope.launch {
                            withContext(Dispatchers.IO) { graph.chat.selection(conversation.id).select(JSONObject().put("names", JSONArray().put(name)).put("mode", if (selected) "add" else "remove")) }; revision++
                        } }, enabled = available) })
                    }
                    "agents" -> {
                        TextButton(onClick = { openConversation(row.getString("agentId")); close() }) { Text("${row.getString("title")} · ${row.getString("status")}") }
                        TextButton(onClick = { scope.launch { graph.agents.stop(conversation.id, row.getString("agentId")); revision++ } }) { Text("停止此任务") }
                    }
                }
            }
            if (next != null) TextButton(onClick = { cursor = next!! }) { Text("下一页") }
            if (cursor > 0) TextButton(onClick = { cursor = 0 }) { Text("返回首页") }
            if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
        }
    }, confirmButton = { TextButton(onClick = close) { Text("关闭") } })
}
