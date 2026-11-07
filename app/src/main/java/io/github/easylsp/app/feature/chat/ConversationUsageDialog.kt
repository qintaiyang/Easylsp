package io.github.easylsp.app.feature.chat

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.easylsp.app.EasylspApp
import io.github.easylsp.app.data.*
import io.noties.markwon.Markwon
import kotlinx.coroutines.*
import org.json.JSONObject

@Composable internal fun ConversationUsageDialog(conversation: ConversationEntity, sessionId: String?, state: String,
    turns: List<ChatTurnEntity>, usage: List<ChatUsageEntity>, plan: List<PlanItemEntity>, branches: List<ChatBranchEntity>,
    selectedTurn: String?, dismiss: () -> Unit, setMode: (Boolean) -> Unit, openBranch: (ChatBranchEntity) -> Unit, historyAccess: (Boolean) -> Unit = {}, globalDefault: () -> Unit = {}, projectDefault: (() -> Unit)? = null, openTerminal: () -> Unit = {}, descendantUsage: List<ChatUsageEntity> = emptyList()) {
    var settings by remember { mutableStateOf(false) }
    var history by remember { mutableStateOf(false) }
    var showPlan by remember { mutableStateOf(false) }
    val turn = turns.firstOrNull { it.id == selectedTurn } ?: turns.lastOrNull()
    val current = usage.filter { it.sessionId == sessionId }
    AlertDialog(onDismissRequest = dismiss, title = { Text("会话用量与设置") }, confirmButton = { TextButton(onClick = dismiss) { Text("关闭") } }, text = {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(conversation.model, style = MaterialTheme.typography.titleSmall)
            Text(state.ifBlank { turn?.let { statusName(it.status) } ?: "尚未开始" })
            Text(if (selectedTurn != null) "所选轮次" else "当前轮 / 最近一轮", style = MaterialTheme.typography.titleSmall)
            UsageDetails(summarizeUsage(current.filter { it.turnId == turn?.id }, turn?.requestCount ?: 0))
            turn?.let { Text("整轮耗时 ${((it.finishedAt ?: System.currentTimeMillis()) - it.startedAt) / 1000} 秒 · ${it.requestCount} 次模型请求") }
            HorizontalDivider()
            val inherited = current.filter { !it.originKey.startsWith("$sessionId:") }
            if (inherited.isNotEmpty()) {
                Text("继承上下文的历史用量", style = MaterialTheme.typography.titleSmall); UsageDetails(summarizeUsage(inherited))
                Text("本分支新增消耗", style = MaterialTheme.typography.titleSmall); UsageDetails(summarizeUsage(current - inherited.toSet()))
            }
            Text("当前分支累计", style = MaterialTheme.typography.titleSmall)
            UsageDetails(summarizeUsage(current, turns.sumOf { it.requestCount }))
            if (branches.isNotEmpty()) {
                Text("含修改历史的实际消耗", style = MaterialTheme.typography.titleSmall)
                UsageDetails(summarizeUsage(usage))
                Text("继承前缀只计一次；回退不会撤销已发生的消耗。", style = MaterialTheme.typography.labelSmall)
            }
            if (descendantUsage.isNotEmpty()) {
                Text("子任务新增消耗（含多层委派）", style = MaterialTheme.typography.titleSmall)
                val inheritedOrigins = usage.mapTo(mutableSetOf()) { it.originKey }
                UsageDetails(summarizeUsage(descendantUsage.filter { it.originKey !in inheritedOrigins }))
                Text("主任务与子任务合计", style = MaterialTheme.typography.titleSmall)
                UsageDetails(summarizeUsage(usage + descendantUsage))
                Text("继承的历史前缀按来源去重，不计为子任务的新消耗。", style = MaterialTheme.typography.labelSmall)
            }
            Text("token 表示处理量。仅汇总引擎实际记录，未记录的压缩请求或供应商账单不估算。", style = MaterialTheme.typography.labelSmall)
            if (plan.isNotEmpty()) {
                TextButton(onClick = { showPlan = !showPlan }) { Text("任务计划 ${plan.count { it.status == "completed" }}/${plan.size}") }
                if (showPlan) plan.forEach { Text("${it.status} · ${it.description}") }
            }
            TextButton(onClick = openTerminal) { Text("打开工作区 / 终端") }
            TextButton(onClick = { settings = !settings }) { Text("会话设置 · " + if (conversation.authorizationMode == "ALLOW") "允许修改并运行" else "单独确认") }
            if (settings) {
                TextButton(onClick = { setMode(true) }) { Text("允许修改并运行") }
                TextButton(onClick = { setMode(false) }) { Text("单独确认每次操作") }
                ListItem(headlineContent = { Text("允许查询全部项目历史") }, supportingContent = { Text("默认仅访问当前作用域；授权后仍由模型按需读取。") }, trailingContent = { Switch(conversation.historyAccess == "authorized", historyAccess) })
                TextButton(onClick = globalDefault) { Text("将当前模型设为全局默认") }
                projectDefault?.let { TextButton(onClick = it) { Text("设为当前项目默认模型") } }
            }
            if (branches.isNotEmpty()) {
                TextButton(onClick = { history = !history }) { Text("修改历史（${branches.size}）") }
                if (history) branches.forEachIndexed { index, branch -> TextButton(onClick = { openBranch(branch) }) { Text("修改记录 ${branches.size - index} · 只读查看原分支") } }
            }
        }
    })
}

