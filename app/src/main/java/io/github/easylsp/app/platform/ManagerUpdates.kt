package io.github.easylsp.app.platform

import io.github.easylsp.app.platform.network.HttpCalls
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class ManagerUpdate(val version: String, val url: String)

class ManagerUpdates(
    private val endpoint: String = "https://api.github.com/repos/qintaiyang/Easylsp/releases/latest",
    private val client: OkHttpClient = OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS).build()
) {
    suspend fun check(currentVersion: String): ManagerUpdate? {
        val request = Request.Builder().url(endpoint)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "Easylsp/" + currentVersion).build()
        return HttpCalls.execute(client, request) { response ->
            val release = JSONObject(requireNotNull(response.body).string())
            if (release.getBoolean("draft") || release.getBoolean("prerelease")) return@execute null
            val tag = release.getString("tag_name")
            val latest = versionParts(tag) ?: return@execute null
            val current = requireNotNull(versionParts(currentVersion)) { "无法识别管理器版本：" + currentVersion }
            val comparison = latest.zip(current).firstOrNull { it.first != it.second }
            if (comparison == null || comparison.first < comparison.second) return@execute null
            ManagerUpdate(tag.removePrefix("v"), AppLinks.SOURCE + "/releases/tag/" + tag)
        }
    }

    private fun versionParts(value: String): List<Int>? {
        val match = Regex("^v?([0-9]+)[.]([0-9]+)[.]([0-9]+)$").matchEntire(value) ?: return null
        return match.groupValues.drop(1).map { it.toIntOrNull() ?: return null }
    }
}
