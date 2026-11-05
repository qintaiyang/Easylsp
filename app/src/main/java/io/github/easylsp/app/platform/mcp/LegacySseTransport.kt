package io.github.easylsp.app.platform.mcp

import io.github.easylsp.app.data.McpServerEntity
import io.github.easylsp.app.platform.network.*
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** MCP 2024 HTTP+SSE: server advertises a POST endpoint; responses arrive on the GET stream. */
class LegacySseTransport(private val server: McpServerEntity, private val token: String) : AutoCloseable {
    private val client = OkHttpClient.Builder().retryOnConnectionFailure(false).readTimeout(0, TimeUnit.SECONDS).build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val endpoint = CompletableDeferred<String>()
    private val pending = ConcurrentHashMap<String, CompletableDeferred<JSONObject>>()
    private var stream: Job? = null
    private fun builder(url: String) = Request.Builder().url(url).apply { if (token.isNotEmpty()) header("Authorization", "Bearer $token") }
    private fun connect() {
        if (stream != null) return
        stream = scope.launch {
            try {
                HttpCalls.execute(client, builder(server.url).header("Accept", "text/event-stream").get().build()) { response ->
                    SseReader.read(requireNotNull(response.body).source()) { event, data ->
                        if (event == "endpoint") {
                            val base = server.url.toHttpUrl(); val post = requireNotNull(base.resolve(data))
                            require(base.scheme == post.scheme && base.host == post.host && base.port == post.port) { "MCP SSE endpoint 必须与服务同源" }
                            endpoint.complete(post.toString())
                        } else if (event == "message") {
                            val message = JSONObject(data)
                            if (message.has("id")) pending.remove(message.getString("id"))?.complete(message)
                        }
                        true
                    }
                }
                error("MCP SSE 连接已关闭")
            } catch (e: Exception) { endpoint.completeExceptionally(e); pending.values.forEach { it.completeExceptionally(e) }; pending.clear() }
        }
    }
    suspend fun post(payload: JSONObject): JSONObject? = withTimeout(60000) {
        connect()
        val url = endpoint.await()
        val id = payload.optString("id")
        val answer = if (id.isNotEmpty()) CompletableDeferred<JSONObject>().also { pending[id] = it } else null
        try {
            HttpCalls.execute(client, builder(url).post(payload.toString().toRequestBody("application/json".toMediaType())).build()) { Unit }
            answer?.await()
        } finally { if (id.isNotEmpty()) pending.remove(id) }
    }
    override fun close() { scope.cancel(); pending.values.forEach { it.cancel() }; pending.clear() }
}
