package io.github.easylsp.app.platform.market

import io.github.easylsp.app.EasylspApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import java.net.URLEncoder
import java.util.Base64

/** Each preparation is immutable; public operations reconcile their fixed GitHub objects on resume. */
class ReleasePublishingService(private val graph: EasylspApp) {
    private fun releaseRef(releaseId: String) = "easylsp-" + Base64.getUrlEncoder().withoutPadding().encodeToString(releaseId.toByteArray(Charsets.UTF_8))
    private val official = "qintaiyang/Easylsp-Market"
    private val client get() = graph.github.client
    private fun save(record: JSONObject): JSONObject {
        val file = File(graph.filesDir, "release-preparations/${record.getString("preparationId")}/preparation.json")
        val temporary = File(file.parentFile, "preparation.new").apply { writeText(record.toString(2)) }
        check(temporary.renameTo(file)); return record
    }
    private fun optional(path: String): JSONObject? = try { client.request("GET", path) as JSONObject }
        catch (error: GitHubFailure) { if (error.status == 404) null else throw error }
    suspend fun repositories(): JSONArray = withContext(Dispatchers.IO) { client.request("GET", "/user/repos?visibility=public&affiliation=owner&per_page=100&sort=updated") as JSONArray }
    suspend fun publish(id: String, repository: String, sourceCommit: String): JSONObject = withContext(Dispatchers.IO) {
        GitHubClient.repository(repository)
        require(sourceCommit.matches(Regex("[a-f0-9]{40}"))) { "必须指定完整源码 commit ID" }
        val record = graph.releasePreparations.status(id)
        if (record.has("repository")) require(record.getString("repository") == repository && record.getString("sourceCommit") == sourceCommit) { "固定准备记录不能改投另一仓库或源码版本，请重新准备发行" }
        val profile = graph.github.profile(); check(profile.has("id")) { "请先在市场登录 GitHub 作者账号" }
        val repo = client.request("GET", "/repos/$repository") as JSONObject
        require(!repo.getBoolean("private") && repo.getJSONObject("owner").getLong("id") == profile.getLong("id") && repo.getJSONObject("permissions").getBoolean("push")) { "请选择当前账号拥有并有写权限的公开仓库" }
        client.request("GET", "/repos/$repository/commits/$sourceCommit")
        val manifest = record.getJSONObject("manifest")
        require(manifest.getString("sourceUrl") == "https://github.com/$repository/tree/$sourceCommit") { "发行源码地址必须绑定所选仓库的完整 commit" }
        record.put("repository", repository).put("repositoryId", repo.getLong("id")).put("sourceCommit", sourceCommit).put("githubAccountId", profile.getLong("id"))
        save(record.put("status", "UPLOADING"))
        val tag = releaseRef(manifest.getString("releaseId"))
        val marker = "Easylsp preparation $id; source $sourceCommit"
        val release = optional("/repos/$repository/releases/tags/$tag") ?: (client.request("POST", "/repos/$repository/releases", JSONObject().put("tag_name", tag).put("target_commitish", sourceCommit).put("name", manifest.getString("name") + " " + manifest.getString("versionName")).put("body", "$marker\n\n${manifest.getString("changelog")}")) as JSONObject)
        require(release.getString("body").startsWith(marker)) { "同名 Release 已被其他发行占用" }
        record.put("githubReleaseId", release.getLong("id")).put("releaseUrl", release.getString("html_url")); save(record)
        val assets = release.getJSONArray("assets")
        val existing = (0 until assets.length()).map { assets.getJSONObject(it) }.singleOrNull { it.getString("name") == "plugin.zip" }
        val archive = File(record.getString("archivePath"))
        val asset = if (existing == null) client.upload(repository, release.getLong("id"), archive) else {
            require(graph.market.fetch(existing.getString("browser_download_url"), 32 * 1024 * 1024).contentEquals(archive.readBytes())) { "已有 plugin.zip 与准备记录不同，不会覆盖" }; existing
        }
        save(record.put("assetId", asset.getLong("id")).put("downloadUrl", asset.getString("browser_download_url")).put("status", "UPLOADED"))
    }
    suspend fun submit(id: String): JSONObject = withContext(Dispatchers.IO) {
        val record = graph.releasePreparations.status(id)
        require(record.has("assetId")) { "请先上传固定 Release" }
        val login = graph.github.profile().getString("login")
        require(graph.github.profile().getLong("id") == record.getLong("githubAccountId")) { "请使用上传发行的同一作者账号投稿" }
        val forkRepo = if (official.substringBefore('/') == login) official else "$login/${official.substringAfter('/')}"
        val fork = optional("/repos/$forkRepo") ?: (client.request("POST", "/repos/$official/forks", JSONObject()) as JSONObject)
        require(fork.getString("full_name") == official || fork.optJSONObject("parent")?.getString("full_name") == official) { "同名仓库不是官方市场的 fork" }
        val base = client.request("GET", "/repos/$official/git/ref/heads/main") as JSONObject
        val branch = releaseRef(record.getJSONObject("manifest").getString("releaseId"))
        if (optional("/repos/$forkRepo/git/ref/heads/$branch") == null) client.request("POST", "/repos/$forkRepo/git/refs", JSONObject().put("ref", "refs/heads/$branch").put("sha", base.getJSONObject("object").getString("sha")))
        val manifest = record.getJSONObject("manifest")
        val submission = JSONObject().put("schema", 1)
        for (key in listOf("catalogPluginId", "publisherId", "releaseId", "versionCode")) submission.put(key, manifest.get(key))
        for (key in listOf("repository", "repositoryId", "sourceCommit", "githubReleaseId", "assetId")) submission.put(key, record.get(key))
        val path = "submissions/" + releaseRef(manifest.getString("releaseId")) + ".json"
        val text = submission.toString(2) + "\n"
        val previous = optional("/repos/$forkRepo/contents/$path?ref=$branch")
        if (previous == null) client.request("PUT", "/repos/$forkRepo/contents/$path", JSONObject().put("message", "Submit fixed Easylsp release").put("branch", branch).put("content", Base64.getEncoder().encodeToString(text.toByteArray())))
        else require(Base64.getMimeDecoder().decode(previous.getString("content")).toString(Charsets.UTF_8) == text) { "投稿分支已有不同内容，请重新准备发行" }
        val head = "$login:$branch"
        val prs = client.request("GET", "/repos/$official/pulls?state=all&head=${URLEncoder.encode(head, "UTF-8")}") as JSONArray
        val pr = if (prs.length() > 0) prs.getJSONObject(0) else client.request("POST", "/repos/$official/pulls", JSONObject().put("title", "${manifest.getString("name")} ${manifest.getString("versionName")}").put("head", head).put("base", "main").put("body", "Fixed author release: ${record.getString("releaseUrl")}\nSource: ${manifest.getString("sourceUrl")}\n\nPublisher registration uses numeric account/repository IDs. Please review the retained candidate artifact.")) as JSONObject
        save(record.put("prNumber", pr.getInt("number")).put("prUrl", pr.getString("html_url")).put("status", "PENDING_REVIEW"))
    }
    suspend fun status(id: String): JSONObject = withContext(Dispatchers.IO) {
        val record = graph.releasePreparations.status(id)
        if (record.has("prNumber")) {
            val pr = client.request("GET", "/repos/$official/pulls/${record.getInt("prNumber")}") as JSONObject
            record.put("status", if (pr.optBoolean("merged")) "MERGED_AWAITING_INDEX" else if (pr.getString("state") == "closed") "CLOSED" else "PENDING_REVIEW")
        }
        save(record)
        val catalog = graph.market.refresh("official").getJSONArray("releases")
        val manifest = record.getJSONObject("manifest")
        for (i in 0 until catalog.length()) { val release = catalog.getJSONObject(i)
            if (listOf("catalogPluginId", "publisherId", "releaseId").all { release.getString(it) == manifest.getString(it) }) record.put("status", if (release.getString("state") == "WITHDRAWN") "WITHDRAWN" else "INDEXED")
        }
        save(record)
    }
}
