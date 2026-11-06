package io.github.easylsp.app.platform.terminal

import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Owns app-scoped work; leaving a screen never starts or repeats a command. */
class WorkspaceExecution(private val root: File, val runner: CommandRunner = CommandRunner(), private val commandWrapper: suspend (List<String>) -> List<String> = { it }) {
    val store = ExecutionStore(File(root, "executions"))
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<String, Job>()
    private val sharedDirectories = ConcurrentHashMap.newKeySet<String>()
    fun bind(workspace: Workspace) {
        val metadata = File(directory(workspace.id), ".workspace.json")
        metadata.writeText(JSONObject().put("id", workspace.id).put("scope", workspace.scope).toString())
    }
    fun workspace(id: String): Workspace {
        val metadata = File(root, "workspaces/$id/.workspace.json")
        return Workspace(id, if (metadata.exists()) JSONObject(metadata.readText()).getString("scope") else "PROJECT")
    }
    fun directory(projectId: String, relative: String = "."): File {
        UUID.fromString(projectId)
        val base = File(root, "workspaces/$projectId").apply { mkdirs() }.canonicalFile
        // Commands keep this app's group through setgid; root-created children remain app-writable.
        if (sharedDirectories.add(projectId)) android.system.Os.chmod(base.path, 0b10111111000) // 02770
        val directory = File(base, relative).canonicalFile
        require(directory == base || directory.path.startsWith(base.path + File.separator)) { "工作目录必须位于项目空间" }
        require(directory.isDirectory) { "工作目录不存在；先用 mkdir 创建" }
        return directory
    }
    fun requireRecord(projectId: String, id: String): ExecutionRecord = store.records.value.firstOrNull { it.id == id && it.workspaceId == projectId } ?: error("执行记录不属于当前项目")
    fun start(projectId: String, kind: String, title: String, work: suspend (String, (String) -> Unit) -> Int): String {
        val id = UUID.randomUUID().toString()
        val owner = ExecutionOwner.conversation.get()
        store.save(ExecutionRecord(id, projectId, kind, title, "RUNNING", workspaceScope = workspace(projectId).scope, conversationId = owner))
        val job = scope.launch(ExecutionOwner.conversation.asContextElement(owner), start = CoroutineStart.LAZY) {
            var state = "COMPLETE"; var exit: Int? = null
            try { exit = work(id) { store.append(id, it) }; if (exit != 0) state = "ERROR" }
            catch (error: TimeoutCancellationException) { state = "TIMEOUT"; store.append(id, "\n执行超时\n") }
            catch (error: CancellationException) { state = "CANCELLED" }
            catch (error: Exception) { state = "ERROR"; store.append(id, "\n${error.message ?: error}\n") }
            finally { store.save(requireRecord(projectId, id).copy(state = state, exitCode = exit)); jobs.remove(id) }
        }
        jobs[id] = job; job.start(); return id
    }
    var pty: (suspend (String, String, List<String>, String, Int, (String) -> Unit) -> Int)? = null
    var ptyInput: ((String, String, String, Boolean) -> Boolean)? = null
    var ptyStop: ((String, String) -> Unit)? = null
    fun command(projectId: String, script: String, cwd: String = ".", timeoutSeconds: Int = 120, interactive: Boolean = false): String {
        require(script.isNotBlank()) { "command 不能为空" }
        require(timeoutSeconds in 1..3600) { "timeoutSeconds 必须在 1..3600 秒范围内" }
        val directory = directory(projectId, cwd)
        return start(projectId, "terminal", script) { id, output ->
            val command = commandWrapper(listOf(runner.shellPath, "-c", script))
            val terminal = pty
            if (terminal != null) terminal(projectId, id, command, cwd, timeoutSeconds, output)
            else runner.execute(id, command, directory, closeInput = !interactive, timeoutSeconds = timeoutSeconds, output = output).exitCode
        }
    }
    suspend fun await(projectId: String, id: String): JSONObject { requireRecord(projectId, id); jobs[id]?.join(); return store.page(projectId, id, 0, 12000) }
    suspend fun waitFor(projectId: String, id: String, seconds: Int): JSONObject {
        require(seconds in 1..60) { "waitSeconds 必须在 1..60 秒范围内" }; requireRecord(projectId, id)
        withTimeoutOrNull(seconds * 1000L) { jobs[id]?.join() }
        return store.page(projectId, id, 0, 12000)
    }
    fun stop(projectId: String, id: String) { requireRecord(projectId, id); val job = jobs[id]; if (job != null) job.cancel() else ptyStop?.invoke(projectId, id) }
    suspend fun stopConversation(id: String) {
        val owned = store.records.value.filter { it.conversationId == id && it.state == "RUNNING" }
        owned.forEach { stop(it.workspaceId, it.id) }
        owned.forEach { jobs[it.id]?.join() }
        check(store.records.value.none { it.conversationId == id && it.state == "RUNNING" }) { "此会话仍有未确认停止的执行" }
    }
    suspend fun stopWorkspace(id: String) {
        val owned = store.records.value.filter { it.workspaceId == id && it.state == "RUNNING" }
        owned.forEach { stop(id, it.id) }
        owned.forEach { jobs[it.id]?.join() }
        check(store.records.value.none { it.workspaceId == id && it.state == "RUNNING" }) { "仍有未确认停止的执行记录" }
    }
    fun input(projectId: String, id: String, text: String, close: Boolean) { requireRecord(projectId, id); if (ptyInput?.invoke(projectId, id, text, close) == true) return; runner.write(id, text); if (close) runner.closeInput(id) }
}
