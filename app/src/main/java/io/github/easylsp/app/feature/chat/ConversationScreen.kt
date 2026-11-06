package io.github.easylsp.app.feature.chat

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.easylsp.app.data.*
import io.github.easylsp.app.platform.harness.HarnessSessionClient
import io.github.easylsp.app.ui.components.LoadingState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import org.json.*
import io.noties.markwon.Markwon


@OptIn(FlowPreview::class)
@Composable fun ConversationScreen(project: ProjectEntity?, conversationId: String, sessionId: String?, openOutput: (String, String, String) -> Unit, openPlugin: (String) -> Unit, vm: ChatViewModel = viewModel(), back: (() -> Unit)? = null, openConversation: (String) -> Unit = {}, openTerminal: () -> Unit = {}) {
    val graph = vm.graph; val dao = graph.store.workflows
    val context = LocalContext.current
    val markdown = remember(context) { Markwon.builder(context).build() }
    val conversations by remember { dao.observeConversations() }.collectAsStateWithLifecycle(emptyList())
    val conversationState by remember(conversationId) { dao.observeConversation(conversationId) }.collectAsStateWithLifecycle(null)
    val conversation = conversationState
    var expandedTools by rememberSaveable(conversationId) { mutableStateOf(emptyList<String>()) }
    var expandedGroups by rememberSaveable(conversationId) { mutableStateOf(emptyList<String>()) }
    var pageSize by rememberSaveable(conversationId) { mutableIntStateOf(80) }
    val messages by remember(conversationId, pageSize) { dao.messageWindow(conversationId, pageSize) }.collectAsStateWithLifecycle(emptyList())
    val messageCount by remember(conversationId) { dao.messageCount(conversationId) }.collectAsStateWithLifecycle(0)
    val runs by remember(conversationId, pageSize) { dao.toolRunWindow(conversationId, pageSize) }.collectAsStateWithLifecycle(emptyList())
    val interactions by remember(conversationId, pageSize) { dao.interactionWindow(conversationId, pageSize) }.collectAsStateWithLifecycle(emptyList())
    val providers by remember { graph.models.available() }.collectAsStateWithLifecycle(emptyList())
    val mapping by remember(conversationId) { dao.observeHarnessSession(conversationId) }.collectAsStateWithLifecycle(null)
    var choosingModel by remember { mutableStateOf(false) }
    var headerMenu by remember { mutableStateOf(false) }
    var connectionProvider by remember { mutableStateOf<ProviderEntity?>(null) }
    LaunchedEffect(conversation?.providerId) { connectionProvider = withContext(Dispatchers.IO) { conversation?.providerId?.let(dao::provider) } }
    var managingModel by remember { mutableStateOf(false) }
    val queued by remember(conversationId) { dao.inputs(conversationId) }.collectAsStateWithLifecycle(emptyList())
    val turns by remember(conversationId) { dao.turns(conversationId) }.collectAsStateWithLifecycle(emptyList())
    val plan by remember(conversationId) { dao.planItems(conversationId) }.collectAsStateWithLifecycle(emptyList())
    val active by graph.chat.activeConversation.collectAsStateWithLifecycle()
    val status by graph.chat.status.collectAsStateWithLifecycle()
    val statusConversation by graph.chat.statusConversation.collectAsStateWithLifecycle()
    val maintenance by graph.chat.maintenance.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    var attachments by remember(conversationId) { mutableStateOf(emptyList<JSONObject>()) }
    var input by rememberSaveable(conversationId) { mutableStateOf("") }
    var draftLoaded by remember(conversationId) { mutableStateOf(false) }
    var allow by rememberSaveable(conversationId) { mutableStateOf(false) }
    var choosingAuthorization by remember { mutableStateOf(false) }
    var editedMessageId by rememberSaveable(conversationId) { mutableStateOf<Long?>(null) }
    var branchId by rememberSaveable(conversationId) { mutableStateOf<String?>(null) }
    var selectedTurn by remember { mutableStateOf<String?>(null) }
    var selectedBranch by remember { mutableStateOf<ChatBranchEntity?>(null) }
    val usage by remember(conversationId) { dao.observeUsage(conversationId) }.collectAsStateWithLifecycle(emptyList())
    val descendantUsage by remember(conversationId) { dao.observeDescendantUsage(conversationId) }.collectAsStateWithLifecycle(emptyList())
    val branches by remember(conversationId) { dao.branches(conversationId) }.collectAsStateWithLifecycle(emptyList())
    var commandPanel by remember { mutableStateOf<String?>(null) }
    var commandOutput by remember { mutableStateOf("") }
    var unknownCommand by remember { mutableStateOf(false) }
    var accepting by remember(conversationId) { mutableStateOf(false) }
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val atBottom by remember { derivedStateOf { list.firstVisibleItemIndex == 0 && list.firstVisibleItemScrollOffset < 48 } }
    var reading by remember(conversationId) { mutableStateOf<ChatReadingEntity?>(null) }
    val groupAnchors = remember(conversationId) { mutableMapOf<String, Long>() }
    var readingLoaded by remember(conversationId) { mutableStateOf(false) }
    var readingRestored by remember(conversationId) { mutableStateOf(false) }
    val live by remember(conversationId) { graph.chat.liveAssistant.sample(80) }.collectAsStateWithLifecycle(initialValue = null)
    val shownMessages = remember(messages, live, statusConversation) {
        val current = live?.takeIf { statusConversation == conversationId && messages.none { row -> row.displayKey == it.displayKey } }
        if (current == null || conversation == null) messages else messages + MessageEntity(id = -1, conversationId = conversationId,
            role = "assistant", content = current.text, blocks = JSONObject().put("reasoning", current.reasoning).toString(),
            status = "STREAMING", sampleRevision = conversation.sampleRevision, sessionId = sessionId, displayKey = current.displayKey)
    }
    val timeline = remember(shownMessages, turns, interactions, reading?.itemKey) { retainToolGroupKeys(chatTimeline(shownMessages, turns, interactions), groupAnchors, reading?.itemKey) }
    var displayedSession by remember(conversationId) { mutableStateOf<String?>(null) }
    LaunchedEffect(mapping?.sessionId) {
        val next = mapping?.sessionId
        if (next != null && displayedSession != null && next != displayedSession) {
            expandedTools = emptyList(); expandedGroups = emptyList(); groupAnchors.clear(); reading = null
            pageSize = 80; list.scrollToItem(0)
        }
        if (next != null) displayedSession = next
    }
    LaunchedEffect(conversationId) {
        val draft = withContext(Dispatchers.IO) { dao.draft(conversationId) }
        if (draft != null) {
            editedMessageId = draft.editedMessageId; branchId = draft.branchId
            input = draft.text
            val parts = JSONArray(draft.attachments)
            attachments = (0 until parts.length()).map { parts.getJSONObject(it) }
        }
        draftLoaded = true
        reading = withContext(Dispatchers.IO) { dao.reading(conversationId) }
        reading?.let { pageSize = it.messageLimit }
        readingLoaded = true
    }
    LaunchedEffect(input, attachments, draftLoaded, editedMessageId, branchId) {
        if (draftLoaded) { delay(250); withContext(Dispatchers.IO) { graph.store.transaction { if (dao.conversation(conversationId) != null) dao.saveDraft(ChatDraftEntity(conversationId, input, JSONArray(attachments).toString(), editedMessageId, branchId)) } } }
    }
    DisposableEffect(conversationId) { onDispose { vm.saveDraft(conversationId, input, attachments, editedMessageId, branchId) } }
    LaunchedEffect(readingLoaded, timeline.size) {
        if (readingLoaded && !readingRestored && timeline.isNotEmpty() && messageCount > 0 && messages.size == minOf(pageSize, messageCount)) {
            val index = timeline.asReversed().indexOfFirst { it.key == reading?.itemKey }.coerceAtLeast(0)
            list.scrollToItem(index, reading?.offset ?: 0)
            readingRestored = true
        }
    }
    LaunchedEffect(conversationId, readingRestored, pageSize) {
        if (readingRestored) snapshotFlow { list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset }.debounce(200).collect { (index, offset) ->
            val key = list.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }?.key as? String
            if (key != null) withContext(Dispatchers.IO) { dao.saveReading(ChatReadingEntity(conversationId, key, offset, pageSize)) }
        }
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(conversationId, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            try { graph.chat.observe(conversationId).await(); vm.error.value = "" }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) { vm.error.value = error.message.orEmpty() }
        }
    }
    LaunchedEffect(timeline.size) { if (readingRestored && atBottom && timeline.isNotEmpty() && !list.isScrollInProgress) list.scrollToItem(0) }
    fun send(text: String, parts: JSONArray, clear: Boolean, intent: String = "NEW_TASK", literal: Boolean = false) {
        val command = if (literal) null else ChatCommands.parse(text)
        if (command != null) {
            if (!command.known) { unknownCommand = true; return }
            scope.launch {
                try {
                    when (command.name) {
                        "help" -> commandOutput = ChatCommands.entries.entries.joinToString("\n") { "/${it.key} · ${it.value}" }
                        "model" -> choosingModel = true
                        "skills" -> if (command.argument.isEmpty()) commandPanel = "skills" else {
                            val operation = command.argument.substringBefore(' ')
                            val skillId = command.argument.substringAfter(' ')
                            commandOutput = withContext(Dispatchers.IO) { graph.chat.pinSkill(conversationId, skillId, operation == "use").toString(2) }
                        }
                        "tools", "agents", "memory" -> commandPanel = command.name
                        "stop" -> graph.chat.stop(conversationId)
                        "compact" -> commandOutput = withContext(Dispatchers.IO) { if (dao.harnessSession(conversationId) == null) "尚无可压缩的会话历史" else {
                            val result = graph.chat.command(conversationId, "/compact")
                            check(result.getString("status") != "ERROR") { result.optJSONObject("result")?.optString("text") ?: result.optString("message") }
                            result.toString(2)
                        } }
                        "context" -> commandOutput = withContext(Dispatchers.IO) { (if (dao.harnessSession(conversationId) == null) graph.chat.selection(conversationId).snapshot() else graph.chat.context(conversationId)).put("scope", conversation!!.scope).put("model", conversation.model).toString(2) }
                        "new" -> openConversation(withContext(Dispatchers.IO) { graph.conversations.create(conversation!!.projectId, graph.models.selected(conversation)).id })
                        "fork" -> openConversation(withContext(Dispatchers.IO) { graph.chat.fork(conversation!!).id })
                    }
                    input = "" // Command execution preserves pending attachments.
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { vm.error.value = e.message.orEmpty() }
            }
            return
        }
        accepting = true
        vm.send(conversationId, if (literal) text else ChatCommands.literal(text), sessionId, allow, parts, accepted = {
            accepting = false
            if (clear) { input = ""; attachments = emptyList(); editedMessageId = null; branchId = null; vm.saveDraft(conversationId, "", emptyList()) }
        }, rejected = { accepting = false }, intent = if (active == conversationId && intent == "NEW_TASK") "SUPPLEMENT" else intent, editedMessageId = editedMessageId, branchId = branchId)
    }
    LaunchedEffect(error) { if (error.isNotEmpty()) accepting = false }
    if (conversation == null) { LoadingState(); return }
    if (unknownCommand) AlertDialog(onDismissRequest = { unknownCommand = false }, title = { Text("命令或参数无法识别") }, text = { Text("草稿和附件已保留。可以修改命令，或将原文作为普通消息发送。") }, confirmButton = { TextButton(onClick = { unknownCommand = false; send(input, JSONArray(attachments), true, literal = true) }) { Text("按原文发送") } }, dismissButton = { TextButton(onClick = { unknownCommand = false }) { Text("返回编辑") } })
    if (commandOutput.isNotBlank()) AlertDialog(onDismissRequest = { commandOutput = "" }, text = { SelectionContainer { Text(commandOutput, modifier = Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) } }, confirmButton = { TextButton(onClick = { commandOutput = "" }) { Text("关闭") } })
    commandPanel?.let { panel ->
        if (panel == "memory") AlertDialog(onDismissRequest = { commandPanel = null }, title = { Text("记忆与规则") }, text = { io.github.easylsp.app.feature.settings.MemoryScreen(graph, conversation.projectId) }, confirmButton = { TextButton(onClick = { commandPanel = null }) { Text("关闭") } })
        else CapabilityPanel(graph, conversation, panel, { commandPanel = null }, openConversation)
    }
    if (choosingAuthorization) AlertDialog(onDismissRequest = { choosingAuthorization = false }, title = { Text("选择会话授权模式") },
        text = { Text("此对话的后续输入沿用所选模式，可在会话设置中修改。") },
        confirmButton = { TextButton(onClick = { scope.launch { graph.chat.authorizeTask(conversationId, true).await(); allow = true; choosingAuthorization = false; send(input, JSONArray(attachments), true) } }) { Text("允许修改并运行") } },
        dismissButton = { TextButton(onClick = { scope.launch { graph.chat.authorizeTask(conversationId, false).await(); allow = false; choosingAuthorization = false; send(input, JSONArray(attachments), true) } }) { Text("单独确认") } })
    if (headerMenu) ConversationUsageDialog(conversation, mapping?.sessionId, if (statusConversation == conversationId && active == conversationId) status else "", turns, usage, plan, branches,
        selectedTurn, { headerMenu = false; selectedTurn = null }, { allowed -> scope.launch { graph.chat.authorizeTask(conversationId, allowed).await() } }, { selectedBranch = it }, historyAccess = { all -> scope.launch(Dispatchers.IO) { dao.saveConversation(conversation.copy(historyAccess = if (all) "authorized" else "scope")) } },
        globalDefault = { scope.launch(Dispatchers.IO) { graph.models.select(conversation, graph.models.selected(conversation), "global") } },
        descendantUsage = descendantUsage, openTerminal = { headerMenu = false; openTerminal() }, projectDefault = if (project == null) null else { { scope.launch(Dispatchers.IO) { graph.models.select(conversation, graph.models.selected(conversation), "project") }; Unit } })
    selectedBranch?.let { BranchHistoryDialog(it, conversation, graph, markdown, { selectedBranch = null }, openOutput, openPlugin) }
    fun reconnect() { scope.launch {
        try { graph.chat.observe(conversationId).await(); vm.error.value = "" } catch (error: Exception) { vm.error.value = error.message.orEmpty() }
    } }
    if (choosingModel) ConversationModels(conversation, providers, active != null || accepting || queued.any { it.status != "QUEUED" }, { choosingModel = false }, { provider ->
        scope.launch { try {
            val imageAttachment = attachments.any { it.optString("type") == "image_url" }
            require(!imageAttachment || JSONObject(provider.options).optBoolean("supportsImages")) { "草稿含有图片，请选择声明支持图片的模型" }
            graph.chat.selectModel(conversationId, provider.id).await(); choosingModel = false; vm.error.value = ""
        } catch (failure: Exception) { vm.error.value = failure.message.orEmpty() } }
    }, { choosingModel = false; managingModel = true })
    if (managingModel) io.github.easylsp.app.feature.settings.ProviderEditor(viewModel(), connectionProvider ?: ProviderEntity(java.util.UUID.randomUUID().toString(), "OpenAI", "openai", "", "")) { managingModel = false }
    Column(Modifier.fillMaxSize().testTag("conversation-$conversationId")) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (back != null) IconButton(onClick = back) { Icon(io.github.easylsp.app.ui.icons.AppIcons.ArrowBack, "返回") }
            Text("${project?.name ?: "快速对话"} · AI 助手", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            TextButton(onClick = { choosingModel = true }, modifier = Modifier.widthIn(max = 160.dp), contentPadding = PaddingValues(horizontal = 8.dp)) { Text("${conversation.model} ▾", style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) }
            IconButton(onClick = { headerMenu = true }, modifier = Modifier.semantics { contentDescription = "会话用量与设置" }) {
                Box(Modifier.size(14.dp).background(androidx.compose.ui.graphics.Color(0xFF49A778), androidx.compose.foundation.shape.CircleShape))
            }
        }
        maintenance[conversationId]?.let { receipt ->
            val commandState = receipt.getString("status")
            if (commandState !in setOf("COMPLETE", "ERROR", "STOPPED")) Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("上下文压缩 · " + when (commandState) { "RUNNING" -> "执行中"; "UNKNOWN" -> "结果未知，请查询已有回执"; else -> "尚未提交，可继续" }, modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelSmall)
                TextButton(onClick = { send("/compact", JSONArray(), false) }) { Text("查询 / 继续") }
                TextButton(onClick = { scope.launch { graph.chat.stop(conversationId) } }) { Text("停止") }
            }
        }
        if (project != null && conversation.sampleRevision != project.sampleRevision) Text("此会话沿用原样本；新建会话使用新版本。", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 16.dp))
        Box(Modifier.weight(1f)) {
            LazyColumn(state = list, reverseLayout = true, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxSize()) {
                items(timeline.asReversed(), key = { it.key }, contentType = { it::class.simpleName }) { item ->
                    when (item) {
                        is ChatTimelineItem.Message -> {
                            val row = item.row
                            MessageRow(row, row.content, markdown,
                                { language, code -> openOutput("代码", code, language) },
                                { editedMessageId = row.id; branchId = java.util.UUID.randomUUID().toString(); input = row.content; val parts = messageAttachments(row); attachments = (0 until parts.length()).map { parts.getJSONObject(it) } })
                        }
                        is ChatTimelineItem.Tools -> ToolRows(conversation, item, runs, graph, openPlugin, expandedTools, { expandedTools = it }, expandedGroups, { expandedGroups = it })
                        is ChatTimelineItem.Interaction -> InteractionCard(item.row) { answer ->
                            scope.launch(Dispatchers.IO) { graph.interactions.answer(item.row.id, answer) }
                        }
                        is ChatTimelineItem.Outcome -> {
                            val turn = item.turn
                            val summary = summarizeUsage(usage.filter { it.sessionId == mapping?.sessionId && it.turnId == turn.id }, turn.requestCount)
                            Text(summary.compact((turn.finishedAt ?: turn.startedAt) - turn.startedAt), style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.fillMaxWidth().clickable { selectedTurn = turn.id; headerMenu = true }.padding(vertical = 8.dp))
                            if (turn.status != "COMPLETE") ChatFeedback(turn.errorDetail.ifBlank { "上一轮已中断" }, "继续", active == null && !accepting && editedMessageId == null,
                                { send("继续上轮目标。沿用已经完成的工作，先查询状态确认未知结果，不重放已执行的写操作。", JSONArray(), false) },
                                "来源 ${turn.errorSource} · 阶段 ${turn.phase} · 类型 ${turn.errorType} · 请求 ${turn.requestCount} 次\n")
                        }
                    }
                }
                if (messageCount > messages.size) item(key = "older") { TextButton(onClick = { pageSize += 80 }, modifier = Modifier.fillMaxWidth()) { Text("加载更早的消息（${messages.size}/$messageCount）") } }
            }
            if (!atBottom) FilledTonalButton(onClick = { scope.launch { list.animateScrollToItem(0) } }, modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp)) { Text("回到底部") }
        }
        if (error.isNotEmpty()) Box(Modifier.padding(horizontal = 12.dp)) { ChatFeedback(error, "恢复会话", !accepting, ::reconnect) }
        else if (statusConversation == conversationId && status.startsWith("连接已断开")) TextButton(onClick = ::reconnect) { Text("重新连接") }
        if (statusConversation == conversationId && active == conversationId) Text(if (status.startsWith("正在执行工具：")) "正在执行：" + toolName(status.removePrefix("正在执行工具：")) else status.ifBlank { "正在接受请求…" }, modifier = Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.labelSmall)
        Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.extraLarge, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                if (queued.isNotEmpty()) {
                    var queueExpanded by rememberSaveable(conversationId) { mutableStateOf(false) }
                    TextButton(onClick = { queueExpanded = !queueExpanded }) { Text("待发送 ${queued.size} 条 · 当前任务完成后依次发送", style = MaterialTheme.typography.labelSmall) }
                    if (queueExpanded) queued.forEach { entry -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Text((if (entry.status == "UNKNOWN") "接受状态待确认 · " else if (entry.status == "SENDING") "正在接受 · " else "") + entry.text.take(70), modifier = Modifier.weight(1f), maxLines = 2, style = MaterialTheme.typography.bodySmall)
                        TextButton(enabled = entry.status == "QUEUED" || (entry.status == "UNKNOWN" && active == null), onClick = { scope.launch(Dispatchers.IO) { dao.deleteInput(entry.id) } }) { Text(if (entry.status == "UNKNOWN") "移除记录" else "取消") }
                    } }
                    if (queued.any { it.status != "QUEUED" }) TextButton(onClick = { scope.launch {
                        try { graph.chat.observe(conversationId).await(); vm.error.value = "" } catch (error: Exception) { vm.error.value = error.message.orEmpty() }
                    } }) { Text("重新连接并查询回执") }
                    if (active == null) TextButton(onClick = { graph.chat.sendQueued(conversationId) }) { Text("发送待处理输入") }
                }
                attachments.forEachIndexed { index, part ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(if (part.optString("type") == "image_url") "图片 ${index + 1}" else part.optString("text").lineSequence().first().take(50), style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f), maxLines = 1)
                        TextButton(onClick = { attachments = attachments.filterIndexed { i, _ -> i != index } }) { Text("移除") }
                    }
                }
                if (editedMessageId != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("正在修改较早的消息", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                        TextButton(onClick = { editedMessageId = null; branchId = null }) { Text("取消编辑") }
                    }
                    Text("将从这条消息重新继续；已执行的文件和插件操作不会撤销。", style = MaterialTheme.typography.labelSmall)
                    if (active == conversationId) Text("请先停止当前任务，再提交修改。", style = MaterialTheme.typography.labelSmall)
                }
                if (input.startsWith("/") && !input.contains(' ')) Row(Modifier.horizontalScroll(rememberScrollState())) {
                    ChatCommands.entries.filterKeys { it.startsWith(input.drop(1)) }.forEach { (name, description) -> TextButton(onClick = { input = "/$name" }) { Text("/$name · $description") } }
                }
                TextField(input, { input = it }, placeholder = { Text("描述需求，或补充下一步…") }, modifier = Modifier.fillMaxWidth(), maxLines = 5, enabled = !accepting,
                    colors = TextFieldDefaults.colors(focusedContainerColor = androidx.compose.ui.graphics.Color.Transparent, unfocusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                        focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent, unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ConversationControls(graph, conversation, active != null, { input += it }, attachments, { attachments = it }, openOutput)
                    Spacer(Modifier.weight(1f))
                    if (active == conversationId) TextButton(onClick = { graph.chat.stop(conversationId) }) { Text("停止") }
                    FilledTonalIconButton(enabled = (active == null || active == conversationId) && !accepting && input.isNotBlank() && (editedMessageId == null || active == null), onClick = { if (conversation.authorizationMode.isEmpty() && ChatCommands.parse(input) == null) choosingAuthorization = true else send(input, JSONArray(attachments), true) }) {
                        Text(if (accepting) "…" else if (active == conversationId) "+" else "↑", modifier = Modifier.semantics { contentDescription = if (active == conversationId) "补充当前任务" else "发送" })
                    }
                }
                val lastUser = messages.lastOrNull { it.role == "user" }
                val lastTurn = turns.lastOrNull()
                val hasWrites = runs.any { it.startedAt >= (lastUser?.createdAt ?: Long.MAX_VALUE) }
                if (active == null && editedMessageId == null && lastUser != null && !hasWrites && lastTurn?.status == "ERROR") TextButton(enabled = !accepting, onClick = { send(lastUser.content, messageAttachments(lastUser), false, "CONTINUE") }) { Text("重试此请求（保留附件）") }
            }
        }
    }
}

