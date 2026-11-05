package io.github.easylsp.app.platform.harness

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import okhttp3.*
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** One authenticated duplex connection. Reconnect reads state/history; it never resends RPCs. */
class HarnessConnection private constructor() : WebSocketListener(), AutoCloseable {
    private val connected = CompletableDeferred<Unit>()
    private val closed = CompletableDeferred<Unit>()
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<JSONObject>>()
    private val sequence = AtomicLong()
    private var failure: Throwable? = null
    private lateinit var socket: WebSocket
    val notifications = Channel<JSONObject>(Channel.UNLIMITED)

    companion object {
        private val client = OkHttpClient.Builder().retryOnConnectionFailure(false)
            .connectTimeout(15, TimeUnit.SECONDS).readTimeout(0, TimeUnit.SECONDS).build()
        suspend fun connect(port: Int, token: String): HarnessConnection {
            val connection = HarnessConnection()
            connection.socket = client.newWebSocket(Request.Builder().url("http://127.0.0.1:$port/session")
                .header("Authorization", "Bearer $token").build(), connection)
            try { connection.connected.await(); return connection }
            catch (error: Exception) { connection.close(); throw error }
        }
    }
    override fun onOpen(webSocket: WebSocket, response: Response) { connected.complete(Unit) }
    override fun onMessage(webSocket: WebSocket, text: String) {
        try {
            val frame = JSONObject(text)
            if (frame.has("id") && !frame.isNull("id")) {
                val request = pending.remove(frame.getLong("id")) ?: return
                if (frame.has("error")) request.completeExceptionally(IllegalStateException(frame.getJSONObject("error").getString("message")))
                else request.complete(frame.getJSONObject("result"))
            } else notifications.trySend(frame)
        } catch (error: org.json.JSONException) { fail(IOException("宿主响应格式错误", error)); webSocket.cancel() }
    }
    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { fail(t) }
    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { fail(IOException("宿主连接已关闭 ($code)")) }
    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, reason) }
    private fun fail(error: Throwable) {
        connected.completeExceptionally(error)
        synchronized(pending) {
            failure = error
            pending.values.forEach { it.completeExceptionally(error) }
            pending.clear()
        }
        notifications.close(error)
        closed.complete(Unit)
    }
    suspend fun request(method: String, params: JSONObject = JSONObject()): JSONObject {
        val id = sequence.incrementAndGet()
        val response = CompletableDeferred<JSONObject>()
        synchronized(pending) { failure?.let { throw it }; pending[id] = response }
        try {
            check(socket.send(JSONObject().put("id", id).put("method", method).put("params", params).toString())) { "宿主连接已关闭；请重新连接并查询接收状态" }
            return response.await()
        } finally { pending.remove(id) }
    }
    suspend fun awaitClosed() { closed.await() }
    override fun close() { socket.cancel(); fail(IOException("客户端已断开；宿主任务继续运行")) }
}
