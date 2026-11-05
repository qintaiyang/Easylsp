package io.github.easylsp.app.data

import android.content.Context
import androidx.room.Room
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.*
import java.util.*
import java.util.zip.*

class ProjectStore(private val context: Context, databaseName: String = "easylsp.db", private val root: File = File(context.filesDir, "plugins")) {
    private val database = Room.databaseBuilder(context, EasylspDatabase::class.java, databaseName).addMigrations(EasylspDatabase.MIGRATION_1_2, EasylspDatabase.MIGRATION_2_3, EasylspDatabase.MIGRATION_3_4, EasylspDatabase.MIGRATION_4_5, EasylspDatabase.MIGRATION_5_6, EasylspDatabase.MIGRATION_6_7, EasylspDatabase.MIGRATION_7_8, EasylspDatabase.MIGRATION_8_9, EasylspDatabase.MIGRATION_9_10, EasylspDatabase.MIGRATION_10_11, EasylspDatabase.MIGRATION_11_12, EasylspDatabase.MIGRATION_12_13).build()
    val nativePlugins = database.nativePlugins()
    val diagnostics = database.diagnostics()
    val dao = database.projects()
    val workflows = database.workflows()
    fun transaction(block: () -> Unit) { database.runInTransaction(block) }
    fun projectsSnapshot() = dao.snapshotProjects()
    val projects = dao.observeProjects()
    val plugins = dao.observePlugins()
    init { root.mkdirs() }
    fun close() { database.close() }
    fun sourceFile(plugin: PluginEntity) = if (plugin.runtime == "NATIVE") File(attachmentDirectory(plugin), plugin.entry) else File(File(root, plugin.id), "${plugin.revision}.java")
    suspend fun select(packageName: String, name: String, version: String) = withContext(Dispatchers.IO) {
        dao.insertProject(ProjectEntity(UUID.randomUUID().toString(), packageName, name, version))
        dao.projectByPackage(packageName)!!
    }
    suspend fun create(project: ProjectEntity, name: String, source: String, author: String = "", description: String = "", version: String = "1.0", processName: String = "", runtime: String = "BEANSHELL") = withContext(Dispatchers.IO) {
        require(name.isNotBlank()) { "插件名不能为空" }
        require(processName.isEmpty() || processName == project.packageName || processName.startsWith("${project.packageName}:")) { "进程名必须属于项目包名" }
        require(runtime in setOf("BEANSHELL", "NATIVE")) { "未知插件类型" }
        val plugin = PluginEntity(UUID.randomUUID().toString(), project.id, name, version, author, description, processName = processName, runtime = runtime, entry = if (runtime == "NATIVE") "src/main.c" else "main.java")
        sourceFile(plugin).apply { parentFile!!.mkdirs(); writeText(source) }
        dao.insertPlugin(plugin)
        plugin
    }
    suspend fun save(id: String, name: String, author: String, description: String, version: String, source: String, processName: String? = null, files: Map<String, String>? = null) = withContext(Dispatchers.IO) {
        val previous = requireNotNull(dao.plugin(id)) { "插件不存在" }
        val packageName = requireNotNull(dao.project(previous.projectId)).packageName
        require(name.isNotBlank()) { "插件名不能为空" }
        require(processName == null || processName.isEmpty() || processName == packageName || processName.startsWith("$packageName:")) { "进程名必须属于项目包名" }
        val next = previous.copy(name = name, author = author, description = description, version = version, revision = previous.revision + 1, processName = processName ?: previous.processName)
        sourceFile(next).apply { parentFile!!.mkdirs(); writeText(source) }
        writeAttachments(next, files ?: readFiles(previous).filterKeys { it != previous.entry })
        libraries(previous).forEach { file -> file.copyTo(File(libraryDirectory(next).apply { mkdirs() }, file.name)) }
        dao.updatePlugin(next)
    }
    suspend fun enable(id: String, enabled: Boolean) = withContext(Dispatchers.IO) { val plugin = requireNotNull(dao.plugin(id)); require(plugin.runtime == "BEANSHELL") { "native 启用/停用必须通过发布服务，下次启动生效" }; dao.updatePlugin(plugin.copy(desiredEnabled = enabled)) }
    fun libraryDirectory(plugin: PluginEntity) = File(File(root, plugin.id), "lib/${plugin.revision}")
    fun libraries(plugin: PluginEntity): List<File> = if (plugin.runtime == "NATIVE") emptyList() else libraryDirectory(plugin).listFiles().orEmpty().filter { it.isFile }.sortedBy { it.name }
    suspend fun addLibrary(id: String, name: String, bytes: ByteArray) = withContext(Dispatchers.IO) {
        PluginLibraries.validate("lib/$name", bytes)
        val previous = requireNotNull(dao.plugin(id))
        require(previous.runtime == "BEANSHELL") { "native 首版不支持外部依赖；不能使用 Java lib/" }
        save(id, previous.name, previous.author, previous.description, previous.version, read(id))
        val current = requireNotNull(dao.plugin(id)); libraryDirectory(current).mkdirs(); File(libraryDirectory(current), name).writeBytes(bytes)
        current
    }
    suspend fun removeLibrary(id: String, name: String) = withContext(Dispatchers.IO) {
        require(!name.contains('/') && !name.contains('\\'))
        val previous = requireNotNull(dao.plugin(id))
        require(previous.runtime == "BEANSHELL") { "native 首版不支持外部依赖；不能使用 Java lib/" }
        save(id, previous.name, previous.author, previous.description, previous.version, read(id))
        File(libraryDirectory(requireNotNull(dao.plugin(id))), name).delete()
    }
    fun draftFile(id: String) = File(File(root, id), "draft.json")
    private fun attachmentDirectory(plugin: PluginEntity) = File(File(root, plugin.id), if (plugin.runtime == "NATIVE") "native/${plugin.revision}" else "files/${plugin.revision}")
    fun readFiles(plugin: PluginEntity): Map<String, String> = buildMap {
        put(plugin.entry, sourceFile(plugin).readText())
        val directory = attachmentDirectory(plugin)
        if (directory.exists()) directory.walkTopDown().filter { it != directory }.forEach {
            put(it.relativeTo(directory).invariantSeparatorsPath + if (it.isDirectory) "/" else "", if (it.isFile) it.readText() else "")
        }
    }
    private fun writeAttachments(plugin: PluginEntity, files: Map<String, String>) {
        val directory = attachmentDirectory(plugin)
        files.filterKeys { it != plugin.entry }.forEach { (path, text) ->
            PluginPaths.validate(path)
            if (plugin.runtime == "NATIVE") require(path.startsWith("src/") || path.startsWith("include/")) { "native 文本文件放在 src/ 或 include/" }
            val file = File(directory, path)
            if (path.endsWith('/')) file.mkdirs() else { file.parentFile!!.mkdirs(); file.writeText(text) }
        }
    }
    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        val plugin = requireNotNull(dao.plugin(id))
        require(plugin.runtime != "NATIVE" || !plugin.desiredEnabled) { "请先撤销 native 发布再删除" }
        dao.deletePlugin(id); File(root, id).deleteRecursively(); Unit
    }
    suspend fun read(id: String) = withContext(Dispatchers.IO) { sourceFile(requireNotNull(dao.plugin(id))).readText() }
    suspend fun importPlugin(project: ProjectEntity, input: InputStream) = withContext(Dispatchers.IO) {
        val entries = PluginArchive.read(input)
        val info = Properties().apply { load(StringReader(requireNotNull(entries["info.prop"]) { "缺少 info.prop" }.toString(Charsets.UTF_8))) }
        val runtime = if (info.getProperty("runtime", "beanshell").equals("native", true)) "NATIVE" else "BEANSHELL"
        val entry = if (runtime == "NATIVE") "src/main.c" else "main.java"
        if (runtime == "BEANSHELL") require(info.getProperty("apiVersion") == "1" && info.getProperty("entry") == entry) { "不支持的插件 API 或入口" }
        val scope = info.getProperty("processScope", "exact"); require(scope in setOf("exact", "main", "children", "all"))
        val plugin = create(project, requireNotNull(info.getProperty("name")) { "缺少插件名" }, requireNotNull(entries[entry]) { "缺少 $entry" }.toString(Charsets.UTF_8), info.getProperty("author", ""), info.getProperty("description", ""), info.getProperty("version", "1.0"), info.getProperty("processName", ""), runtime)
        dao.updatePlugin(plugin.copy(processScope = scope))
        writeAttachments(plugin, entries.filterKeys { it != "info.prop" && it != entry && !it.startsWith("lib/") && !it.startsWith("native/") }.mapValues { it.value.toString(Charsets.UTF_8) })
        entries.filterKeys { it.startsWith("lib/") && !it.endsWith('/') }.forEach { (path, bytes) -> libraryDirectory(plugin).mkdirs(); File(libraryDirectory(plugin), path.removePrefix("lib/")).writeBytes(bytes) }
        entries["native/arm64-v8a/plugin.so"]?.let { bytes ->
            val id = UUID.randomUUID().toString()
            val artifact = File(File(context.filesDir, "workspaces/${project.id}/native-builds/$id"), "plugin.so")
            artifact.parentFile!!.mkdirs(); artifact.writeBytes(bytes)
            nativePlugins.saveBuild(NativeBuildEntity(id, plugin.id, project.id, 1, project.sampleRevision, "1", "IMPORTED (ELF API 34)", state = "SUCCEEDED", artifactPath = artifact.path))
        }
        plugin.copy(processScope = scope)
    }
    suspend fun exportPlugin(id: String, output: OutputStream) = withContext(Dispatchers.IO) {
        val plugin = requireNotNull(dao.plugin(id))
        val info = Properties().apply {
            setProperty("id", plugin.id); setProperty("name", plugin.name); setProperty("version", plugin.version)
            setProperty("author", plugin.author); setProperty("description", plugin.description); setProperty("apiVersion", "1"); setProperty("entry", plugin.entry); setProperty("processName", plugin.processName); setProperty("processScope", plugin.processScope)
        }
        if (plugin.runtime == "NATIVE") info.apply {
            setProperty("formatVersion", "2"); setProperty("runtime", "native"); setProperty("nativeApiVersion", "1")
            setProperty("abi", "arm64-v8a"); setProperty("minApi", "34")
        }
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("info.prop")); val writer = StringWriter(); info.store(writer, "Easylsp plugin"); zip.write(writer.toString().toByteArray()); zip.closeEntry()
            libraries(plugin).forEach { file -> zip.putNextEntry(ZipEntry("lib/${file.name}")); file.inputStream().use { it.copyTo(zip) }; zip.closeEntry() }
            readFiles(plugin).forEach { (path, text) -> zip.putNextEntry(ZipEntry(path)); if (!path.endsWith('/')) zip.write(text.toByteArray()); zip.closeEntry() }
        }
    }
}
