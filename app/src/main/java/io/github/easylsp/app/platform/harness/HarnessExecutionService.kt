package io.github.easylsp.app.platform.harness

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.util.Log
import io.github.easylsp.app.MainActivity
import io.github.easylsp.app.EasylspApp
import io.github.easylsp.app.platform.tools.ToolDefinition
import io.github.easylsp.app.platform.tools.BoundToolCatalog
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File

/** Owns the live Android tool bridge and host socket independently of any Activity. */
class HarnessExecutionService : Service() {
    companion object {
        private const val CHANNEL = "harness-execution"
        private const val NOTIFICATION = 31
        fun start(graph: EasylspApp) { graph.startForegroundService(Intent(graph, HarnessExecutionService::class.java)) }
    }
    inner class LocalBinder : Binder() { val service get() = this@HarnessExecutionService }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var ready = CompletableDeferred<HarnessConnection>()
    private val sockets = Mutex()
    private var reader: Job? = null
    private val sessionClients = java.util.concurrent.ConcurrentHashMap<String, HarnessSessionClient>()
    internal fun observation(id: String) = sessionClients[id]
    private val frames = MutableSharedFlow<JSONObject>(extraBufferCapacity = 64)
    val events = frames.asSharedFlow()
    private val graph get() = application as EasylspApp
    private var connection: HarnessConnection? = null
    private data class Binding(val bridge: HarnessToolBridge, val tools: List<ToolDefinition>, val config: String)
    private val bindings = java.util.concurrent.ConcurrentHashMap<String, Binding>()
    private val openings = Mutex()
    private var bubble: ExecutionBubble? = null
    var disconnected = false
        private set
    val ended = CompletableDeferred<Unit>()
    override fun onCreate() {
        super.onCreate()
        val notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel(CHANNEL, "AI 开发任务", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).putExtra("route", "conversations"), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 1, Intent(this, HarnessExecutionService::class.java).setAction("STOP"), PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Easylsp 开发任务").setContentText("会话宿主与设备工具已连接，离开页面后可继续任务")
            .setContentIntent(open).setOngoing(true).addAction(Notification.Action.Builder(null, "停止", stop).build()).build()
        startForeground(NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        showBubble()
        scope.launch {
            try {
                graph.harness.start()
                reconnect()
            } catch (error: Exception) {
                ready.completeExceptionally(error)
                graph.harness.serviceStatus.value = if (error is CancellationException) "执行服务已停止" else "执行服务已断开：${error.message}"
                stopSelf()
            }
        }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        showBubble()
        if (intent?.action == "STOP") scope.launch {
            try { graph.harness.stop() }
            finally { stopSelf() }
        }
        return START_NOT_STICKY
    }
    private fun showBubble() {
        if (bubble == null && android.provider.Settings.canDrawOverlays(this)) bubble = ExecutionBubble(this)
    }
    override fun onBind(intent: Intent) = LocalBinder()
    suspend fun request(method: String, params: JSONObject = JSONObject()): JSONObject = ready.await().request(method, params)

    /** Reconnect only the client socket; existing tools and the SDK task keep running. */
    suspend fun reconnect() = sockets.withLock {
        reader?.cancelAndJoin()
        connection?.close()
        if (ready.isCompleted) ready = CompletableDeferred()
        try {
            val client = graph.harness.connect()
            connection = client; ready.complete(client); disconnected = false
            sessionClients.values.forEach { it.synchronize() }
            val concurrency = graph.getSharedPreferences("harness-settings", 0).getInt("concurrency", 0)
            if (concurrency > 0) client.request("host/configure", JSONObject().put("concurrency", concurrency))
            graph.harness.serviceStatus.value = "执行服务已连接"
            reader = scope.launch {
                try {
                    for (frame in client.notifications) {
                        val params = frame.optJSONObject("params")
                        sessionClients.values.firstOrNull { it.sessionId == params?.optString("sessionId") }?.accept(frame)
                        if (frame.optString("method") == "mcp.credentials") graph.mcp.credentials(params!!.getString("serverId"), params.getJSONObject("credentials"))
                        if (frame.optString("method") == "mcp.catalog-changed") graph.mcp.invalidate(params!!.getString("serverId"))
                        if (frame.optString("method") == "session.admission-stopped") graph.store.workflows.inputStatus(params!!.getString("requestId"), "STOPPED")
                        if (frame.optString("method") == "session.admitted") graph.chat.admitted(params!!)
                        frames.emit(frame)
                    }
                } catch (error: CancellationException) { throw error }
                catch (error: Exception) {
                    Log.e("HarnessExecution", "Session projection stopped", error)
                    disconnected = true
                    sessionClients.values.forEach { it.disconnected() }
                    graph.harness.serviceStatus.value = "会话显示已断开：${error.message}；重新连接可补读历史"
                }
            }
        } catch (error: Exception) { ready.completeExceptionally(error); throw error }
    }

    internal suspend fun observeConversation(conversationId: String): HarnessSessionClient {
        ready.await()
        return sockets.withLock {
            val mapping = requireNotNull(graph.store.workflows.harnessSession(conversationId))
            val client = sessionClients[conversationId]?.takeIf { it.sessionId == mapping.sessionId } ?: HarnessSessionClient(graph.store, conversationId, ::request).also { sessionClients[conversationId] = it }
            client.synchronize()
            client
        }
    }

    /** Config carries no bridge address from callers; the service owns that authenticated boundary. */
    internal suspend fun open(sessionId: String, config: JSONObject, resume: Boolean = false, reconfigure: Boolean = false, refreshTools: Boolean = false, parentSessionId: String? = null, resources: suspend () -> BoundToolCatalog): JSONObject = openings.withLock {
        val client = ready.await()
        val states = client.request("host/sessions").getJSONArray("sessions")
        val state = (0 until states.length()).map { states.getJSONObject(it) }.firstOrNull { it.optString("sessionId") == sessionId }
        val same = state != null && state.getString("status") != "exited"
        val old = bindings[sessionId]
        var change = same && ((reconfigure && old?.config != config.toString()) || (old == null && !File(graph.filesDir, "harness/bridges/$sessionId.json").exists()))
        if (same && old != null && (!change && !refreshTools || state!!.getString("status") in setOf("running", "queued"))) return@withLock state!!
        val catalog = resources()
        val nextTools = catalog.tools
        if (same && old != null && old.tools.map { it.json().toString() } != nextTools.map { it.json().toString() }) change = true
        val bridge = if (same && old != null) old.bridge.also { it.replace(nextTools, catalog.capabilities) } else {
            old?.bridge?.close()
            val metadata = File(graph.filesDir, "harness/bridges/$sessionId.json").also { it.parentFile!!.mkdirs() }
            val saved = if (same && metadata.exists()) JSONObject(metadata.readText()) else null
            val secretId = "harness-bridge:$sessionId"
            val token = if (saved != null) graph.secrets.get(secretId) else java.util.UUID.randomUUID().toString()
            HarnessToolBridge(nextTools, saved?.getInt("port") ?: 0, token, catalog.capabilities).also {
                graph.secrets.put(secretId, token)
                metadata.writeText(JSONObject().put("sessionId", sessionId).put("port", it.port).toString())
            }
        }
        old?.tools?.forEach { it.close() }
        bindings[sessionId] = Binding(bridge, nextTools, config.toString())
        if (same && !change) return@withLock state!!
        client.request("session/open", JSONObject().put("sessionId", sessionId).put("resume", resume || (same && change))
            .put("parentSessionId", parentSessionId).put("reconfigure", change)
            .put("config", JSONObject(config.toString()).put("bridgeURL", bridge.url).put("bridgeToken", bridge.token)))
    }
    internal fun capabilities(sessionId: String) = bindings[sessionId]?.bridge?.selection()
    override fun onDestroy() {
        bubble?.close()
        sessionClients.keys.forEach { graph.phone.release(it) }
        bindings.values.forEach { it.bridge.close(); it.tools.forEach { tool -> tool.close() } }; bindings.clear()
        connection?.close(); scope.cancel()
        ended.complete(Unit)
        graph.harness.serviceStatus.value = "执行服务已停止"
        super.onDestroy()
    }
}
