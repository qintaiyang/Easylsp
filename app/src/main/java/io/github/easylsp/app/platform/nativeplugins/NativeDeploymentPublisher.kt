package io.github.easylsp.app.platform.nativeplugins

import io.github.easylsp.app.EasylspApp
import io.github.easylsp.app.data.NativeDeploymentEntity
import io.github.easylsp.app.data.PluginEntity
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Single publishing boundary shared by tools and UI. The next process reads a complete snapshot. */
class NativeDeploymentPublisher(private val graph: EasylspApp) {
    @Synchronized fun enable(plugin: PluginEntity, buildId: String, policy: String): JSONObject {
        require(plugin.runtime == "NATIVE")
        require(policy in setOf("NATIVE_ONLY", "NATIVE_AND_JAVA")) { "发布时必须明确选择 NATIVE_ONLY 或 NATIVE_AND_JAVA" }
        val build = requireNotNull(graph.store.nativePlugins.build(buildId)) { "构建不存在" }
        require(build.pluginId == plugin.id && build.projectId == plugin.projectId && build.state == "SUCCEEDED") { "构建必须属于此插件并已成功接收" }
        val snapshot = snapshot(plugin.projectId, policy)
        val items = snapshot.getJSONArray("plugins")
        val retained = JSONArray((0 until items.length()).map { items.getJSONObject(it) }.filter { it.getString("pluginId") != plugin.id })
        val remote = "native-${UUID.randomUUID()}.so"
        retained.put(JSONObject().put("pluginId", plugin.id).put("buildId", buildId).put("sourceRevision", build.sourceRevision)
            .put("remoteFile", remote).put("processScope", plugin.processScope).put("processName", plugin.processName.ifEmpty { snapshot.getString("packageName") }).put("config", "{}"))
        require(retained.length() <= 16) { "首版每进程最多 16 个 native 插件" }
        snapshot.put("plugins", retained)
        return commit(plugin.projectId, snapshot, remote to File(build.artifactPath))
    }
    @Synchronized fun disable(plugin: PluginEntity): JSONObject {
        require(plugin.runtime == "NATIVE")
        val published = graph.store.nativePlugins.published(plugin.projectId)
        val snapshot = snapshot(plugin.projectId, published?.policy ?: "JAVA_ONLY", allowChangedTarget = true)
        val items = snapshot.getJSONArray("plugins")
        val retained = JSONArray((0 until items.length()).map { items.getJSONObject(it) }.filter { it.getString("pluginId") != plugin.id })
        snapshot.put("plugins", retained)
        if (retained.length() == 0) {
            val info = graph.packageManager.getPackageInfo(snapshot.getString("packageName"), 0)
            snapshot.put("policy", "JAVA_ONLY").put("targetApkPath", requireNotNull(info.applicationInfo).sourceDir).put("targetVersionCode", info.longVersionCode)
        }
        return commit(plugin.projectId, snapshot, null)
    }
    private fun snapshot(projectId: String, policy: String, allowChangedTarget: Boolean = false): JSONObject {
        val project = requireNotNull(graph.store.dao.project(projectId))
        require(project.kind == "INSTALLED") { "导入 APK 项目可构建，发布需要已安装目标" }
        val info = graph.packageManager.getPackageInfo(project.packageName, 0)
        val app = requireNotNull(info.applicationInfo)
        val current = graph.framework.nativeSnapshot("${app.uid}:${project.packageName}")?.let(::JSONObject)
        if (!allowChangedTarget && current != null && current.getJSONArray("plugins").length() > 0)
            require(current.getString("targetApkPath") == app.sourceDir) { "目标已升级，请先停用旧发布再按新样本重新发布" }
        return JSONObject().put("schema", 1).put("generation", UUID.randomUUID().toString()).put("uid", app.uid)
            .put("packageName", project.packageName).put("targetApkPath", if (allowChangedTarget && current != null) current.getString("targetApkPath") else app.sourceDir)
            .put("targetVersionCode", if (allowChangedTarget && current != null) current.optLong("targetVersionCode", info.longVersionCode) else info.longVersionCode)
            .put("policy", policy).put("plugins", current?.getJSONArray("plugins") ?: JSONArray())
    }
    private fun commit(projectId: String, snapshot: JSONObject, artifact: Pair<String, File>?): JSONObject {
        val row = NativeDeploymentEntity(snapshot.getString("generation"), projectId, snapshot.getInt("uid"), snapshot.getString("packageName"),
            snapshot.getLong("targetVersionCode"), snapshot.getString("policy"), snapshot.toString(), "PUBLISHED")
        var step = "WRITE_ARTIFACT"
        try {
            artifact?.let { graph.framework.writeNativeArtifact(it.first, it.second) }
            step = "COMMIT_SNAPSHOT"
            graph.framework.publishNativeSnapshot("${row.uid}:${row.packageName}", snapshot)
        } catch (error: Exception) {
            graph.store.nativePlugins.saveDeployment(row.copy(state = "FAILED", failedStep = step, error = error.toString()))
            throw IllegalStateException("native 发布失败：$step；${error.message}", error)
        }
        graph.store.transaction {
            graph.store.nativePlugins.saveDeployment(row)
            val items = snapshot.getJSONArray("plugins")
            val enabled = (0 until items.length()).map { items.getJSONObject(it).getString("pluginId") }.toSet()
            graph.store.dao.plugins(projectId).filter { it.runtime == "NATIVE" }.forEach { graph.store.dao.updatePlugin(it.copy(desiredEnabled = it.id in enabled)) }
        }
        return snapshot.put("status", "PUBLISHED_PENDING_RESTART").put("note", "下次目标新进程生效；当前进程的 SO 和 Hook 保留到退出")
    }
    fun publishPrototype(projectId: String, buildId: String, policy: String = "NATIVE_ONLY"): JSONObject {
        val project = requireNotNull(graph.store.dao.project(projectId))
        require(project.packageName == "io.github.easylsp.demo") { "P0 只发布受控 Demo；其他目标等待早期链路验收" }
        require(policy in setOf("NATIVE_ONLY", "NATIVE_AND_JAVA"))
        val directory = graph.nativeBuilds.directory(projectId, buildId)
        val record = JSONObject(File(directory, "build.json").readText())
        require(record.getString("state") == "SUCCEEDED") { "只能发布已通过产物接收的固定 buildId" }
        val info = graph.packageManager.getPackageInfo(project.packageName, 0)
        val app = requireNotNull(info.applicationInfo)
        val remote = "native-${UUID.randomUUID()}.so"
        val snapshot = JSONObject().put("schema", 1).put("generation", UUID.randomUUID().toString())
            .put("uid", app.uid).put("packageName", project.packageName).put("targetApkPath", app.sourceDir)
            .put("targetVersionCode", info.longVersionCode).put("policy", policy)
            .put("plugins", JSONArray().put(JSONObject().put("pluginId", record.getString("pluginId"))
                .put("buildId", buildId).put("sourceRevision", record.getLong("sourceRevision"))
                .put("remoteFile", remote).put("processScope", "main").put("processName", project.packageName).put("config", "{}")))
        var step = "WRITE_ARTIFACT"
        try {
            graph.framework.writeNativeArtifact(remote, File(directory, "plugin.so"))
            step = "COMMIT_SNAPSHOT"
            graph.framework.publishNativeSnapshot("${app.uid}:${project.packageName}", snapshot)
            File(directory, "publication-${snapshot.getString("generation")}.json").writeText(snapshot.toString(2))
        } catch (error: Exception) {
            throw IllegalStateException("native 发布失败：$step；${error.message}", error)
        }
        return snapshot.put("status", "PUBLISHED_PENDING_RESTART")
    }
    fun disablePrototype(projectId: String): JSONObject {
        val project = requireNotNull(graph.store.dao.project(projectId))
        require(project.packageName == "io.github.easylsp.demo")
        val app = graph.packageManager.getApplicationInfo(project.packageName, 0)
        val snapshot = JSONObject().put("schema", 1).put("generation", UUID.randomUUID().toString())
            .put("uid", app.uid).put("packageName", project.packageName).put("targetApkPath", app.sourceDir)
            .put("policy", "JAVA_ONLY").put("plugins", JSONArray())
        graph.framework.publishNativeSnapshot("${app.uid}:${project.packageName}", snapshot)
        return snapshot.put("status", "DISABLED_PENDING_RESTART")
    }
}
