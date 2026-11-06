package io.github.easylsp.app.platform.market

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONObject
import org.json.JSONArray
import java.io.File

class GitHubFailure(val status: Int, message: String) : IllegalStateException("GitHub $status: $message")
/** Credentials remain inside the transport; request headers are never logged. */
class GitHubClient(private val token: () -> String, private val api: String = "https://api.github.com", private val http: OkHttpClient = OkHttpClient()) {
    fun request(method: String, path: String, body: JSONObject? = null): Any {
        require(path.startsWith('/') && !path.startsWith("//"))
        val data = body?.toString()?.toRequestBody("application/json".toMediaType())
        val request = Request.Builder().url(api + path).header("Accept", "application/vnd.github+json").header("X-GitHub-Api-Version", "2022-11-28")
        val secret = token(); if (secret.isNotEmpty()) request.header("Authorization", "Bearer $secret")
        return execute(request.method(method, data).build())
    }
    fun upload(repository: String, releaseId: Long, file: File): JSONObject {
        repository(repository)
        val request = Request.Builder().url("https://uploads.github.com/repos/$repository/releases/$releaseId/assets?name=plugin.zip")
            .header("Authorization", "Bearer ${token()}").post(file.readBytes().toRequestBody("application/zip".toMediaType())).build()
        return execute(request) as JSONObject
    }
    private fun execute(request: Request): Any = http.newCall(request).execute().use { response ->
        val text = requireNotNull(response.body).string()
        if (!response.isSuccessful) {
            val message = if (text.startsWith('{')) JSONObject(text).optString("message", response.message) else response.message
            throw GitHubFailure(response.code, message)
        }
        when { text.isBlank() -> JSONObject(); text.startsWith('[') -> JSONArray(text); else -> JSONObject(text) }
    }
    companion object {
        private val tokenPattern = Regex("(?:github_pat_|gh[pousr]_)[A-Za-z0-9_]{20,}")
        fun tokenFromMessage(text: String): String {
            val tokens = tokenPattern.findAll(text).map { it.value }.distinct().toList()
            require(tokens.size == 1) { "指定消息必须包含一个明确的 GitHub 令牌" }
            return tokens.single()
        }
        fun tokenFromUserHistory(entries: List<Pair<Long, String>>): String {
            val latest = entries.filter { tokenPattern.containsMatchIn(it.second) }.maxByOrNull { it.first }
            requireNotNull(latest) { "当前会话没有用户提供的 GitHub 令牌，请配置作者账号" }
            return tokenFromMessage(latest.second)
        }
        fun repository(value: String): String { require(value.matches(Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+"))) { "仓库格式应为 owner/repo" }; return value }
    }
}
