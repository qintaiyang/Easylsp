package io.github.easylsp.app.platform.harness

import io.github.easylsp.app.data.ProviderEntity
import org.json.JSONArray
import org.json.JSONObject

/** Only explicit provider capabilities enter the SDK profile. */
object HarnessConfig {
    fun model(provider: ProviderEntity, secret: String, prompt: String): JSONObject {
        val options = io.github.easylsp.app.platform.ai.ModelCapabilities.resolve(provider.model, provider.baseUrl, JSONObject(provider.options))
        require(options.optString("authMode") == "none" || secret.isNotBlank()) { "请填写模型 API Key" }
        io.github.easylsp.app.platform.ai.ModelCapabilities.validate(options)
        val capacity = options.optInt("contextWindow")
        val budget = options.optInt("contextBudget", capacity)
        val output = options.optInt("maxOutputTokens", minOf(options.optInt("modelMaxOutputTokens", 32768), 32768, budget / 4))
        val path = options.optString("requestPath", when (provider.protocol) {
            "anthropic" -> "/messages"; "gemini" -> "/models/{model}:streamGenerateContent?alt=sse"; else -> "/chat/completions"
        })
        val api = when (provider.protocol) {
            "openai" -> if (path.endsWith("/responses")) "openai-responses" else "openai-completions"
            "anthropic" -> "anthropic-messages"
            "gemini" -> "gemini"
            else -> error("不支持的模型协议：${provider.protocol}")
        }
        options.optJSONArray("protocols")?.let { protocols ->
            require((0 until protocols.length()).any { protocols.getString(it) == api }) { "该模型能力声明不支持当前协议 " + api }
        }
        val suffix = when (api) { "openai-responses" -> "/responses"; "openai-completions" -> "/chat/completions"; "anthropic-messages" -> "/messages"; else -> "/models/{model}:streamGenerateContent?alt=sse" }
        require(path.endsWith(suffix)) { "Harness 不支持此请求路径：$path" }
        val base = provider.baseUrl.trimEnd('/') + path.removeSuffix(suffix)
        return JSONObject().put("api", api).put("baseURL", base).put("model", provider.model).put("secret", if (options.optString("authMode") == "none") "local-no-auth" else secret)
            .put("contextWindow", capacity).put("contextBudget", budget).put("maxTokens", output).put("summaryMaxTokens", options.optInt("summaryMaxTokens", minOf(16384, budget / 4)))
            .put("timeoutMs", provider.timeoutSeconds * 1000L).put("maxRequests", provider.maxSteps)
            .put("supportsImages", options.optBoolean("supportsImages")).put("prompt", prompt).apply {
                listOf("reasoningEfforts", "reasoningEffort", "thinkingBudget", "outputTokenField", "summaryMaxTokens", "maxParallelToolCalls").forEach { if (options.has(it)) put(it, options.get(it)) }
            }
    }

    fun input(text: String, attachments: JSONArray, supportsImages: Boolean): JSONArray = JSONArray()
        .put(JSONObject().put("type", "text").put("text", text)).apply {
            for (index in 0 until attachments.length()) {
                val part = attachments.getJSONObject(index)
                when (part.getString("type")) {
                    "text" -> put(part)
                    "image_url" -> {
                        require(supportsImages) { "此模型尚未声明支持图片" }
                        val url = part.getJSONObject("image_url").getString("url")
                        val match = requireNotNull(Regex("^data:(image/(?:png|jpeg|webp|gif));base64,(.+)$", RegexOption.DOT_MATCHES_ALL).matchEntire(url)) { "附件必须是应用保存的 PNG/JPEG/WebP/GIF 图片" }
                        put(JSONObject().put("type", "image").put("mimeType", match.groupValues[1]).put("data", match.groupValues[2]))
                    }
                    else -> error("不支持的附件类型：${part.getString("type")}")
                }
            }
        }
}
