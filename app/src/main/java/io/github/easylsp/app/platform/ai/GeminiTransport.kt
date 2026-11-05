package io.github.easylsp.app.platform.ai

import io.github.easylsp.app.data.ProviderEntity
import io.github.easylsp.app.platform.network.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.*
import java.util.UUID
import java.util.concurrent.TimeUnit

class GeminiTransport {
    suspend fun stream(provider: ProviderEntity, secret: String, messages: JSONArray, tools: JSONArray, chunk: (String) -> Unit): ModelReply {
        val contents = JSONArray(); val system = JSONArray(); val names = mutableMapOf<String, String>()
        for (i in 0 until messages.length()) {
            val message = messages.getJSONObject(i); val parts = JSONArray()
            when (message.getString("role")) {
                "system" -> { system.put(JSONObject().put("text", message.getString("content"))); continue }
                "tool" -> parts.put(JSONObject().put("functionResponse", JSONObject().put("name", names.getValue(message.getString("tool_call_id"))).put("response", JSONObject().put("result", message.getString("content")))))
                else -> {
                    val content = message.opt("content")
                    if (content is JSONArray) for (n in 0 until content.length()) {
                        val part = content.getJSONObject(n)
                        if (part.getString("type") == "image_url") {
                            val data = part.getJSONObject("image_url").getString("url")
                            parts.put(JSONObject().put("inlineData", JSONObject().put("mimeType", data.substringAfter("data:").substringBefore(';')).put("data", data.substringAfter(','))))
                        } else parts.put(JSONObject().put("text", part.getString("text")))
                    } else if (message.optString("content").isNotEmpty()) parts.put(JSONObject().put("text", message.getString("content")))
                    message.optJSONArray("tool_calls")?.let { calls -> for (n in 0 until calls.length()) {
                        val call = calls.getJSONObject(n); val fn = call.getJSONObject("function"); names[call.getString("id")] = fn.getString("name")
                        parts.put(JSONObject().put("functionCall", JSONObject().put("name", fn.getString("name")).put("args", JSONObject(fn.getString("arguments")))).apply { if (call.optString("thoughtSignature").isNotEmpty()) put("thoughtSignature", call.getString("thoughtSignature")) })
                    } }
                }
            }
            contents.put(JSONObject().put("role", if (message.getString("role") == "assistant") "model" else "user").put("parts", parts))
        }
        val options = JSONObject(provider.options)
        val body = JSONObject().put("contents", contents).put("systemInstruction", JSONObject().put("parts", system)).put("generationConfig", JSONObject().put("maxOutputTokens", options.optInt("maxOutputTokens", 4096)).apply {
            if (options.optInt("thinkingBudget") > 0) put("thinkingConfig", JSONObject().put("thinkingBudget", options.getInt("thinkingBudget")).put("includeThoughts", true))
        })
        if (tools.length() > 0) body.put("tools", JSONArray().put(JSONObject().put("functionDeclarations", JSONArray().apply { for (i in 0 until tools.length()) put(tools.getJSONObject(i).getJSONObject("function")) })))
        val path = options.optString("requestPath", "/models/{model}:streamGenerateContent?alt=sse").replace("{model}", provider.model)
        val request = Request.Builder().url(provider.baseUrl.trimEnd('/') + path).header("x-goog-api-key", secret).header("Accept", "text/event-stream").post(body.toString().toRequestBody("application/json".toMediaType())).build()
        val client = OkHttpClient.Builder().retryOnConnectionFailure(false).readTimeout(0, TimeUnit.SECONDS).callTimeout(provider.timeoutSeconds.toLong(), TimeUnit.SECONDS).build()
        return HttpCalls.execute(client, request) { response ->
            val text = StringBuilder(); val thought = StringBuilder(); val calls = mutableListOf<ToolCall>(); var usage = JSONObject(); var finish = ""
            SseReader.read(requireNotNull(response.body).source()) { _, data ->
                val event = JSONObject(data); event.optJSONObject("usageMetadata")?.let { raw -> usage = JSONObject(raw.toString()).apply { if (raw.has("promptTokenCount")) put("input_tokens", raw.getInt("promptTokenCount")); if (raw.has("candidatesTokenCount")) put("output_tokens", raw.getInt("candidatesTokenCount")); if (raw.has("totalTokenCount")) put("total_tokens", raw.getInt("totalTokenCount")) } }
                event.optJSONArray("candidates")?.let { candidates -> for (i in 0 until candidates.length()) {
                    val candidate = candidates.getJSONObject(i); if (candidate.has("finishReason")) finish = candidate.getString("finishReason")
                    candidate.optJSONObject("content")?.optJSONArray("parts")?.let { parts -> for (n in 0 until parts.length()) {
                        val part = parts.getJSONObject(n)
                        if (part.has("text")) { if (part.optBoolean("thought")) thought.append(part.getString("text")) else { text.append(part.getString("text")); chunk(part.getString("text")) } }
                        part.optJSONObject("functionCall")?.let { fn -> calls += ToolCall(UUID.randomUUID().toString(), fn.getString("name"), fn.optJSONObject("args")?.toString() ?: "{}", part.optString("thoughtSignature")) }
                    } }
                } }
                true
            }
            require(finish == "STOP") { "Gemini 响应未正常完成：$finish" }
            ModelReply(text.toString(), calls, usage, thought.toString())
        }
    }
}