@Composable private fun InteractionCard(row: ChatInteractionEntity, answer: (JSONObject) -> Unit) {
    val request = remember(row.request) { JSONObject(row.request) }
    val pending = row.status == "PENDING"
    var details by rememberSaveable(row.id) { mutableStateOf(false) }
    val response = remember(row.response) { row.response.takeIf(String::isNotEmpty)?.let(::JSONObject) }
    val label = when {
        pending -> ""
        row.status != "ANSWERED" -> "所属任务已中断"
        row.kind == "APPROVAL" -> if (response!!.getBoolean("allowed")) "已允许这次操作" else "已拒绝"
        response!!.optBoolean("cancelled") -> "已取消提问"
        else -> "已回答：" + listOf(response.optJSONArray("selected")?.let { choices -> (0 until choices.length()).joinToString("、") { choices.getString(it) } }.orEmpty(), response.optString("text")).filter(String::isNotEmpty).joinToString(" · ")
    }
    if (!pending) TextButton(onClick = { details = !details }) { Text("${if (details) "▾" else "▸"} $label", maxLines = 1) }
    if (pending || details) Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.medium) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            if (row.kind == "APPROVAL") {
                Text("允许修改并运行", style = MaterialTheme.typography.titleSmall)
                Text("${request.getString("source")} · ${toolName(request.getString("tool"))}")
                var expanded by rememberSaveable(row.id) { mutableStateOf(false) }
                TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "收起操作内容" else "查看操作内容") }
                if (expanded) Text(request.getString("preview"), style = MaterialTheme.typography.bodySmall)
                if (pending) Row {
                    TextButton(onClick = { answer(JSONObject().put("allowed", true)) }) { Text("允许这次操作") }
                    TextButton(onClick = { answer(JSONObject().put("allowed", false)) }) { Text("拒绝") }
                }
            } else {
                Text(request.getString("question"), style = MaterialTheme.typography.titleSmall)
                var selected by rememberSaveable(row.id) { mutableStateOf(emptyList<String>()) }
                var text by rememberSaveable(row.id) { mutableStateOf("") }
                val options = request.optJSONArray("options") ?: JSONArray()
                if (pending) {
                    for (i in 0 until options.length()) {
                        val option = options.getString(i)
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable {
                            selected = if (request.optBoolean("multiple")) { if (option in selected) selected - option else selected + option } else listOf(option)
                        }) { Checkbox(option in selected, null); Text(option) }
                    }
                    OutlinedTextField(text, { text = it }, label = { Text("补充或直接回答") }, modifier = Modifier.fillMaxWidth())
                    Row {
                        TextButton(enabled = selected.isNotEmpty() || text.isNotBlank(), onClick = { answer(JSONObject().put("cancelled", false).put("selected", JSONArray(selected)).put("text", text)) }) { Text("提交回答") }
                        TextButton(onClick = { answer(JSONObject().put("cancelled", true)) }) { Text("取消提问") }
                    }
                }
            }
            if (!pending) {
                val response = remember(row.response) { row.response.takeIf(String::isNotEmpty)?.let(::JSONObject) }
                val label = when {
                    row.status != "ANSWERED" -> "所属任务已中断"
                    row.kind == "APPROVAL" -> if (response!!.getBoolean("allowed")) "已允许这次操作" else "已拒绝"
                    response!!.optBoolean("cancelled") -> "已取消提问"
                    else -> listOf(response.optJSONArray("selected")?.let { options -> (0 until options.length()).joinToString("、") { options.getString(it) } }.orEmpty(), response.optString("text")).filter(String::isNotEmpty).joinToString(" · ")
                }
                Text(label, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable private fun MessageRow(row: MessageEntity, text: String, markdown: Markwon, openCode: (String, String) -> Unit, edit: () -> Unit) {
    val blocks = remember(row.blocks) { JSONObject(row.blocks) }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (row.role == "user") Alignment.End else Alignment.Start) {
        if (row.role == "user") BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            val clipboard = LocalClipboardManager.current
            var menu by remember { mutableStateOf(false) }
            var selecting by rememberSaveable(row.id) { mutableStateOf(false) }
            Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.large,
                modifier = Modifier.widthIn(max = minOf(maxWidth * .82f, 560.dp)).combinedClickable(onClick = {}, onLongClick = { menu = true }).semantics {
                    customActions = listOf(CustomAccessibilityAction("复制") { clipboard.setText(AnnotatedString(text)); true }, CustomAccessibilityAction("编辑并从此处重发") { edit(); true }, CustomAccessibilityAction("选择文本") { selecting = true; true })
                }) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 7.dp)) {
                    val continuing = blocks.optString("inputIntent") == "CONTINUE"
                    if (selecting) SelectionContainer { Text(text, style = MaterialTheme.typography.bodyLarge) }
                    else Text(if (continuing) "继续任务" else text, style = MaterialTheme.typography.bodyLarge)
                    val attachments = messageAttachments(row)
                    if (attachments.length() > 0) Text("附件 ${attachments.length()}", style = MaterialTheme.typography.labelSmall)
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem(text = { Text("复制") }, onClick = { clipboard.setText(AnnotatedString(text)); menu = false })
                        DropdownMenuItem(text = { Text("选择文本") }, onClick = { selecting = true; menu = false })
                        DropdownMenuItem(text = { Text("编辑并从此处重发") }, onClick = { edit(); menu = false })
                    }
                    if (continuing) { var instruction by rememberSaveable(row.id) { mutableStateOf(false) }; TextButton(onClick = { instruction = !instruction }) { Text(if (instruction) "收起指令" else "指令详情") }; if (instruction) SelectionContainer { Text(text, style = MaterialTheme.typography.bodySmall) } }
                }
            }
        } else {
            val reasoning = blocks.optString("reasoning")
            if (reasoning.isNotEmpty()) {
                var expanded by rememberSaveable(row.id) { mutableStateOf(false) }
                TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "收起思考" else "思考 · 展开") }
                if (expanded) SelectionContainer { Text(reasoning, style = MaterialTheme.typography.bodySmall) }
            }
            if (text.isNotEmpty()) MarkdownMessage(text, markdown, openCode)
            else if (row.status == "STREAMING") Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) { CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp); Text("等待模型输出…", style = MaterialTheme.typography.labelMedium) }
        }
    }
}

