package io.github.easylsp.app.platform.ai

import io.github.easylsp.app.data.ProviderEntity
import io.github.easylsp.app.platform.network.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.*
import java.util.concurrent.TimeUnit

 data class ToolCall(val id: String, val name: String, val arguments: String, val thoughtSignature: String = "") {
    fun json() = JSONObject().put("id", id).put("type", "function").put("function", JSONObject().put("name", name).put("arguments", arguments)).apply { if (thoughtSignature.isNotEmpty()) put("thoughtSignature", thoughtSignature) }
}
data class ModelReply(val text: String, val calls: List<ToolCall>, val usage: JSONObject, val reasoning: String = "", val thinkingBlocks: JSONArray = JSONArray())

class OpenAiStreamAccumulator {
    private data class Partial(val id: StringBuilder = StringBuilder(), val name: StringBuilder = StringBuilder(), val args: StringBuilder = StringBuilder())
    private val partial = sortedMapOf<Int, Partial>()
    val text = StringBuilder()
    val reasoning = StringBuilder()
    var usage = JSONObject(); private set
    private var finish = ""
    fun feed(data: String): String {
        val value = JSONObject(data)
        if (value.has("error")) error(value.getJSONObject("error").optString("message", "模型服务错误"))
        value.optJSONObject("usage")?.let { usage = it }
        val choices = value.optJSONArray("choices") ?: return ""
        if (choices.length() == 0) return ""
        val choice = choices.getJSONObject(0)
        if (!choice.isNull("finish_reason")) finish = choice.getString("finish_reason")
        val delta = choice.optJSONObject("delta") ?: return ""
        val content = if (delta.isNull("content")) "" else delta.optString("content")
        text.append(content)
        if (!delta.isNull("reasoning_content")) reasoning.append(delta.getString("reasoning_content"))
        delta.optJSONArray("tool_calls")?.let { calls -> for (i in 0 until calls.length()) {
            val call = calls.getJSONObject(i); val item = partial.getOrPut(call.getInt("index")) { Partial() }
            if (!call.isNull("id")) item.id.append(call.getString("id"))
            call.optJSONObject("function")?.let { fn -> if (!fn.isNull("name")) item.name.append(fn.getString("name")); if (!fn.isNull("arguments")) item.args.append(fn.getString("arguments")) }
        } }
        return content
    }
    fun finish(): ModelReply {
        require(finish == "stop" || finish == "tool_calls") { "模型流未正常完成：${finish.ifEmpty { "连接中断" }}" }
        val calls = partial.values.map { ToolCall(it.id.toString(), it.name.toString(), it.args.toString()) }
        calls.forEach { require(it.id.isNotBlank() && it.name.isNotBlank()); JSONObject(it.arguments) }
        return ModelReply(text.toString(), calls, usage, reasoning.toString())
    }
}
class AnthropicStreamAccumulator {
    private val calls = sortedMapOf<Int, ToolCall>()
    private val fragments = mutableMapOf<Int, StringBuilder>()
    val text = StringBuilder()
    val reasoning = StringBuilder()
    private val usage = JSONObject()
    private var stop = ""
    private val thinking = sortedMapOf<Int, JSONObject>()
    fun feed(event: String, data: String): String {
        val value = JSONObject(data)
        when (event) {
            "error" -> error(value.getJSONObject("error").getString("message"))
            "message_start" -> value.getJSONObject("message").optJSONObject("usage")?.let { source -> source.keys().forEach { usage.put(it, source.get(it)) } }
            "content_block_start" -> {
                val block = value.getJSONObject("content_block")
                if (block.getString("type") == "thinking" || block.getString("type") == "redacted_thinking") thinking[value.getInt("index")] = JSONObject(block.toString())
                if (block.getString("type") == "tool_use") { val index = value.getInt("index"); calls[index] = ToolCall(block.getString("id"), block.getString("name"), block.optJSONObject("input")?.toString() ?: "{}"); fragments[index] = StringBuilder() }
            }
            "content_block_delta" -> {
                val delta = value.getJSONObject("delta")
                when (delta.getString("type")) {
                    "text_delta" -> return delta.getString("text").also { text.append(it) }
                    "thinking_delta" -> { reasoning.append(delta.getString("thinking")); val block = thinking.getValue(value.getInt("index")); block.put("thinking", block.optString("thinking") + delta.getString("thinking")) }
                    "signature_delta" -> { val block = thinking.getValue(value.getInt("index")); block.put("signature", block.optString("signature") + delta.getString("signature")) }
                    "input_json_delta" -> fragments.getValue(value.getInt("index")).append(delta.getString("partial_json"))
                }
            }
            "message_delta" -> { stop = value.getJSONObject("delta").optString("stop_reason"); value.optJSONObject("usage")?.let { source -> source.keys().forEach { usage.put(it, source.get(it)) } } }
        }
        return ""
    }
    fun finish(): ModelReply {
        require(stop in setOf("end_turn", "tool_use", "stop_sequence")) { "模型流未正常完成：${stop.ifEmpty { "连接中断" }}" }
        return ModelReply(text.toString(), calls.map { (index, call) -> call.copy(arguments = fragments.getValue(index).toString().ifEmpty { call.arguments }).also { JSONObject(it.arguments) } }, usage, reasoning.toString(), JSONArray(thinking.values.toList()))
    }
}
class ModelTransport {
    suspend fun stream(provider: ProviderEntity, secret: String, messages: JSONArray, tools: JSONArray, chunk: (String) -> Unit): ModelReply {
        val client = OkHttpClient.Builder().retryOnConnectionFailure(false).readTimeout(0, TimeUnit.SECONDS).callTimeout(provider.timeoutSeconds.toLong(), TimeUnit.SECONDS).build()
        if (provider.protocol == "gemini") return GeminiTransport().stream(provider, secret, messages, tools, chunk)
        val options = JSONObject(provider.options)
        val anthropic = provider.protocol == "anthropic"
        require(provider.protocol in setOf("openai", "anthropic")) { "不支持的模型协议" }
        val body = if (anthropic) anthropicBody(provider, messages, tools) else JSONObject().put("model", provider.model).put("messages", messages).put("stream", true).put("stream_options", JSONObject().put("include_usage", true)).apply { if (tools.length() > 0) put("tools", tools) }
        if (!anthropic) {
            if (options.has("reasoningEffort")) body.put("reasoning_effort", options.getString("reasoningEffort"))
            if (options.has("maxOutputTokens")) body.put(options.optString("outputTokenField", if (provider.baseUrl.startsWith("https://api.openai.com/")) "max_completion_tokens" else "max_tokens"), options.getInt("maxOutputTokens"))
        }
        val builder = Request.Builder().url(provider.baseUrl.trimEnd('/') + options.optString("requestPath", if (anthropic) "/messages" else "/chat/completions")).post(body.toString().toRequestBody("application/json".toMediaType())).header("Accept", "text/event-stream")
        if (anthropic) { builder.header("anthropic-version", "2023-06-01"); if (secret.isNotEmpty()) builder.header("x-api-key", secret) }
        else if (secret.isNotEmpty()) builder.header("Authorization", "Bearer $secret")
        return HttpCalls.execute(client, builder.build()) { response ->
            val openai = OpenAiStreamAccumulator(); val claude = AnthropicStreamAccumulator()
            SseReader.read(requireNotNull(response.body).source()) { event, data ->
                if (data == "[DONE]") false else { val text = if (anthropic) claude.feed(event, data) else openai.feed(data); if (text.isNotEmpty()) chunk(text); true }
            }
            if (anthropic) claude.finish() else openai.finish()
        }
    }
    private fun anthropicBody(provider: ProviderEntity, messages: JSONArray, tools: JSONArray): JSONObject {
        val converted = JSONArray(); val system = StringBuilder()
        for (i in 0 until messages.length()) {
            val message = messages.getJSONObject(i)
            when (message.getString("role")) {
                "system" -> system.append(message.getString("content")).append('\n')
                "tool" -> {
                    val block = JSONObject().put("type", "tool_result").put("tool_use_id", message.getString("tool_call_id")).put("content", message.getString("content"))
                    val last = if (converted.length() > 0) converted.getJSONObject(converted.length() - 1) else null
                    if (last?.optString("role") == "user" && last.opt("content") is JSONArray) last.getJSONArray("content").put(block)
                    else converted.put(JSONObject().put("role", "user").put("content", JSONArray().put(block)))
                }
                "assistant" -> {
                    val content = JSONArray()
                    message.optJSONArray("thinking_blocks")?.let { blocks -> for (n in 0 until blocks.length()) content.put(blocks.get(n)) }
                    if (message.optString("content").isNotEmpty()) content.put(JSONObject().put("type", "text").put("text", message.getString("content")))
                    message.optJSONArray("tool_calls")?.let { calls -> for (n in 0 until calls.length()) { val call = calls.getJSONObject(n); val fn = call.getJSONObject("function"); content.put(JSONObject().put("type", "tool_use").put("id", call.getString("id")).put("name", fn.getString("name")).put("input", JSONObject(fn.getString("arguments")))) } }
                    converted.put(JSONObject().put("role", "assistant").put("content", content))
                }
                else -> {
                    val content = message.get("content")
                    val blocks = if (content is JSONArray) JSONArray().apply { for (n in 0 until content.length()) {
                        val part = content.getJSONObject(n)
                        if (part.getString("type") == "image_url") {
                            val data = part.getJSONObject("image_url").getString("url")
                            put(JSONObject().put("type", "image").put("source", JSONObject().put("type", "base64").put("media_type", data.substringAfter("data:").substringBefore(';')).put("data", data.substringAfter(','))))
                        } else put(JSONObject().put("type", "text").put("text", part.getString("text")))
                    } } else content
                    converted.put(JSONObject().put("role", "user").put("content", blocks))
                }
            }
        }
        val definitions = JSONArray()
        for (i in 0 until tools.length()) { val fn = tools.getJSONObject(i).getJSONObject("function"); definitions.put(JSONObject().put("name", fn.getString("name")).put("description", fn.getString("description")).put("input_schema", fn.getJSONObject("parameters"))) }
        val options = JSONObject(provider.options)
        return JSONObject().put("model", provider.model).put("max_tokens", options.optInt("maxOutputTokens", 4096)).put("stream", true).put("system", system.toString()).put("messages", converted).apply { if (definitions.length() > 0) put("tools", definitions); if (options.optInt("thinkingBudget") > 0) put("thinking", JSONObject().put("type", "enabled").put("budget_tokens", options.getInt("thinkingBudget"))) }
    }
}
