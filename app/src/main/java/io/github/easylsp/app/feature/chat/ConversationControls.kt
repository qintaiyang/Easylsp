package io.github.easylsp.app.feature.chat

import io.github.easylsp.app.util.readUpTo
import android.app.Activity
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.easylsp.app.EasylspApp
import io.github.easylsp.app.data.ConversationEntity
import io.github.easylsp.app.platform.ai.RunModes
import kotlinx.coroutines.*
import org.json.*

@Composable fun ConversationControls(graph: EasylspApp, conversation: ConversationEntity, busy: Boolean, speak: (String) -> Unit, attachments: List<JSONObject>, attach: (List<JSONObject>) -> Unit, open: (String, String, String) -> Unit) {
    val scope = rememberCoroutineScope()
    val prompts by graph.store.workflows.observePrompts().collectAsState(initial = emptyList())
    var advanced by remember { mutableStateOf(false) }
    var choosing by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    fun configure(mode: String, promptId: String = conversation.promptId, text: String = conversation.promptText) {
        scope.launch(Dispatchers.IO) { graph.store.workflows.configureConversation(conversation.id, mode, promptId, text) }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> scope.launch {
        loading = true
        try {
            val provider = withContext(Dispatchers.IO) { graph.models.resolve(graph.models.selected(conversation)) }
            val parts = withContext(Dispatchers.IO) { uris.map { uri ->
                val mime = graph.contentResolver.getType(uri) ?: "text/plain"
                val bytes = requireNotNull(graph.contentResolver.openInputStream(uri)).use { stream -> stream.readUpTo(5 * 1024 * 1024 + 1) }
                require(bytes.size <= 5 * 1024 * 1024) { "单个附件最多 5 MiB" }
                if (mime.startsWith("image/")) {
                    require(JSONObject(provider.options).optBoolean("supportsImages")) { "请在模型高级参数中确认此模型支持图片" }
                    JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", "data:$mime;base64," + android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)))
                } else {
                    val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    val text = decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString()
                    JSONObject().put("type", "text").put("text", "附件 ${uri.lastPathSegment}\n$text")
                }
            } }
            require(attachments.size + parts.size <= 6) { "每轮最多 6 个附件" }
            attach(attachments + parts); status = ""
        } catch (e: CancellationException) { throw e } catch (e: Exception) { status = e.message ?: "附件读取失败" } finally { loading = false }
    } }
    val speech = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let(speak)
    }
    Box {
        TextButton(onClick = { advanced = true }, contentPadding = PaddingValues(4.dp)) { Text("＋", style = MaterialTheme.typography.titleLarge) }
        DropdownMenu(advanced, { advanced = false }) {
            DropdownMenuItem(text = { Text(if (loading) "读取附件中…" else "添加附件") }, enabled = !loading, onClick = { advanced = false; picker.launch(arrayOf("text/*", "application/json", "application/xml", "image/*")) })
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM).putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
            if (intent.resolveActivity(graph.packageManager) != null) DropdownMenuItem(text = { Text("语音输入") }, onClick = { advanced = false; speech.launch(intent) })
            RunModes.names.forEach { (id, label) -> DropdownMenuItem(text = { Text(label + if (conversation.mode == id) " ✓" else "") }, enabled = !busy, onClick = { configure(id); advanced = false }) }
            DropdownMenuItem(text = { Text("Prompt 预设") }, enabled = !busy, onClick = { choosing = true; advanced = false })
        }
    }
    if (status.isNotEmpty()) AlertDialog(onDismissRequest = { status = "" }, text = { Text(status) }, confirmButton = { TextButton(onClick = { status = "" }) { Text("知道了") } })
    if (choosing) AlertDialog(onDismissRequest = { choosing = false }, title = { Text("选择 Prompt 预设") }, text = {
        Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
            Text("选择后保存到会话，运行中的请求继续使用原快照。", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { configure(conversation.mode, "", ""); choosing = false }) { Text("默认提示词") }
            prompts.forEach { prompt -> TextButton(onClick = { configure(conversation.mode, prompt.id, prompt.text); choosing = false }) { Text(prompt.name) } }
        }
    }, confirmButton = { TextButton(onClick = { choosing = false }) { Text("关闭") } })
}
