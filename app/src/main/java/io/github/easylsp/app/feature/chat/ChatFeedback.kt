package io.github.easylsp.app.feature.chat

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp

internal fun chatErrorSummary(detail: String): String = when {
    detail.contains("easylsp/limit") || detail.contains("refusing to interpret") -> "会话历史加载失败，已有内容仍保留"
    detail.contains("no_available_accounts", true) -> "模型服务容量不足，暂无可用账号，可稍后继续"
    detail.contains("account_concurrency_exceeded", true) -> "模型服务账号并发已满，可稍后继续"
    detail.contains("content_filter", true) || detail.contains("refusal", true) || detail.contains("refused", true) || detail.contains("rejected", true) || detail.contains("内容拒绝") -> "模型服务拒绝了本次请求，可查看具体原因"
    detail.contains("503") -> "模型服务暂时不可用，可稍后继续"
    detail.contains("timeout", true) || detail.contains("timed out", true) || detail.contains("超时") -> "本次模型请求超时，可继续已有工作"
    detail.contains("上限") -> "已达到本次请求上限，可继续已有工作"
    detail.contains("目标") && (detail.contains("断开") || detail.contains("离线")) -> "目标进程已断开，请重新连接目标"
    detail.contains("回执") -> "输入接受状态待确认，请重新连接"
    detail.contains("停止") -> "任务已停止，已有结果保留"
    detail.contains("连接") || detail.contains("fetch failed") -> "会话连接失败，可重新连接"
    else -> "本次请求未完成，可查看详情"
}

@Composable internal fun ChatFeedback(detail: String, action: String, enabled: Boolean = true, onAction: () -> Unit, context: String = "") {
    var expanded by rememberSaveable(detail) { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("⚠ ${chatErrorSummary(detail)}", style = MaterialTheme.typography.bodySmall, maxLines = 2, modifier = Modifier.weight(1f))
            TextButton(enabled = enabled, onClick = onAction) { Text(action) }
            TextButton(onClick = { expanded = !expanded }, contentPadding = PaddingValues(horizontal = 6.dp)) { Text(if (expanded) "收起" else "详情") }
        }
        if (expanded) {
            SelectionContainer { Text(context + detail, style = MaterialTheme.typography.bodySmall, modifier = Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState())) }
            TextButton(onClick = { clipboard.setText(AnnotatedString(context + detail)) }) { Text("复制详情") }
        }
    }
}
