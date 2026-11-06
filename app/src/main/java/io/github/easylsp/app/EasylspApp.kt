package io.github.easylsp.app
import android.app.Application
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import io.github.easylsp.app.data.*
import io.github.easylsp.app.platform.bridge.BridgeHub
class EasylspApp : Application() {
    val framework = io.github.easylsp.app.platform.FrameworkStatus()
    val root by lazy { io.github.easylsp.app.platform.root.RootAccess(filesDir) }
    override fun onCreate() {
        super.onCreate()
        if (android.app.Application.getProcessName() == packageName) {
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch { models.migrateConnections(); skillPackages.installPresets() }
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                root.state.collect { value ->
                    if (value.granted && root.preferred.value) {
                        try { managerKeepalive.reconcile() }
                        catch (error: Exception) {
                            if (error is CancellationException) throw error
                            android.util.Log.e("EasylspKeepalive", "UID0 keeper setup failed", error)
                        }
                    }
                }
            }
            framework.connect()
            if (root.preferred.value) root.refresh()
        }
    }
    val managerKeepalive by lazy { io.github.easylsp.app.platform.root.ManagerKeepalive(this) }
    val executions by lazy { io.github.easylsp.app.platform.terminal.WorkspaceExecution(filesDir, commandWrapper = root::wrap).apply {
        pty = { project, id, command, cwd, timeout, output -> terminals.execute(project, id, command, cwd, timeout, output) }
        ptyInput = { project, id, text, close -> if (terminals.entries.value.any { it.id == id }) { terminals.input(project, id, text + if (close) "\u0004" else ""); true } else false }
        ptyStop = { project, id -> if (terminals.entries.value.any { it.id == id }) terminals.stop(project, id) }
    } }
    val terminals by lazy { io.github.easylsp.app.platform.terminal.TerminalSessions(this) }
    val linux by lazy { io.github.easylsp.app.platform.terminal.LinuxEnvironment(this) }
    val harness by lazy { io.github.easylsp.app.platform.harness.HarnessEnvironment(this) }
    val skillPackages by lazy { io.github.easylsp.app.platform.skills.SkillPackages(this) }
    val skills by lazy { io.github.easylsp.app.platform.skills.SkillRunner(this) }
    val mcp by lazy { io.github.easylsp.app.platform.mcp.McpService(this) }
    val phone by lazy { io.github.easylsp.app.platform.phone.PhoneController(this) }
    val agents by lazy { io.github.easylsp.app.platform.agents.AgentCoordinator(this) }
    val frida by lazy { io.github.easylsp.app.platform.frida.FridaBackend(this) }
    val apkExporter by lazy { io.github.easylsp.app.platform.export.ApkExporter(this) }
    val store by lazy { ProjectStore(this) }
    val secrets by lazy { SecretStore(this) }
    val toolOutputs by lazy { io.github.easylsp.app.platform.tools.ToolOutputStore(java.io.File(filesDir, "tool-output")) }
    val models by lazy { io.github.easylsp.app.platform.config.ModelConfigurationService(this) }
    val conversations by lazy { io.github.easylsp.app.platform.ai.ConversationService(this) }
    val memories by lazy { io.github.easylsp.app.platform.ai.MemoryService(this) }
    val tools by lazy { io.github.easylsp.app.platform.tools.ToolCatalog(this) }
    val chat by lazy { io.github.easylsp.app.platform.ai.ChatEngine(this) }
    val interactions by lazy { io.github.easylsp.app.platform.ai.TaskInteractions(store.workflows) }
    val logs by lazy { LogStore(this) }
    val samples by lazy { SampleStore(this, store) }
    val analysis by lazy { io.github.easylsp.app.platform.analysis.AnalysisClient(this) }
    val nativeAnalysis by lazy { io.github.easylsp.app.platform.analysis.NativeAnalysis(this) }
    val nativeBuilds by lazy { io.github.easylsp.app.platform.nativeplugins.NativeBuildService(this) }
    val github by lazy { io.github.easylsp.app.platform.market.GitHubAccount(this) }
    val market by lazy { io.github.easylsp.app.platform.market.MarketService(this) }
    val releasePublishing by lazy { io.github.easylsp.app.platform.market.ReleasePublishingService(this) }
    val releaseActivation by lazy { io.github.easylsp.app.platform.market.ReleaseActivationService(this) }
    val releasePreparations by lazy { io.github.easylsp.app.platform.market.ReleasePreparationService(this) }
    val targetApplications by lazy { io.github.easylsp.app.platform.nativeplugins.TargetApplications(this) }
    val nativeDeployments by lazy { io.github.easylsp.app.platform.nativeplugins.NativeDeploymentPublisher(this) }
    val nativeHost by lazy { io.github.easylsp.app.platform.nativeplugins.NativeHostClient(this) }
    val nativeInstances by lazy { io.github.easylsp.app.platform.nativeplugins.NativeInstanceStore(this) }
    val diagnostics by lazy { io.github.easylsp.app.platform.diagnostics.DiagnosticCoordinator(this) }
    val localManagement by lazy { io.github.easylsp.app.platform.ai.LocalManagementService(this) }
    val storage by lazy { StorageManager(this) }
    val settings by lazy { SettingsStore(this) }
    val bridge by lazy { BridgeHub(this, store, logs) }
}
