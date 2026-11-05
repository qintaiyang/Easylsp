package io.github.easylsp.app.platform.mcp

import io.github.easylsp.app.data.McpServerEntity
import io.github.easylsp.app.platform.network.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.*
import java.util.UUID
import java.util.concurrent.TimeUnit

/** A run owns its Streamable HTTP or traditional HTTP+SSE MCP connection. */
class McpClient(private val server: McpServerEntity, private val token: String) : AutoCloseable {
    private val legacy = if (server.transport == "sse") LegacySseTransport(server, token) else null
    override fun close() { legacy?.close() }
    private val client = OkHttpClient.Builder().retryOnConnectionFailure(false).readTimeout(0, TimeUnit.SECONDS).callTimeout(60, TimeUnit.SECONDS).build()
    private var session: String? = null
    private var protocol: String? = null
    private fun request(body: JSONObject): Request = Request.Builder().url(server.url).post(body.toString().toRequestBody("application/json".toMediaType()))
        .header("Accept", "application/json, text/event-stream").apply {
            session?.let { header("Mcp-Session-Id", it) }; protocol?.let { header("MCP-Protocol-Version", it) }
            if (token.isNotEmpty()) header("Authorization", "Bearer $token")
        }.build()
    private suspend fun rpc(method: String, params: JSONObject = JSONObject()): JSONObject {
        val id = UUID.randomUUID().toString()
        val payload = JSONObject().put("jsonrpc", "2.0").put("id", id).put("method", method).put("params", params)
        try {
            if (legacy != null) {
                val message = requireNotNull(legacy.post(payload))
                if (message.has("error")) error("MCP: ${message.getJSONObject("error").optString("message")}")
                return message.getJSONObject("result")
            }
            return HttpCalls.execute(client, request(payload)) { response ->
                response.header("Mcp-Session-Id")?.let { session = it }
                val body = requireNotNull(response.body)
                var result: JSONObject? = null
                if (body.contentType()?.subtype == "event-stream") {
                    SseReader.read(body.source()) { _, data ->
                        val item = JSONObject(data)
                        if (item.optString("id") == id) { result = item; false } else true
                    }
                } else result = JSONObject(body.string())
                val message = requireNotNull(result) { "MCP 未返回请求结果" }
                require(message.optString("id") == id) { "MCP 响应 id 不匹配" }
                if (message.has("error")) error("MCP: ${message.getJSONObject("error").optString("message")}")
                message.getJSONObject("result")
            }
        } catch (error: CancellationException) {
            // Cancellation is a protocol notification, never a replay of the original call.
            val notification = JSONObject().put("jsonrpc", "2.0").put("method", "notifications/cancelled").put("params", JSONObject().put("requestId", id).put("reason", "User cancelled"))
            if (legacy != null) withContext(NonCancellable) { legacy.post(notification) }
            else client.newCall(request(notification)).enqueue(object : Callback {
                override fun onFailure(call: Call, e: java.io.IOException) { }
                override fun onResponse(call: Call, response: Response) { response.close() }
            })
            throw error
        }
    }
    suspend fun initialize() {
        val result = rpc("initialize", JSONObject().put("protocolVersion", "2025-11-25").put("capabilities", JSONObject()).put("clientInfo", JSONObject().put("name", "Easylsp").put("version", "0.3.0")))
        val negotiated = result.getString("protocolVersion")
        require(negotiated in setOf("2024-11-05", "2025-03-26", "2025-06-18", "2025-11-25")) { "不支持 MCP 协议版本：$negotiated" }
        protocol = negotiated
        val notification = JSONObject().put("jsonrpc", "2.0").put("method", "notifications/initialized")
        if (legacy != null) legacy.post(notification) else HttpCalls.execute(client, request(notification)) { Unit }
    }
    suspend fun listTools(): List<JSONObject> {
        val tools = mutableListOf<JSONObject>(); var cursor: String? = null
        do {
            val result = rpc("tools/list", JSONObject().apply { cursor?.let { put("cursor", it) } })
            val list = result.getJSONArray("tools"); for (i in 0 until list.length()) tools += list.getJSONObject(i)
            cursor = result.optString("nextCursor").takeIf { it.isNotEmpty() }
        } while (cursor != null)
        return tools
    }
    suspend fun call(name: String, arguments: JSONObject): JSONObject = rpc("tools/call", JSONObject().put("name", name).put("arguments", arguments))
}
