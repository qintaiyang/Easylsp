package io.github.easylsp.app.platform.ai

import io.github.easylsp.app.data.ProviderEntity
import io.github.easylsp.app.platform.network.HttpCalls
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.*
import java.util.concurrent.TimeUnit

data class ProviderPreset(val name: String, val protocol: String, val url: String, val requestPath: String, val modelsPath: String = "/models")
object ProviderPresets {
    val all = listOf(
        ProviderPreset("OpenAI", "openai", "https://api.openai.com/v1", "/chat/completions"),
        ProviderPreset("Anthropic", "anthropic", "https://api.anthropic.com/v1", "/messages"),
        ProviderPreset("DeepSeek", "openai", "https://api.deepseek.com/v1", "/chat/completions"),
        ProviderPreset("OpenRouter", "openai", "https://openrouter.ai/api/v1", "/chat/completions"),
        ProviderPreset("硅基流动", "openai", "https://api.siliconflow.cn/v1", "/chat/completions"),
        ProviderPreset("阿里云百炼", "openai", "https://dashscope.aliyuncs.com/compatible-mode/v1", "/chat/completions"),
        ProviderPreset("Gemini", "gemini", "https://generativelanguage.googleapis.com/v1beta", "/models/{model}:streamGenerateContent?alt=sse"),
        ProviderPreset("Ollama", "openai", "http://127.0.0.1:11434/v1", "/chat/completions"),
        ProviderPreset("自定义", "openai", "", "/chat/completions")
    )
}
data class RemoteModel(val id: String, val name: String, val contextWindow: Int = 0)
class ProviderModels {
    private val client = OkHttpClient.Builder().retryOnConnectionFailure(false).callTimeout(30, TimeUnit.SECONDS).build()
    suspend fun list(provider: ProviderEntity, secret: String): List<RemoteModel> {
        val options = JSONObject(provider.options)
        var next: String? = null
        val rows = mutableListOf<RemoteModel>()
        do {
            val url = (provider.baseUrl.trimEnd('/') + options.optString("modelsPath", "/models")).toHttpUrl().newBuilder().apply {
                next?.let { addQueryParameter(if (provider.protocol == "gemini") "pageToken" else "after_id", it) }
            }.build()
            val request = Request.Builder().url(url).apply {
                when (provider.protocol) {
                    "anthropic" -> { header("anthropic-version", "2023-06-01"); header("x-api-key", secret) }
                    "gemini" -> header("x-goog-api-key", secret)
                    else -> if (secret.isNotEmpty()) header("Authorization", "Bearer $secret")
                }
            }.build()
            val data = HttpCalls.execute(client, request) { JSONObject(requireNotNull(it.body).string()) }
            val array = if (provider.protocol == "gemini") data.getJSONArray("models") else data.getJSONArray("data")
            for (i in 0 until array.length()) {
                val model = array.getJSONObject(i)
                if (provider.protocol != "gemini" || model.optJSONArray("supportedGenerationMethods")?.toString()?.contains("generateContent") == true) {
                    val id = if (provider.protocol == "gemini") model.getString("name").removePrefix("models/") else model.getString("id")
                    rows += RemoteModel(id, model.optString("displayName", model.optString("name", id)), model.optInt("context_length", model.optInt("inputTokenLimit", model.optJSONObject("top_provider")?.optInt("context_length") ?: 0)))
                }
            }
            next = if (provider.protocol == "gemini") data.optString("nextPageToken").takeIf { it.isNotBlank() } else if (data.optBoolean("has_more")) data.getString("last_id") else null
        } while (next != null)
        return rows.distinctBy { it.id }.sortedBy { it.id }
    }
}
