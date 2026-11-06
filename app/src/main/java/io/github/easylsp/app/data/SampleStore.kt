package io.github.easylsp.app.data

import android.content.Context
import android.net.Uri
import io.github.easylsp.app.EasylspApp
import io.github.easylsp.app.platform.root.RootCommand
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.util.UUID

class SampleStore(private val context: Context, private val store: ProjectStore) {
    private val root = File(context.filesDir, "samples").apply { mkdirs() }
    suspend fun capture(project: ProjectEntity): ProjectEntity = withContext(Dispatchers.IO) {
        require(project.kind == "INSTALLED") { "导入项目请使用重新导入" }
        val info = (context.applicationContext as EasylspApp).targetApplications.info(project.packageName, project.userId)
        val app = requireNotNull(info.applicationInfo)
        save(project, listOf(File(app.sourceDir)) + (app.splitSourceDirs?.map(::File) ?: emptyList()), info.versionName ?: "", useRoot = (context.applicationContext as EasylspApp).root.useRoot())
    }
    suspend fun importApk(uri: Uri): ProjectEntity = withContext(Dispatchers.IO) {
        val staging = File(context.cacheDir, "import-${UUID.randomUUID()}.apk")
        try {
            requireNotNull(context.contentResolver.openInputStream(uri)).use { input -> staging.outputStream().use { input.copyTo(it) } }
            val info = requireNotNull(context.packageManager.getPackageArchiveInfo(staging.path, 0)) { "无法读取普通 APK；暂不支持 APKM/XAPK/APKS" }
            val app = requireNotNull(info.applicationInfo).apply { sourceDir = staging.path; publicSourceDir = staging.path }
            val existing = store.projectsSnapshot().firstOrNull { it.packageName == info.packageName && it.kind == "IMPORTED" }
            val project = existing ?: ProjectEntity(UUID.randomUUID().toString(), info.packageName, app.loadLabel(context.packageManager).toString(), info.versionName ?: "", "IMPORTED")
            store.dao.insertProject(project)
            save(project, listOf(staging), info.versionName ?: "")
        } finally { staging.delete() }
    }
    private suspend fun save(project: ProjectEntity, inputs: List<File>, version: String, useRoot: Boolean = false): ProjectEntity {
        val current = requireNotNull(store.dao.project(project.id))
        val revision = current.sampleRevision + 1
        val dir = File(File(root, project.id), revision.toString()).apply { mkdirs() }
        try {
        val files = inputs.mapIndexed { i, file ->
            val destination = File(dir, if (i == 0) "base.apk" else "split-$i.apk")
            if (useRoot) {
                // App opens the destination first; root only reads source bytes. Ownership stays with app UID.
                destination.outputStream().close()
                val command = "cat " + RootCommand.quote(file.absolutePath) + " > " + RootCommand.quote(destination.absolutePath)
                val output = StringBuilder()
                val graph = context.applicationContext as EasylspApp
                val result = graph.executions.runner.execute(UUID.randomUUID().toString(), listOf(graph.root.su, "-c", command), dir, closeInput = true, timeoutSeconds = 120) { output.append(it) }
                require(result.exitCode == 0) { "root 采集失败：$output" }
            } else file.copyTo(destination)
            destination
        }
        val next = current.copy(version = version, sampleRevision = revision, archived = if (current.kind == "IMPORTED") false else current.archived)
        store.transaction {
            store.dao.insertSample(SampleEntity(project.id, revision, JSONArray(files.map { it.path }).toString(), version, System.currentTimeMillis()))
            store.dao.updateProject(next)
        }
        return next
        } catch (error: Exception) { dir.deleteRecursively(); throw error }
    }
}
