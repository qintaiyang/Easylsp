package io.github.easylsp.app.feature.settings

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.easylsp.app.MainViewModel
import io.github.easylsp.app.platform.nativeplugins.nativeHostProblem
import io.github.easylsp.app.ui.components.*

@Composable internal fun RuntimeEnvironmentCards(vm: MainViewModel) {
    val preparation by vm.graph.harness.preparationId.collectAsStateWithLifecycle()
    val preparationStatus by vm.graph.harness.status.collectAsStateWithLifecycle()
    val executions by vm.graph.executions.store.records.collectAsStateWithLifecycle()
    val execution = executions.firstOrNull { it.id == preparation }
    var offset by remember(preparation) { mutableIntStateOf(0) }
    var nextOffset by remember(preparation) { mutableStateOf<Int?>(null) }
    var preparationLog by remember { mutableStateOf("") }
    LaunchedEffect(preparation, execution, offset) {
        val page = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { if (execution != null) vm.graph.harness.preparationPage(offset) else null }
        preparationLog = page?.optString("text").orEmpty()
        nextOffset = page?.takeUnless { it.isNull("nextOffset") }?.getInt("nextOffset")
    }
    AppCard {
        Text("准备 AI 环境", style = MaterialTheme.typography.titleLarge)
        Text(preparationStatus)
        execution?.let { Text("任务 ${it.id} · ${it.state}") }
        TextButton(onClick = { vm.action { vm.graph.harness.prepare(); "准备任务已启动；已完成阶段会复用" } }) { Text("准备 / 继续未完成步骤") }
        if (preparationLog.isNotEmpty()) Text(preparationLog)
        if (offset > 0) TextButton(onClick = { offset = 0 }) { Text("从头查看") }
        nextOffset?.let { next -> TextButton(onClick = { offset = next }) { Text("后续日志") } }
    }
    val framework by vm.graph.framework.state.collectAsStateWithLifecycle()
    val nativeHost by vm.graph.nativeHost.state.collectAsStateWithLifecycle()
    val root by vm.graph.root.state.collectAsStateWithLifecycle()
    val javaReady = framework.compatible
    val nativeProblem = nativeHostProblem(nativeHost, root.granted)
    LaunchedEffect(root.granted) {
        if (root.granted) kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { vm.graph.nativeHost.refresh() }
    }
    AppCard {
        Text("Java 模块与框架", style = MaterialTheme.typography.titleLarge)
        StatusBadge(if (javaReady) "框架服务已连接" else if (framework.active) "框架 API 不满足（需要 101）" else "框架服务未连接", javaReady)
        Text(framework.description)
        if (framework.frameworkName.isNotEmpty()) Text("${framework.frameworkName} · 版本 ${framework.frameworkVersion} · libxposed API ${framework.apiVersion}")
        Text("Java Hook 需要在框架管理器中启用 Easylsp、授予目标作用域并重启目标。", style = MaterialTheme.typography.bodyMedium)
    }
    AppCard {
        Text("Native 宿主", style = MaterialTheme.typography.titleLarge)
        Text("安装版本：${nativeHost.optString("installedVersion", "尚未查询")}")
        Text("安装运行库：${nativeHost.optString("installedRuntime", "尚未查询")}\n本次启动运行库：${nativeHost.optJSONObject("runtime")?.optString("runtimeVersion") ?: "尚无进程证据"}")
        Text("提供者候选：${nativeHost.optJSONArray("providerCandidates") ?: "尚未查询"}")
        Text("Java：${if (javaReady) "可用" else "待恢复框架连接"} · Native：${nativeProblem ?: "宿主已运行"}")
        Text("协议：${nativeHost.optInt("hostProtocolVersion")} · 能力：${nativeHost.optJSONArray("capabilities") ?: "尚未取得"}")
        Text("运行状态：${nativeHost.optString("runtimeStatus", nativeHost.optString("status", "尚未查询"))}")
        nativeHost.optString("detail").takeIf { it.isNotEmpty() }?.let { Text(it) }
        Text("宿主安装/升级后需重启手机；插件发布后只需重启目标应用。")
        TextButton(onClick = { vm.action { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { vm.graph.nativeHost.refresh() }.toString() } }) { Text("刷新 Native 宿主") }
        TextButton(onClick = { vm.action { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { vm.graph.nativeHost.call("disable-all") }; "已撤销全部后续启动发布；请退出已加载的目标进程" } }) { Text("恢复：停用全部 Native 发布") }
    }
}
