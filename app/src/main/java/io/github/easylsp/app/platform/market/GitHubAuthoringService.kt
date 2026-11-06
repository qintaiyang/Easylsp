package io.github.easylsp.app.platform.market

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.util.Base64

/** Author operations use the same stored account as release publication. */
class GitHubAuthoringService(private val client: GitHubClient, private val profile: () -> JSONObject) {
    fun createRepository(name: String, description: String, isPrivate: Boolean): JSONObject {
        require(name.matches(Regex("[A-Za-z0-9_.-]+"))) { "仓库名只支持字母、数字、下划线、点和连字符" }
        check(profile().has("id")) { "请先配置 GitHub 作者账号" }
        val repo = client.request("POST", "/user/repos", JSONObject().put("name", name)
            .put("description", description).put("private", isPrivate).put("auto_init", false)) as JSONObject
        return JSONObject().put("repository", repo.getString("full_name")).put("url", repo.getString("html_url"))
            .put("private", repo.getBoolean("private")).put("defaultBranch", repo.getString("default_branch"))
    }

    fun commitSource(workspace: File, repository: String, paths: List<String>, message: String, branch: String?): JSONObject {
        GitHubClient.repository(repository)
        require(paths.isNotEmpty() && message.isNotBlank()) { "必须指定要提交的文件和提交说明" }
        val root = workspace.canonicalFile
        // Read the explicit selection before making any remote changes. Never upload the whole workspace.
        val files = paths.distinct().map { path ->
            require(!File(path).isAbsolute && path.split('/').none { it == ".git" || it == ".." }) { "源码路径必须在当前工作区内，不能包含 .git 或 .." }
            val file = File(root, path).canonicalFile
            require(file.toPath().startsWith(root.toPath()) && file.isFile) { "源码文件不存在或超出当前工作区：$path" }
            file.relativeTo(root).invariantSeparatorsPath to file.readBytes()
        }
        val account = profile()
        check(account.has("id")) { "请先配置 GitHub 作者账号" }
        val repo = client.request("GET", "/repos/$repository") as JSONObject
        require(repo.getJSONObject("owner").getLong("id") == account.getLong("id") && repo.getJSONObject("permissions").getBoolean("push")) { "请选择当前作者拥有且有写权限的仓库" }
        val targetBranch = branch ?: repo.getString("default_branch")
        require(targetBranch.isNotBlank()) { "分支名不能为空" }
        val encodedBranch = URLEncoder.encode(targetBranch, "UTF-8").replace("+", "%20")
        fun readRef(name: String): JSONObject? = try {
            val encoded = URLEncoder.encode(name, "UTF-8").replace("+", "%20")
            client.request("GET", "/repos/$repository/git/ref/heads/$encoded") as JSONObject
        } catch (error: GitHubFailure) { if (error.status == 404 || error.status == 409) null else throw error }
        val ref = readRef(targetBranch)
        var branchExists = ref != null
        var parent = ref?.getJSONObject("object")?.getString("sha")
        if (parent == null && targetBranch != repo.getString("default_branch")) {
            parent = readRef(repo.getString("default_branch"))?.getJSONObject("object")?.getString("sha")
        }
        if (parent == null) {
            // GitHub's Git database API cannot create blobs in an empty repository.
            // Bootstrap it with one explicitly selected file through the Contents API.
            val (path, bytes) = files.first()
            val encodedPath = path.split('/').joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
            val initial = client.request("PUT", "/repos/$repository/contents/$encodedPath", JSONObject()
                .put("message", message).put("content", Base64.getEncoder().encodeToString(bytes))) as JSONObject
            parent = initial.getJSONObject("commit").getString("sha")
            if (targetBranch == repo.getString("default_branch")) branchExists = true
        }
        val tree = JSONObject().put("tree", JSONArray(files.map { (path, bytes) ->
            val blob = client.request("POST", "/repos/$repository/git/blobs", JSONObject()
                .put("encoding", "base64").put("content", Base64.getEncoder().encodeToString(bytes))) as JSONObject
            JSONObject().put("path", path).put("mode", "100644").put("type", "blob").put("sha", blob.getString("sha"))
        }))
        val previous = client.request("GET", "/repos/$repository/git/commits/$parent") as JSONObject
        tree.put("base_tree", previous.getJSONObject("tree").getString("sha"))
        val createdTree = client.request("POST", "/repos/$repository/git/trees", tree) as JSONObject
        val commit = client.request("POST", "/repos/$repository/git/commits", JSONObject().put("message", message)
            .put("tree", createdTree.getString("sha")).put("parents", JSONArray().put(parent))) as JSONObject
        val sha = commit.getString("sha")
        if (!branchExists) client.request("POST", "/repos/$repository/git/refs", JSONObject().put("ref", "refs/heads/$targetBranch").put("sha", sha))
        else client.request("PATCH", "/repos/$repository/git/refs/heads/$encodedBranch", JSONObject().put("sha", sha).put("force", false))
        return JSONObject().put("repository", repository).put("branch", targetBranch).put("sourceCommit", sha)
            .put("sourceUrl", "https://github.com/$repository/tree/$sha").put("commitUrl", "https://github.com/$repository/commit/$sha")
            .put("files", JSONArray(files.map { it.first }))
    }
}