@Composable private fun UsageDetails(summary: UsageSummary) {
    Text("输入总量 ${summary.totalInput.label()}\n未缓存输入 ${summary.input.label()}\n输出 ${summary.output.label()}\n缓存读取 ${summary.cacheRead.label()}\n缓存写入 ${summary.cacheWrite.label()}\n缓存命中率 ${summary.rateLabel()}\n总计 ${summary.total.label()}")
    Text("${summary.requests} 次请求 · ${summary.knownRequests} 次有已知用量" + if (summary.incomplete) " · 统计不完整" else "", style = MaterialTheme.typography.labelSmall)
}

@Composable internal fun BranchHistoryDialog(branch: ChatBranchEntity, conversation: ConversationEntity, graph: EasylspApp,
    markdown: Markwon, dismiss: () -> Unit, openOutput: (String, String, String) -> Unit, openPlugin: (String) -> Unit) {
    val dao = graph.store.workflows
    val messages by remember(branch.id) { dao.messages(branch.id) }.collectAsStateWithLifecycle(emptyList())
    val turns by remember(branch.id) { dao.turns(branch.id) }.collectAsStateWithLifecycle(emptyList())
    val runs by remember(branch.id) { dao.toolRuns(branch.id) }.collectAsStateWithLifecycle(emptyList())
    val interactions by remember(branch.id) { dao.interactions(branch.id) }.collectAsStateWithLifecycle(emptyList())
    val timeline = remember(messages, turns, interactions) { chatTimeline(messages, turns, interactions) }
    var tools by remember { mutableStateOf(emptyList<String>()) }; var groups by remember { mutableStateOf(emptyList<String>()) }
    androidx.compose.ui.window.Dialog(onDismissRequest = dismiss) {
        Surface(shape = MaterialTheme.shapes.large) {
            Column(Modifier.fillMaxWidth().fillMaxHeight(.9f).padding(16.dp)) {
                Text("修改历史 · 原分支（只读）", style = MaterialTheme.typography.titleMedium)
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(timeline, key = { it.key }) { item -> when (item) {
                        is ChatTimelineItem.Message -> Column {
                            Text(if (item.row.role == "user") "你" else "助手", style = MaterialTheme.typography.labelSmall)
                            MarkdownMessage(item.row.content, markdown) { language, code -> openOutput("代码", code, language) }
                            val count = messageAttachments(item.row).length()
                            if (count > 0) Text("附件 $count", style = MaterialTheme.typography.labelSmall)
                        }
                        is ChatTimelineItem.Tools -> ToolRows(conversation, item, runs, graph, openPlugin, tools, { tools = it }, groups, { groups = it })
                        is ChatTimelineItem.Outcome -> Text(statusName(item.turn.status), style = MaterialTheme.typography.labelSmall)
                        is ChatTimelineItem.Interaction -> Text("${if (item.row.kind == "APPROVAL") "操作确认" else "提问"} · ${item.row.status}\n${item.row.request}\n${item.row.response}", style = MaterialTheme.typography.bodySmall)
                    } }
                }
                TextButton(onClick = dismiss) { Text("关闭") }
            }
        }
    }
}
