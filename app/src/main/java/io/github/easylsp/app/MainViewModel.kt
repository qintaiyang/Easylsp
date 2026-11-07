package io.github.easylsp.app

import android.app.Application
import android.content.Intent
import android.content.pm.ApplicationInfo
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.easylsp.api.internal.Protocol
import io.github.easylsp.app.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class MainViewModel(application: Application) : AndroidViewModel(application) {
    val graph = application as EasylspApp
    val projects = graph.store.projects.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val plugins = graph.store.plugins.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val showSystem = graph.settings.showSystem.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    val sessions = graph.bridge.sessions
    val instances = graph.bridge.instances
    val nativeBuilds = graph.store.nativePlugins.observeBuilds().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val nativeDeployments = graph.store.nativePlugins.observeDeployments().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val nativeReports = MutableStateFlow<Map<String, String>>(emptyMap())
    val logs = graph.logs.lines
    val busy = MutableStateFlow(false)
    val messages = MutableSharedFlow<String>()
    data class InstalledApp(val packageName: String, val name: String, val version: String, val system: Boolean, val installedAt: Long = 0, val updatedAt: Long = 0)
    val installed = MutableStateFlow<List<InstalledApp>>(emptyList())
    init {
        viewModelScope.launch { graph.initializeManagement() }
        viewModelScope.launch {
            graph.framework.state.map { it.compatible }.distinctUntilChanged().filter { it }.collect {
                val failures = withContext(Dispatchers.IO) { graph.javaDeployments.migrateEnabled() }
                if (failures.isNotEmpty()) messages.emit("已启用插件的离线发布未完成：\n" + failures.joinToString("\n"))
            }
        }
        refreshApps()
    }
    fun action(block: suspend () -> String) {
        if (busy.value) return
        busy.value = true
        viewModelScope.launch {
            try { messages.emit(block()) }
            catch (error: Exception) { if (error is CancellationException) throw error; messages.emit(error.message ?: error.toString()) }
            finally { busy.value = false }
        }
    }
    fun refreshApps() = action {
        installed.value = withContext(Dispatchers.IO) {
            val pm = graph.packageManager
            pm.getInstalledApplications(0).filter { it.packageName != graph.packageName }.map { app ->
                val info = pm.getPackageInfo(app.packageName, 0)
                InstalledApp(app.packageName, app.loadLabel(pm).toString(), info.versionName ?: "", app.flags and ApplicationInfo.FLAG_SYSTEM != 0, info.firstInstallTime, info.lastUpdateTime)
            }.sortedBy { it.name.lowercase() }
        }
        "应用列表已刷新"
    }
    fun select(app: InstalledApp, done: (ProjectEntity) -> Unit) = action { done(graph.store.select(app.packageName, app.name, app.version)); "项目已选择；请在 LSPosed 配置作用域" }
    fun setApplicationScope(app: InstalledApp, enabled: Boolean) = action {
        withContext(Dispatchers.IO) {
            if (enabled) graph.framework.requestScope(app.packageName)
            else graph.framework.removeScope(app.packageName)
        }
        if (enabled) "已向框架请求 ${app.name} 的作用域，请在框架提示中确认" else "已从作用域移除 ${app.name}，重启目标应用后生效"
    }
    fun create(project: ProjectEntity, example: String?, name: String = example ?: "新插件", author: String = "", description: String = "", version: String = "1.0", runtime: String = "BEANSHELL", done: (PluginEntity) -> Unit) = action {
        val source = withContext(Dispatchers.IO) { if (runtime == "NATIVE") graph.assets.open("native-sdk/blank.c").bufferedReader().use { it.readText() } else if (example == null) "void onLoad() {\n    plugin.log(\"插件已加载\");\n}\n\nvoid onUnload() {\n    plugin.log(\"插件已卸载\");\n}\n" else graph.assets.open("$example/main.java").bufferedReader().use { it.readText() } }
        done(graph.store.create(project, name, source, author, description, version, runtime = runtime)); "插件已创建，尚未启用"
    }
    fun enableRelease(plugin: PluginEntity) = action {
        graph.releaseActivation.enable(plugin)
        "固定发行及依赖已启用，请重启目标生效"
    }
    fun disableRelease(plugin: PluginEntity, delete: Boolean = false) = action {
        graph.releaseActivation.disable(plugin, delete)
        if (delete) "整组发行已删除，请重启目标" else "整组发行已停用，请重启目标；依赖发行保留"
    }
    fun setEnabled(project: ProjectEntity, plugin: PluginEntity, enabled: Boolean) = action {
        if (plugin.runtime == "NATIVE") {
            require(!enabled) { "请在 native 操作中选择固定 buildId 和运行策略再发布" }
            withContext(Dispatchers.IO) { graph.nativeDeployments.disable(plugin) }
            return@action "已停用；下次目标新进程生效"
        }
        if (enabled) withContext(Dispatchers.IO) {
            val dependencies = ReleaseDependencies.installedOrder(plugin, graph.store.dao.plugins(project.id))
            require(dependencies.all { it.id == plugin.id || it.desiredEnabled }) { "请先启用联合发行与依赖" }
        }
        graph.store.enable(plugin.id, enabled)
        if (graph.bridge.sessions.value.values.none { it.uid / 100000 == project.userId && it.packageName == project.packageName && PluginProcesses.matches(plugin, project.packageName, it.processName) }) "启用配置已发布；下次目标启动生效"
        else graph.bridge.command(project.packageName, if (enabled) "loadPlugin" else "unloadPlugin", plugin.id)
    }
    fun command(project: ProjectEntity, operation: String, plugin: PluginEntity? = null) = action { graph.bridge.command(project.packageName, operation, plugin?.id) }
    fun delete(project: ProjectEntity, plugin: PluginEntity, done: () -> Unit) = action {
        if (plugin.runtime == "NATIVE" && project.kind == "INSTALLED") withContext(Dispatchers.IO) { graph.nativeDeployments.disable(plugin) }
        else if (graph.bridge.sessions.value.values.any { it.uid / 100000 == project.userId && it.packageName == project.packageName && PluginProcesses.matches(plugin, project.packageName, it.processName) }) graph.bridge.command(project.packageName, "unloadPlugin", plugin.id)
        graph.store.delete(plugin.id); done(); "插件已删除"
    }
    fun buildNative(project: ProjectEntity, plugin: PluginEntity) = action {
        val result = withContext(Dispatchers.IO) { graph.nativeBuilds.build(plugin, plugin.revision, project.sampleRevision) }
        "构建已开始：${result.getString("buildId")}；可在详情读取编译输出"
    }
    fun enableNative(plugin: PluginEntity, buildId: String, policy: String) = action {
        withContext(Dispatchers.IO) { graph.nativeDeployments.enable(plugin, buildId, policy) }
        "已发布 $buildId；下次启动生效"
    }
    fun nativeStatus(plugin: PluginEntity) = action {
        val report = withContext(Dispatchers.IO) { graph.nativeInstances.status(plugin).toString(2) }
        nativeReports.update { it + (plugin.id to report) }; "native 执行证据已更新"
    }
    fun restartNative(project: ProjectEntity, plugin: PluginEntity) = action {
        withContext(Dispatchers.IO) {
            require(graph.root.useRoot()) { "重启目标需要已授予的 root" }
            val output = StringBuilder()
            val code = graph.executions.runner.execute(java.util.UUID.randomUUID().toString(), listOf(graph.root.su, "-c", "am force-stop --user ${project.userId} ${io.github.easylsp.app.platform.root.RootCommand.quote(project.packageName)} && am start --user ${project.userId} -W -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -p ${io.github.easylsp.app.platform.root.RootCommand.quote(project.packageName)}"), graph.filesDir, closeInput = true) { output.append(it) }.exitCode
            check(code == 0) { output.toString() }
            nativeReports.update { it + (plugin.id to graph.nativeInstances.status(plugin).toString(2)) }
        }
        "目标已重启；已读取实际 native 事件"
    }
    fun start(project: ProjectEntity) = action {
        if (graph.framework.state.value.active) withContext(Dispatchers.IO) { graph.framework.requestJavaDebug(requireNotNull(graph.targetApplications.info(project.packageName, project.userId).applicationInfo).uid, project.packageName) }
        val intent = graph.packageManager.getLaunchIntentForPackage(project.packageName) ?: error("目标没有启动 Activity")
        graph.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); "已启动目标"
    }
    fun reconnect(project: ProjectEntity) = action {
        graph.bridge.reconnect(project.packageName); "已请求重新连接；未运行时请先启动目标"
    }
}
