package io.github.easylsp.app.platform.terminal

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.termux.terminal.*
import io.github.easylsp.app.EasylspApp
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.UUID
import java.io.File
import io.github.easylsp.app.platform.root.RootCommand
import java.util.concurrent.ConcurrentHashMap

/** App-owned PTYs; visible sessions and AI execution IDs refer to the same shell. */
class TerminalSessions(private val graph: EasylspApp) {
    data class Entry(val id: String, val workspaceId: String, val title: String, val session: TerminalSession, val rootPidFile: File? = null, val workspaceScope: String = "PROJECT") { val projectId get() = workspaceId.takeIf { workspaceScope == "PROJECT" } }
    val entries = MutableStateFlow<List<Entry>>(emptyList())
    private val finished = ConcurrentHashMap<String, CompletableDeferred<Int>>()
    var changed: ((TerminalSession) -> Unit)? = null
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = object : TerminalSessionClient {
        override fun onTextChanged(session: TerminalSession) {
            entries.value.firstOrNull { it.session === session }?.let { entry ->
                if (graph.executions.store.records.value.any { it.id == entry.id }) graph.executions.store.replaceOutput(entry.id, session.emulator.screen.transcriptText)
            }
            changed?.invoke(session)
        }
        override fun onTitleChanged(session: TerminalSession) { changed?.invoke(session) }
        override fun onSessionFinished(session: TerminalSession) {
            val entry = entries.value.firstOrNull { it.session === session } ?: return
            finished[entry.id]?.complete(session.exitStatus)
            changed?.invoke(session)
        }
        override fun onCopyTextToClipboard(session: TerminalSession, text: String) { (graph.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("终端", text)) }
        override fun onPasteTextFromClipboard(session: TerminalSession) { (graph.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip?.getItemAt(0)?.coerceToText(graph)?.let { session.emulator.paste(it.toString()) } }
        override fun onBell(session: TerminalSession) {}
        override fun onColorsChanged(session: TerminalSession) { changed?.invoke(session) }
        override fun onTerminalCursorStateChange(state: Boolean) {}
        override fun getTerminalCursorStyle(): Int = 0
        override fun logError(tag: String, message: String) { Log.e(tag, message) }
        override fun logWarn(tag: String, message: String) { Log.w(tag, message) }
        override fun logInfo(tag: String, message: String) { Log.i(tag, message) }
        override fun logDebug(tag: String, message: String) { Log.d(tag, message) }
        override fun logVerbose(tag: String, message: String) { Log.v(tag, message) }
        override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) { Log.e(tag, message, e) }
        override fun logStackTrace(tag: String, e: Exception) { Log.e(tag, e.message, e) }
    }
    suspend fun create(projectId: String, title: String = "交互终端", command: List<String>? = null, executionId: String = UUID.randomUUID().toString(), cwd: String = "."): Entry {
        val directory = graph.executions.directory(projectId, cwd)
        val requested = command ?: graph.root.wrap(listOf("/system/bin/sh", "-i"))
        val rootPidFile = if (requested.first() == graph.root.su && requested.getOrNull(1) == "-c") File(directory, ".easylsp-pty-$executionId.pid") else null
        val actual = if (rootPidFile == null) requested else listOf(graph.root.su, "-c", "echo \$\$ > ${RootCommand.quote(rootPidFile.path)}; ${requested[2]}")
        return withContext(Dispatchers.Main) {
            val environment = System.getenv().toMutableMap().apply { put("TERM", "xterm-256color"); put("HOME", directory.path); put("TMPDIR", graph.cacheDir.path); put("EASYLSP_WORKSPACE", projectId); put("EASYLSP_SCOPE", graph.executions.workspace(projectId).scope) }.map { "${it.key}=${it.value}" }.toTypedArray()
            val session = TerminalSession(actual.first(), directory.path, actual.toTypedArray(), environment, 3000, client)
            val entry = Entry(executionId, projectId, title, session, rootPidFile, graph.executions.workspace(projectId).scope)
            finished[entry.id] = CompletableDeferred()
            entries.value = entries.value + entry
            session.updateSize(90, 28, 9, 18)
            entry
        }
    }
    fun requireEntry(projectId: String, id: String) = entries.value.firstOrNull { it.id == id && it.workspaceId == projectId } ?: error("终端不属于当前项目")
    fun input(projectId: String, id: String, text: String) { val entry = requireEntry(projectId, id); main.post { entry.session.write(text) } }
    private suspend fun finish(entry: Entry) {
        try {
            if (entry.session.isRunning && entry.rootPidFile?.exists() == true) graph.executions.runner.terminateRootTree(entry.rootPidFile.readText().trim().toLong())
        } finally {
            withContext(Dispatchers.Main) { entry.session.finishIfRunning() }
            entry.rootPidFile?.delete()
        }
    }
    fun stop(projectId: String, id: String) { val entry = requireEntry(projectId, id); scope.launch { finish(entry) } }
    suspend fun closeExecutions(ids: Set<String>) {
        val owned = entries.value.filter { it.id in ids }
        owned.forEach { finish(it) }
        withContext(Dispatchers.Main) { entries.value = entries.value.filter { it.id !in ids } }
        owned.forEach { finished.remove(it.id) }
    }
    suspend fun closeWorkspace(id: String) {
        val owned = entries.value.filter { it.workspaceId == id }
        owned.forEach { finish(it) }
        withContext(Dispatchers.Main) { entries.value = entries.value.filter { it.workspaceId != id } }
        owned.forEach { finished.remove(it.id) }
    }
    suspend fun execute(projectId: String, id: String, command: List<String>, cwd: String, timeout: Int, output: (String) -> Unit): Int {
        val entry = create(projectId, command.joinToString(" "), command, id, cwd)
        try { return withTimeout(timeout * 1000L) { requireNotNull(finished[id]).await() } }
        finally {
            withContext(NonCancellable) {
                finish(entry)
                withContext(Dispatchers.Main) { graph.executions.store.replaceOutput(id, entry.session.emulator.screen.transcriptText) }
            }
        }
    }
}
