package io.github.easylsp.app.platform.market

import io.github.easylsp.app.EasylspApp
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.FormBody
import org.json.JSONObject

class GitHubAccount(private val graph: EasylspApp) {
    private val key = "github-market-author"
    val client = GitHubClient({ graph.secrets.get(key) })
    private val preferences = graph.getSharedPreferences("github-market", 0)
    private val http = OkHttpClient()
    fun profile(): JSONObject = JSONObject(preferences.getString("profile", "{}")!!)
    fun tokenLogin(secret: String): JSONObject {
        val profile = GitHubClient({ secret }).request("GET", "/user") as JSONObject
        graph.secrets.put(key, secret)
        val publicProfile = JSONObject().put("id", profile.getLong("id")).put("login", profile.getString("login"))
        preferences.edit().putString("profile", publicProfile.toString()).commit()
        return publicProfile
    }
    fun logout() { graph.secrets.delete(key); preferences.edit().remove("profile").commit() }
    private fun form(url: String, fields: Map<String, String>): JSONObject {
        val body = FormBody.Builder().apply { fields.forEach { (key, value) -> add(key, value) } }.build()
        return http.newCall(Request.Builder().url(url).header("Accept", "application/json").post(body).build()).execute().use { response ->
            check(response.isSuccessful) { "GitHub Device Flow HTTP ${response.code}" }
            JSONObject(requireNotNull(response.body).string())
        }
    }
    fun beginDeviceFlow(clientId: String): JSONObject {
        require(clientId.isNotBlank()) { "请配置已启用 Device Flow 的 OAuth Client ID" }
        return form("https://github.com/login/device/code", mapOf("client_id" to clientId, "scope" to "public_repo"))
    }
    /** Called by UI at the server-provided interval; pending/slow_down are explicit states. */
    fun pollDeviceFlow(clientId: String, code: String): JSONObject {
        val result = form("https://github.com/login/oauth/access_token", mapOf("client_id" to clientId, "device_code" to code, "grant_type" to "urn:ietf:params:oauth:grant-type:device_code"))
        if (result.has("access_token")) return tokenLogin(result.getString("access_token")).put("status", "AUTHORIZED")
        return JSONObject().put("status", result.getString("error")).put("detail", result.optString("error_description"))
    }
}
