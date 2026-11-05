package io.github.easylsp.app.ui

import io.github.easylsp.app.ui.icons.AppIcons
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.*
import io.github.easylsp.app.MainViewModel
import io.github.easylsp.app.feature.home.HomeScreen
import io.github.easylsp.app.feature.projects.*
import io.github.easylsp.app.feature.settings.*
import io.github.easylsp.app.feature.logs.LogsScreen
import io.github.easylsp.app.feature.editor.Editor
import io.github.easylsp.app.feature.chat.ConversationScreen
import io.github.easylsp.app.ui.components.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun EasylspNavigation(vm: MainViewModel = viewModel(), destination: kotlinx.coroutines.flow.StateFlow<String?>? = null, consumed: () -> Unit = {}) {
    val nav = rememberNavController()
    val scope = rememberCoroutineScope()
    LaunchedEffect(destination) { destination?.collect { route -> if (route != null) { nav.navigate(route) { launchSingleTop = true }; consumed() } } }
    var editorBack by remember { mutableStateOf<(() -> Unit)?>(null) }
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val compact by vm.graph.settings.compactNavigation.collectAsStateWithLifecycle(false)
    val projects by vm.projects.collectAsStateWithLifecycle()
    val plugins by vm.plugins.collectAsStateWithLifecycle()
    val sessions by vm.sessions.collectAsStateWithLifecycle()
    val conversations by remember { vm.graph.store.workflows.observeConversations() }.collectAsStateWithLifecycle(emptyList())
    val instances by vm.instances.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val viewers = remember { ViewerStore(File(vm.graph.filesDir, "viewers")) }
    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }
    fun project(id: String) { nav.navigate("project/$id"); scope.launch(Dispatchers.IO) { vm.graph.store.dao.visit(id, System.currentTimeMillis()) } }
    fun viewer(title: String, text: String, language: String = "text") {
        scope.launch(Dispatchers.Main.immediate) {
            val origin = nav.currentBackStackEntry
            val id = withContext(Dispatchers.IO) { viewers.put(text) }
            if (nav.currentBackStackEntry === origin && origin?.lifecycle?.currentState?.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED) == true) nav.navigate("viewer/$id?title=${Uri.encode(title)}&language=${Uri.encode(language)}")
        }
    }
    val apkImport = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.action { val created = vm.graph.samples.importApk(uri); nav.navigate("project/${created.id}"); "APK 已导入" }
    }
    val importApk = { apkImport.launch(arrayOf("application/vnd.android.package-archive", "application/octet-stream")) }
    val rootRoutes = listOf("home", "projects", "settings")
    val titles = mapOf("appearance" to "外观", "models" to "模型", "extensions" to "扩展与工具", "environment" to "执行环境", "storage" to "存储", "about" to "关于")
    val title = when (route) {
        "home" -> "Easylsp"; "projects" -> "已添加项目"; "settings" -> "设置"; "apps" -> "添加应用"
        "settings/{category}" -> titles[entry?.arguments?.getString("category")] ?: "设置"
        "project/{projectId}" -> projects.firstOrNull { it.id == entry?.arguments?.getString("projectId") }?.name ?: "项目"
        "editor/{projectId}/{pluginId}?signature={signature}" -> plugins.firstOrNull { it.id == entry?.arguments?.getString("pluginId") }?.name ?: "编辑插件"
        "logs/{projectId}?session={session}&plugin={plugin}" -> "项目日志"
        "chat/{conversationId}?session={session}" -> "AI 助手"
        else -> entry?.arguments?.getString("title") ?: "Easylsp"
    }
    Scaffold(
        topBar = { if (route?.startsWith("chat/") != true) TopAppBar(title = { Text(title, maxLines = 1) }, navigationIcon = { if (route !in rootRoutes) IconButton(onClick = { if (route?.startsWith("editor/") == true) editorBack?.invoke() else nav.popBackStack() }) { Icon(AppIcons.ArrowBack, "返回") } }) },
        bottomBar = { if (route in rootRoutes) NavigationBar { listOf(Triple("home", "首页", AppIcons.Home), Triple("projects", "项目", AppIcons.Apps), Triple("settings", "设置", AppIcons.Settings)).forEach { (id, label, icon) ->
            NavigationBarItem(selected = route == id, onClick = { nav.navigate(id) { popUpTo(nav.graph.findStartDestination().id) { saveState = true }; launchSingleTop = true; restoreState = true } }, icon = { Icon(icon, null) }, label = { Text(label) }, alwaysShowLabel = !compact)
        } } },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        Column(Modifier.padding(padding).consumeWindowInsets(padding).imePadding().fillMaxSize()) {
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            NavHost(nav, startDestination = "home", modifier = Modifier.weight(1f)) {
                composable("home") { HomeScreen(vm, ::project, { nav.navigate("apps") }, {
                    vm.action {
                        val id = withContext(Dispatchers.IO) { if (vm.graph.models.defaultModel(null) == null) null else vm.graph.conversations.create().id }
                        if (id == null) nav.navigate("settings/models") else nav.navigate("chat/$id")
                        ""
                    }
                }, { nav.navigate("settings/models") }, { nav.navigate("chat/$it") }, { nav.navigate("conversations") }) }
                composable("conversations") { io.github.easylsp.app.feature.chat.ConversationHistoryScreen(vm) { nav.navigate("chat/$it") } }
                composable("projects") { ProjectsScreen(vm, ::project, { nav.navigate("apps") }, importApk) }
                composable("apps") { AddAppScreen(vm, ::project) }
                composable("settings") { SettingsScreen { nav.navigate("settings/$it") } }
                composable("settings/{category}") { item -> SettingsDetailScreen(vm, requireNotNull(item.arguments?.getString("category"))) { name, text -> viewer(name, text) } }
                composable("project/{projectId}") { item ->
                    val id = item.arguments?.getString("projectId")
                    val current = projects.firstOrNull { it.id == id }
                    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                        if (uri != null && current != null) vm.action {
                            val created = withContext(Dispatchers.IO) { vm.graph.store.importPlugin(current, requireNotNull(vm.graph.contentResolver.openInputStream(uri))) }
                            nav.navigate("editor/${current.id}/${created.id}"); "插件已导入，默认停用"
                        }
                    }
                    if (current != null) ProjectScreen(vm, current, { nav.navigate("editor/$id/$it") }, { importer.launch(arrayOf("application/zip", "application/octet-stream")) },
                        { session, plugin -> nav.navigate("logs/$id?session=${session.orEmpty()}&plugin=${plugin.orEmpty()}") },
                        { name, text, language -> viewer(name, text, language) },
                        { conversation, session -> nav.navigate("chat/$conversation?session=${session.orEmpty()}") },
                        { signature -> nav.navigate("template/$id?signature=${Uri.encode(signature)}") })
                    else LoadingState("正在读取项目…")
                }
                composable("editor/{projectId}/{pluginId}?signature={signature}") { item ->
                    val current = projects.firstOrNull { it.id == item.arguments?.getString("projectId") }
                    val plugin = plugins.firstOrNull { it.id == item.arguments?.getString("pluginId") }
                    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
                        if (uri != null && plugin != null) vm.action { vm.graph.store.exportPlugin(plugin.id, requireNotNull(vm.graph.contentResolver.openOutputStream(uri))); "插件已导出" }
                    }
                    if (current != null && plugin != null) {
                        val host = sessions.values.firstOrNull { it.packageName == current.packageName && it.processName == plugin.processName.ifEmpty { current.packageName } }
                        val instance = host?.let { instances["${it.id}/${plugin.id}"] }
                        Editor(vm, current, plugin, if (host == null) "OFFLINE" else instance?.state ?: "STOPPED", instance?.takeIf { it.state == "RUNNING" }?.revision ?: 0, instance?.error.orEmpty(), busy, { exporter.launch("${plugin.name}.zip") }, { nav.popBackStack() }, { nav.popBackStack() }, { editorBack = it }, { nav.navigate("logs/${current.id}?plugin=${plugin.id}") }, item.arguments?.getString("signature").orEmpty())
                    }
                }
                composable("logs/{projectId}?session={session}&plugin={plugin}") { item ->
                    val current = projects.firstOrNull { it.id == item.arguments?.getString("projectId") }
                    if (current != null) LogsScreen(vm, current, item.arguments?.getString("session")?.takeIf { it.isNotEmpty() }, item.arguments?.getString("plugin")?.takeIf { it.isNotEmpty() }, { name, text -> viewer(name, text) }, { plugin -> nav.navigate("editor/${current.id}/$plugin") })
                }
                composable("conversation-terminal/{conversationId}") { item ->
                    val id = requireNotNull(item.arguments?.getString("conversationId"))
                    val conversation by remember(id) { vm.graph.store.workflows.observeConversation(id) }.collectAsStateWithLifecycle(null)
                    conversation?.let { row ->
                        val workspace = io.github.easylsp.app.platform.terminal.Workspace(row.projectId ?: row.id, row.scope)
                        LaunchedEffect(workspace) { withContext(Dispatchers.IO) { vm.graph.executions.bind(workspace) } }
                        io.github.easylsp.app.feature.p3.TerminalScreen(vm, projects.firstOrNull { it.id == row.projectId }, workspaceId = workspace.id)
                    }
                }
                composable("chat/{conversationId}?session={session}") { item ->
                    val conversationId = requireNotNull(item.arguments?.getString("conversationId"))
                    val conversation by remember(conversationId) { vm.graph.store.workflows.observeConversation(conversationId) }.collectAsStateWithLifecycle(null)
                    val current = projects.firstOrNull { it.id == conversation?.projectId }
                    val preferred = item.arguments?.getString("session")
                    val host = sessions.values.firstOrNull { it.id == preferred && it.packageName == current?.packageName }
                        ?: sessions.values.firstOrNull { current != null && it.packageName == current.packageName && it.processName == current.packageName }
                    if (conversation != null) ConversationScreen(current, conversationId, host?.id, { name, text, language -> viewer(name, text, language) },
                        { plugin -> current?.let { nav.navigate("editor/${it.id}/$plugin") } }, back = { nav.popBackStack() }, openConversation = { nav.navigate("chat/$it") }, openTerminal = { nav.navigate("conversation-terminal/$conversationId") })
                }
                composable("template/{projectId}?signature={signature}") { item ->
                    val current = projects.firstOrNull { it.id == item.arguments?.getString("projectId") }
                    val signature = item.arguments?.getString("signature").orEmpty()
                    if (current != null) Column(Modifier.fillMaxSize().padding(16.dp)) {
                        Text(signature, style = MaterialTheme.typography.bodySmall)
                        SectionHeader("将模板插入插件")
                        Button(onClick = { vm.create(current, null, "方法 Hook", description = signature) { plugin -> nav.navigate("editor/${current.id}/${plugin.id}?signature=${Uri.encode(signature)}") } }) { Text("新建插件") }
                        plugins.filter { it.projectId == current.id }.forEach { plugin -> TextButton(onClick = { nav.navigate("editor/${current.id}/${plugin.id}?signature=${Uri.encode(signature)}") }) { Text(plugin.name) } }
                    }
                }
                composable("viewer/{id}?title={title}&language={language}") { item -> StoredViewer(viewers, requireNotNull(item.arguments?.getString("id")), item.arguments?.getString("language") ?: "text") }
            }
        }
    }
}
