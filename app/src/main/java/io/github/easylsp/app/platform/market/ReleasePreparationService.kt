package io.github.easylsp.app.platform.market

import io.github.easylsp.app.EasylspApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Produces a fixed, reviewable local package. No GitHub request or credential is involved. */
class ReleasePreparationService(private val graph: EasylspApp) {
    suspend fun prepare(pluginId: String, buildId: String?, manifest: JSONObject, javaPluginId: String? = null): JSONObject = withContext(Dispatchers.IO) {
        val plugin = requireNotNull(graph.store.dao.plugin(pluginId)) { "插件不存在" }
        require(manifest.getString("runtime") == plugin.runtime || manifest.getString("runtime") == "JAVA_NATIVE" && plugin.runtime == "NATIVE") { "发行与插件运行时不一致" }
        val project = requireNotNull(graph.store.dao.project(plugin.projectId))
        require(manifest.getJSONObject("target").getString("packageName") == project.packageName) { "发行必须绑定真实插件目标" }
        val id = UUID.randomUUID().toString()
        val directory = File(graph.filesDir, "release-preparations/$id").apply { mkdirs() }
        val archive = File(directory, "plugin.zip")
        archive.outputStream().use { graph.store.exportRelease(plugin.id, buildId, manifest, it, javaPluginId) }
        val record = JSONObject().put("preparationId", id).put("status", "PREPARED_LOCAL")
            .put("javaPluginId", javaPluginId ?: JSONObject.NULL).put("pluginId", plugin.id).put("projectId", project.id).put("sourceRevision", buildId?.let { graph.store.nativePlugins.build(it)?.sourceRevision } ?: plugin.revision)
            .put("buildId", buildId ?: JSONObject.NULL).put("manifest", JSONObject(manifest.toString()))
            .put("archivePath", archive.path).put("archiveBytes", archive.length())
            .put("note", "固定本地发行，尚未上传、投稿或收录")
        File(directory, "preparation.json").writeText(record.toString(2))
        record
    }
    fun deleteProject(projectId: String) {
        File(graph.filesDir, "release-preparations").listFiles().orEmpty().forEach { directory ->
            val record = File(directory, "preparation.json")
            if (record.isFile && JSONObject(record.readText()).getString("projectId") == projectId) directory.deleteRecursively()
        }
    }
    fun status(id: String): JSONObject {
        UUID.fromString(id)
        return JSONObject(File(graph.filesDir, "release-preparations/$id/preparation.json").readText())
    }
}
