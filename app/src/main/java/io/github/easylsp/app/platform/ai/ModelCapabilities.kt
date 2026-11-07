package io.github.easylsp.app.platform.ai

import org.json.*

/** Missing metadata is unknown. Explicit values always take precedence over catalog defaults. */
object ModelCapabilities {
    val fields = setOf("contextWindow", "maxInputTokens", "modelMaxOutputTokens", "supportsImages", "reasoningEfforts", "protocols", "contextBudget", "maxOutputTokens")
    fun resolve(id: String, baseUrl: String, explicit: JSONObject, remote: JSONObject = JSONObject()): JSONObject {
        val out = JSONObject(explicit.toString())
        val sources = out.optJSONObject("capabilitySources") ?: JSONObject()
        var updated = false
        fields.filter { out.has(it) && !sources.has(it) }.forEach { sources.put(it, "user"); updated = true }
        fun fill(key: String, value: Any?, source: String) {
            if (!out.has(key) && value != null && value != JSONObject.NULL) { out.put(key, value); sources.put(key, source); updated = true }
        }
        fields.filter { remote.has(it) }.forEach { fill(it, remote.get(it), remote.optJSONObject("capabilitySources")?.optString(it, "provider:" + baseUrl) ?: "provider:" + baseUrl) }
        if (java.net.URI(baseUrl).host == "api.openai.com" && id in setOf("gpt-6-astra", "gpt-6.1-sol")) {
            val source = "https://developers.openai.com/api/docs/models/" + id + "#2026-11-07"
            fill("contextWindow", 1050000, source)
            fill("modelMaxOutputTokens", 128000, source)
            fill("supportsImages", true, source)
            fill("reasoningEfforts", JSONArray(listOf("low", "medium", "high", "xhigh", "max")), source)
            fill("protocols", JSONArray(if (id == "gpt-6.1-sol") listOf("openai-responses") else listOf("openai-responses", "openai-completions")), source)
        }
        if (out.optInt("contextWindow") > 0) fill("contextBudget", minOf(200000, out.getInt("contextWindow")), "application-budget")
        out.put("capabilitySources", sources)
        if (updated) out.put("capabilitiesUpdatedAt", System.currentTimeMillis())
        return out
    }
    fun metadata(model: JSONObject): JSONObject = JSONObject().apply {
        val top = model.optJSONObject("top_provider")
        val context = model.optInt("context_length", model.optInt("inputTokenLimit", top?.optInt("context_length") ?: 0))
        if (context > 0) put("contextWindow", context)
        val output = model.optInt("outputTokenLimit", model.optInt("max_output_tokens", top?.optInt("max_completion_tokens") ?: 0))
        if (output > 0) put("modelMaxOutputTokens", output)
        if (model.has("inputTokenLimit")) put("maxInputTokens", model.getInt("inputTokenLimit"))
        if (model.has("supports_images")) put("supportsImages", model.getBoolean("supports_images"))
        model.optJSONObject("architecture")?.optJSONArray("input_modalities")?.let { modalities ->
            put("supportsImages", (0 until modalities.length()).any { modalities.getString(it) == "image" })
        }
        model.optJSONArray("reasoning_efforts")?.let { put("reasoningEfforts", it) }
        model.optJSONArray("supported_protocols")?.let { put("protocols", it) }
    }
    fun validate(options: JSONObject) {
        require(options.optInt("contextWindow") > 0) { "未识别上下文容量，请在模型高级参数中补充 contextWindow；不会用付费请求试探容量" }
        val capacity = options.getInt("contextWindow")
        val budget = options.optInt("contextBudget", minOf(capacity, 200000))
        val output = options.optInt("maxOutputTokens", minOf(options.optInt("modelMaxOutputTokens", 32768), 32768, budget / 4))
        require(budget in 1..capacity && output in 1 until budget && (!options.has("modelMaxOutputTokens") || output <= options.getInt("modelMaxOutputTokens"))) { "上下文预算或输出上限超出已声明的模型容量" }
        val effort = options.optString("reasoningEffort")
        val supported = options.optJSONArray("reasoningEfforts")
        require(effort.isEmpty() || supported != null && (0 until supported.length()).any { supported.getString(it) == effort }) { "推理强度未获模型能力声明支持，请选择自动或已识别的档位" }
    }
}
