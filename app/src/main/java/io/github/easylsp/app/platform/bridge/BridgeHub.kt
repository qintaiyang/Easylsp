package io.github.easylsp.app.platform.bridge

import android.content.Context
import android.os.*
import io.github.easylsp.api.internal.Protocol
import io.github.easylsp.api.internal.ipc.*
import io.github.easylsp.app.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class BridgeHub(private val context: Context, private val store: ProjectStore, private val logs: LogStore) {
    data class Instance(val revision: Long, val instanceId: String, val state: String, val error: String)
    data class Session(val id: String, val packageName: String, val processName: String, val pid: Int, val uid: Int, val callback: IHostCallback, val death: IBinder.DeathRecipient)
    private data class Pending(val sessionId: String, val result: CompletableDeferred<String>)
    val sessions = MutableStateFlow<Map<String, Session>>(emptyMap())
    val instances = MutableStateFlow<Map<String, Instance>>(emptyMap())
    private val pending = ConcurrentHashMap<String, Pending>()
    private val eventThread = HandlerThread("Easylsp-events").apply { start() }
    private val events = Handler(eventThread.looper)
    private fun ownSession(id: String): Session {
        val session = sessions.value[id] ?: throw SecurityException("会话已结束")
        if (session.uid != Binder.getCallingUid()) throw SecurityException("会话不属于当前 UID")
        return session
    }
    val binder = object : IEasylspBridge.Stub() {
        override fun registerHost(protocolVersion: Int, packageName: String, processName: String, pid: Int, callback: IHostCallback): String {
            require(protocolVersion == Protocol.VERSION) { "协议版本不兼容" }
            val uid = Binder.getCallingUid()
            if (pid != Binder.getCallingPid()) throw SecurityException("PID 不匹配")
            if (context.packageManager.getPackagesForUid(uid)?.contains(packageName) != true) throw SecurityException("包名不属于当前 UID")
            if (processName != packageName && !processName.startsWith("$packageName:")) throw SecurityException("进程不属于目标应用")
            runBlocking(Dispatchers.IO) { if (store.dao.projectByPackage(packageName) == null) throw SecurityException("请先在管理端选择目标应用") }
            val id = UUID.randomUUID().toString()
            val death = IBinder.DeathRecipient { disconnect(id) }
            callback.asBinder().linkToDeath(death, 0)
            synchronized(this@BridgeHub) {
                sessions.value.values.filter { it.packageName == packageName && it.processName == processName }.forEach { disconnect(it.id) }
                sessions.update { it + (id to Session(id, packageName, processName, pid, uid, callback, death)) }
            }
            diagnosticEvent(Session(id, packageName, processName, pid, uid, callback, death), "session_registered")
            return id
        }
        override fun listPlugins(sessionId: String): MutableList<Bundle> {
            val session = ownSession(sessionId)
            return runBlocking(Dispatchers.IO) {
                val project = requireNotNull(store.dao.projectByPackage(session.packageName))
                store.dao.plugins(project.id).filter { it.runtime == "BEANSHELL" && PluginProcesses.matches(it, session.packageName, session.processName) }.map { plugin -> Bundle().apply {
                    putString("runtime", plugin.runtime); putString("pluginId", plugin.id); putLong("revision", plugin.revision); putBoolean("desiredEnabled", plugin.desiredEnabled)
                } }.toMutableList()
            }
        }
        override fun openPlugin(sessionId: String, pluginId: String, revision: Long): ParcelFileDescriptor {
            val session = ownSession(sessionId)
            return runBlocking(Dispatchers.IO) {
                val project = requireNotNull(store.dao.projectByPackage(session.packageName))
                val plugin = store.dao.plugin(pluginId) ?: throw IllegalArgumentException("插件不存在")
                if (plugin.runtime != "BEANSHELL" || plugin.projectId != project.id || !PluginProcesses.matches(plugin, session.packageName, session.processName)) throw SecurityException("不能读取其他项目或进程的插件")
                ParcelFileDescriptor.open(store.sourceFile(plugin.copy(revision = revision)), ParcelFileDescriptor.MODE_READ_ONLY)
            }
        }
        private fun ownPlugin(session: Session, pluginId: String, revision: Long): PluginEntity {
            val plugin = requireNotNull(store.dao.plugin(pluginId))
            require(plugin.runtime == "BEANSHELL") { "native 插件不使用 Java Binder 加载链路" }
            val project = requireNotNull(store.dao.project(plugin.projectId))
            require(project.packageName == session.packageName && PluginProcesses.matches(plugin, session.packageName, session.processName)) { "插件不属于当前目标" }
            require(revision in 1..plugin.revision) { "不存在该插件版本" }
            return plugin.copy(revision = revision)
        }
        override fun listPluginLibraries(sessionId: String, pluginId: String, revision: Long): MutableList<String> { val session = ownSession(sessionId); return runBlocking(Dispatchers.IO) { store.libraries(ownPlugin(session, pluginId, revision)).map { it.name }.toMutableList() } }
        override fun openPluginLibrary(sessionId: String, pluginId: String, revision: Long, name: String): ParcelFileDescriptor { val session = ownSession(sessionId); return runBlocking(Dispatchers.IO) {
            val plugin = ownPlugin(session, pluginId, revision)
            require(name.isNotBlank() && !name.contains('/') && !name.contains('\\'))
            ParcelFileDescriptor.open(java.io.File(store.libraryDirectory(plugin), name), ParcelFileDescriptor.MODE_READ_ONLY)
        } }
        override fun publish(sessionId: String, event: Bundle) {
            val session = ownSession(sessionId)
            events.post { if (sessions.value.containsKey(sessionId)) receive(session, event) }
        }
    }
    @Synchronized private fun disconnect(id: String) {
        val session = sessions.value[id] ?: return
        diagnosticEvent(session, "binder_disconnected")
        session.callback.asBinder().unlinkToDeath(session.death, 0)
        sessions.update { it - id }
        instances.update { map -> map.filterKeys { !it.startsWith("$id/") } }
        pending.entries.filter { it.value.sessionId == id }.forEach { entry -> pending.remove(entry.key)?.result?.completeExceptionally(IllegalStateException("目标进程已断开")) }
    }
    private fun diagnosticEvent(session: Session, phase: String, runId: String? = null) {
        (context.applicationContext as io.github.easylsp.app.EasylspApp).diagnostics.bridgeEvent(session.packageName, session.processName, session.pid, session.uid, session.id, phase, runId)
    }
    private fun receive(session: Session, event: Bundle) {
        when (event.getString("type")) {
            "native" -> {
                val graph = context.applicationContext as io.github.easylsp.app.EasylspApp
                try { graph.nativeInstances.receive(session, event.getString("nativeEvent", "")) }
                catch (error: IllegalArgumentException) { android.util.Log.w("Easylsp", "拒绝 native 事件", error) }
                catch (error: org.json.JSONException) { android.util.Log.w("Easylsp", "拒绝无效 native JSON", error) }
            }
            "diagnostic" -> diagnosticEvent(session, "session_connected", event.getString("diagnosticRunId"))
            "result" -> {
                val id = event.getString("requestId") ?: return
                val call = pending[id] ?: return
                if (call.sessionId != session.id) return
                pending.remove(id)
                if (event.getBoolean("success")) call.result.complete(event.getString("message", "已完成"))
                else call.result.completeExceptionally(IllegalStateException(event.getString("message", "请求失败")))
            }
            "state" -> {
                val id = event.getString("pluginId") ?: return
                instances.update { it + ("${session.id}/$id" to Instance(event.getLong("revision"), event.getString("instanceId", ""), event.getString("state", "UNKNOWN"), event.getString("error", ""))) }
                appendLog(session, event, "STATE", "${event.getString("state")} ${event.getString("error", "")}")
            }
            "log" -> appendLog(session, event, event.getString("level", "INFO"), event.getString("message", ""))
        }
    }
    private fun appendLog(session: Session, event: Bundle, level: String, message: String) {
        logs.append(JSONObject().put("timestamp", event.getLong("timestamp", System.currentTimeMillis())).put("packageName", session.packageName)
            .put("processName", session.processName).put("pid", session.pid).put("sessionId", session.id)
            .put("pluginId", event.getString("pluginId", "")).put("revision", event.getLong("revision"))
            .put("instanceId", event.getString("instanceId", "")).put("level", level).put("message", message.take(16000)))
    }
    fun session(packageName: String, processName: String = packageName) = sessions.value.values.singleOrNull { it.packageName == packageName && it.processName == processName }
    suspend fun command(packageName: String, operation: String, pluginId: String? = null): String {
        val plugin = withContext(Dispatchers.IO) { pluginId?.let { requireNotNull(store.dao.plugin(it)) } }
        val targets = sessions.value.values.filter { it.packageName == packageName && if (plugin == null) it.processName == packageName else PluginProcesses.matches(plugin, packageName, it.processName) }
        require(targets.isNotEmpty()) { "目标进程未连接" }
        return targets.map { "${it.processName}: ${commandSession(it.id, operation, pluginId)}" }.joinToString("\n")
    }
    suspend fun commandSession(sessionId: String, operation: String, pluginId: String? = null, arguments: Bundle = Bundle()): String = withContext(Dispatchers.IO) {
        val session = sessions.value[sessionId] ?: error("目标会话已断开，请重新选择")
        val packageName = session.packageName
        val id = UUID.randomUUID().toString()
        val args = Bundle(arguments)
        val plugin = pluginId?.let { requireNotNull(store.dao.plugin(it)) { "插件不存在" } }
        if (plugin != null) {
            require(plugin.runtime == "BEANSHELL") { "native 请构建、发布明确 buildId 并重启；不支持 Java load/reload/unload" }
            val project = requireNotNull(store.dao.projectByPackage(packageName))
            require(plugin.projectId == project.id && PluginProcesses.matches(plugin, packageName, session.processName)) { "插件不属于当前项目/进程" }
            if (operation == "loadPlugin" || operation == "reloadPlugin") require(plugin.desiredEnabled) { "请先启用插件配置" }
            args.putString("pluginId", plugin.id); args.putLong("revision", plugin.revision)
        }
        val result = CompletableDeferred<String>()
        pending[id] = Pending(session.id, result)
        try {
            val fd = if (operation == "loadPlugin" || operation == "reloadPlugin") ParcelFileDescriptor.open(store.sourceFile(requireNotNull(plugin)), ParcelFileDescriptor.MODE_READ_ONLY) else null
            fd.use { session.callback.onRequest(id, session.id, operation, args, it) }
            withTimeout(15000) { result.await() }
        } finally { pending.remove(id) }
    }
}
