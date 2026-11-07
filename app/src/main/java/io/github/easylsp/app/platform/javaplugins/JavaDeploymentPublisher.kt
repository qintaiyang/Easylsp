package io.github.easylsp.app.platform.javaplugins

import io.github.easylsp.app.EasylspApp
import io.github.easylsp.app.data.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.*
import java.io.File
import java.util.UUID

/** Publish all immutable payloads before atomically selecting a complete enabled set. */
class JavaDeploymentPublisher(private val graph: EasylspApp) {
    private val directory = File(graph.filesDir, "java-publications")
    private val published = MutableStateFlow(directory.listFiles().orEmpty().filter { it.extension == "json" }
        .associate { it.nameWithoutExtension to JSONObject(it.readText()) })
    val snapshots = published.asStateFlow()

    /** Upgrades retain enabled plugins; publish their missing startup sets once the framework connects. */
    @Synchronized fun migrateEnabled(): List<String> = buildList {
        for (project in graph.store.dao.snapshotProjects()) {
            if (project.kind != "INSTALLED" || project.userId != android.os.Process.myUid() / 100000 || File(directory, project.id + ".json").exists()) continue
            val enabled = graph.store.dao.plugins(project.id).filter { it.desiredEnabled }
            if (enabled.none { it.runtime == "BEANSHELL" }) continue
            try { publish(project.id, enabled) }
            catch (error: Exception) { add(project.name + "：" + (error.message ?: error.toString())) }
        }
    }

    @Synchronized fun publish(projectId: String, enabled: List<PluginEntity> = graph.store.dao.plugins(projectId).filter { it.desiredEnabled }, refresh: Boolean = false): JSONObject {
        val project = requireNotNull(graph.store.dao.project(projectId))
        require(project.kind == "INSTALLED") { "Java 发布需要已安装目标" }
        val installed = graph.store.dao.plugins(projectId)
        val ordered = enabled.flatMap { ReleaseDependencies.installedOrder(it, installed) }.filter { it.runtime == "BEANSHELL" }.distinctBy { it.id }
        val generation = UUID.randomUUID().toString()
        val saved = File(directory, projectId + ".json")
        val previousSnapshot = if (saved.exists()) JSONObject(saved.readText()) else null
        val previous = previousSnapshot?.getJSONArray("plugins") ?: JSONArray()
        val app = if (ordered.isEmpty() && previousSnapshot != null) null else requireNotNull(graph.targetApplications.info(project.packageName, project.userId).applicationInfo)
        val uid = app?.uid ?: previousSnapshot!!.getInt("uid")
        val apkPath = app?.sourceDir ?: previousSnapshot!!.getString("targetApkPath")
        val items = JSONArray()
        for (plugin in ordered) {
            val pinned = (0 until previous.length()).map { previous.getJSONObject(it) }.firstOrNull { it.getString("pluginId") == plugin.id }
            if (pinned != null && !refresh) {
                items.put(JSONObject(pinned.toString()).put("processScope", plugin.processScope).put("processName", plugin.processName.ifEmpty { project.packageName }))
                continue
            }
            graph.store.sourceFile(plugin).reader().use { io.github.easylsp.runtime.PluginEngine.readAndParse(it) }
            val script = "java-" + UUID.randomUUID() + ".java"
            graph.framework.writeNativeArtifact(script, graph.store.sourceFile(plugin))
            val libraries = JSONArray()
            graph.store.libraries(plugin).forEach { file ->
                val remote = "java-" + UUID.randomUUID() + "." + file.extension
                graph.framework.writeNativeArtifact(remote, file)
                libraries.put(JSONObject().put("name", file.name).put("remoteFile", remote))
            }
            items.put(JSONObject().put("pluginId", plugin.id).put("revision", plugin.revision).put("remoteFile", script)
                .put("processScope", plugin.processScope).put("processName", plugin.processName.ifEmpty { project.packageName }).put("libraries", libraries))
        }
        val snapshot = JSONObject().put("schema", 1).put("generation", generation).put("uid", uid).put("packageName", project.packageName)
            .put("targetApkPath", apkPath).put("plugins", items).put("publishedAt", System.currentTimeMillis())
        graph.framework.publishJavaSnapshot(uid.toString() + ":" + project.packageName, snapshot)
        saved.parentFile!!.mkdirs()
        val pending = java.io.File(saved.parentFile, projectId + ".pending")
        pending.writeText(snapshot.toString()); check(pending.renameTo(saved)) { "框架已发布，但本地发布记录保存失败" }
        published.update { it + (projectId to snapshot) }
        return snapshot
    }
}
