package io.github.easylsp.app.platform.harness

import io.github.easylsp.app.util.readUpTo
import java.util.logging.Logger
import java.util.logging.Level
import io.github.easylsp.app.platform.tools.*
import kotlinx.coroutines.*
import org.json.*
import java.net.*
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Loopback MCP boundary. Its owner fixes the binding and grants tools before exposing this catalog. */
class HarnessToolBridge(@Volatile private var tools: List<ToolDefinition>, port: Int = 0, val token: String = UUID.randomUUID().toString(),
    @Volatile private var capabilities: ToolCapabilities? = null) : AutoCloseable {
    private val server = ServerSocket(port, 8, InetAddress.getByName("127.0.0.1"))
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val calls = ConcurrentHashMap<String, Job>()
    val port get() = server.localPort
    val url = "http://127.0.0.1:${server.localPort}/mcp"
    private fun registry() = (capabilities?.additionalTools().orEmpty() + tools).distinctBy { it.name }.filter { capabilities?.contains(it.name) != false }
    fun selection() = capabilities
    fun replace(tools: List<ToolDefinition>, capabilities: ToolCapabilities? = null) { this.tools = tools; this.capabilities = capabilities }
    init {
        scope.launch {
            while (isActive) {
                val socket = try { server.accept() } catch (e: SocketException) { if (isActive) throw e else break }
                launch { socket.use { handle(it) } }
            }
        }
    }
    private suspend fun handle(socket: Socket) {
        val input = socket.getInputStream().buffered()
        fun line(): String = buildString { while (true) { val c = input.read(); if (c < 0 || c == 10) break; if (c != 13) append(c.toChar()) } }
        val request = line()
        val headers = buildMap { while (true) { val row = line(); if (row.isEmpty()) break; put(row.substringBefore(':').lowercase(), row.substringAfter(':').trim()) } }
        val output = socket.getOutputStream().bufferedWriter(Charsets.UTF_8)
        fun respond(status: String, body: String = "") {
            output.write("HTTP/1.1 $status\r\nContent-Type: application/json\r\nContent-Length: ${body.toByteArray().size}\r\nConnection: close\r\n\r\n$body"); output.flush()
        }
        if (headers["authorization"] != "Bearer $token") { respond("401 Unauthorized"); return }
        if (!request.startsWith("POST /mcp ")) { respond("405 Method Not Allowed"); return }
        val size = headers["content-length"]?.toIntOrNull() ?: 0
        if (size !in 1..2 * 1024 * 1024) { respond("400 Bad Request"); return }
        val frame = JSONObject(String(input.readUpTo(size), Charsets.UTF_8))
        val method = frame.getString("method"); val params = frame.optJSONObject("params") ?: JSONObject()
        Logger.getLogger("HarnessTools").info("MCP $method id=${frame.opt("id")}")
        if (!frame.has("id")) {
            if (method == "notifications/cancelled") calls[params.get("requestId").toString()]?.cancel()
            respond("202 Accepted"); return
        }
        val id = frame.get("id")
        val response = JSONObject().put("jsonrpc", "2.0").put("id", id)
        try {
            val result = when (method) {
                "initialize" -> JSONObject().put("protocolVersion", "2025-11-25").put("capabilities", JSONObject().put("tools", JSONObject())).put("serverInfo", JSONObject().put("name", "easylsp").put("version", "0.8"))
                "ping" -> JSONObject()
                "easylsp/capabilities/resolve" -> requireNotNull(capabilities) { "此桥未启用会话能力选择" }.resolve()
                "easylsp/capabilities/read" -> requireNotNull(capabilities) { "此桥未启用会话能力选择" }.snapshot()
                "tools/list" -> JSONObject().put("tools", JSONArray(registry().map { JSONObject().put("name", it.name).put("description", it.description).put("inputSchema", it.schema).put("annotations", JSONObject().put("readOnlyHint", !it.writes)) }))
                "tools/call" -> coroutineScope {
                    calls[id.toString()] = currentCoroutineContext().job
                    try {
                        val name = params.getString("name")
                        if (capabilities?.permits(name) == false) {
                            val message = "$name 未在当前 step 声明"
                            return@coroutineScope JSONObject().put("isError", true)
                                .put("structuredContent", JSONObject().put("error", JSONObject().put("code", "TOOL_NOT_SELECTED").put("message", message)))
                                .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", message)))
                        }
                        val tool = registry().first { it.name == name }
                        val arguments = params.optJSONObject("arguments") ?: JSONObject()
                        ToolArguments.validate(tool.schema, arguments)
                        val value = tool.invoke(arguments)
                        ToolResults.mcp(value)
                    } finally { calls.remove(id.toString()) }
                }
                else -> { response.put("error", JSONObject().put("code", -32601).put("message", "Unknown method: $method")); null }
            }
            if (result != null) response.put("result", result)
        } catch (e: Exception) {
            Logger.getLogger("HarnessTools").log(Level.WARNING, "MCP $method id=$id failed", e)
            response.put("result", JSONObject().put("isError", true).put("content", JSONArray().put(JSONObject().put("type", "text").put("text", e.message ?: e.javaClass.simpleName))))
        }
        respond("200 OK", response.toString())
    }
    suspend fun closeAndJoin() { close(); scope.coroutineContext.job.join() }
    override fun close() { scope.cancel(); server.close() }
}
