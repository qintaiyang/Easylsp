package io.github.easylsp.app.data

import android.content.Context
import androidx.room.Room
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.*
import java.util.*
import java.util.zip.*

class ProjectStore(private val context: Context, databaseName: String = "easylsp.db", private val root: File = File(context.filesDir, "plugins"), private val publishJava: (String, List<PluginEntity>) -> Unit = { _, _ -> }) {
    private val sourceWrites = Mutex()
    private val database = Room.databaseBuilder(context, EasylspDatabase::class.java, databaseName).addMigrations(EasylspDatabase.MIGRATION_1_2, EasylspDatabase.MIGRATION_2_3, EasylspDatabase.MIGRATION_3_4, EasylspDatabase.MIGRATION_4_5, EasylspDatabase.MIGRATION_5_6, EasylspDatabase.MIGRATION_6_7, EasylspDatabase.MIGRATION_7_8, EasylspDatabase.MIGRATION_8_9, EasylspDatabase.MIGRATION_9_10, EasylspDatabase.MIGRATION_10_11, EasylspDatabase.MIGRATION_11_12, EasylspDatabase.MIGRATION_12_13, EasylspDatabase.MIGRATION_13_14, EasylspDatabase.MIGRATION_14_15, EasylspDatabase.MIGRATION_15_16, EasylspDatabase.MIGRATION_16_17).build()
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
    suspend fun select(packageName: String, name: String, version: String, userId: Int = android.os.Process.myUid() / 100000) = withContext(Dispatchers.IO) {
        dao.insertProject(ProjectEntity(UUID.randomUUID().toString(), packageName, name, version, userId = userId, nativeBackend = if (userId == android.os.Process.myUid() / 100000) "LIBXPOSED" else "ZYGISK", nativePolicy = if (userId == android.os.Process.myUid() / 100000) "NATIVE_AND_JAVA" else "NATIVE_ONLY"))
        requireNotNull(dao.projectByPackage(packageName, userId)).let { row ->
            row.copy(name = name, version = version, archived = false).also(dao::updateProject)
        }
    }
    suspend fun create(project: ProjectEntity, name: String, source: String, author: String = "", description: String = "", version: String = "1.0", processName: String = "", runtime: String = "BEANSHELL", nativeEntry: String = "src/main.c") = withContext(Dispatchers.IO) {
        require(name.isNotBlank()) { "插件名不能为空" }
        require(processName.isEmpty() || processName == project.packageName || processName.startsWith("${project.packageName}:")) { "进程名必须属于项目包名" }
        require(runtime in setOf("BEANSHELL", "NATIVE")) { "未知插件类型" }
        require(nativeEntry in setOf("src/main.c", "src/main.cpp")) { "Native 入口须为 src/main.c 或 src/main.cpp" }
        val plugin = PluginEntity(UUID.randomUUID().toString(), project.id, name, version, author, description, processName = processName, runtime = runtime, entry = if (runtime == "NATIVE") nativeEntry else "main.java")
        sourceFile(plugin).apply { parentFile!!.mkdirs(); writeText(source) }
        dao.insertPlugin(plugin)
        plugin
    }
    suspend fun save(id: String, name: String, author: String, description: String, version: String, source: String, processName: String? = null, files: Map<String, String>? = null, baseRevision: Long? = null) = withContext(Dispatchers.IO) { sourceWrites.withLock {
        val previous = requireNotNull(dao.plugin(id)) { "插件不存在" }
        require(baseRevision == null || previous.revision == baseRevision) { "插件版本已变化，请重新读取后保存" }
        val packageName = requireNotNull(dao.project(previous.projectId)).packageName
        require(name.isNotBlank()) { "插件名不能为空" }
        require(processName == null || previous.nativeProcessType == "app" && (processName.isEmpty() || processName == packageName) || processName.startsWith("$packageName:") && processName.length > packageName.length + 1) { "进程名必须属于项目包名" }
        val attachments = files ?: readFiles(previous).filterKeys { it != previous.entry }
        attachments.keys.forEach { PluginPaths.validate(it) }
        require(attachments.keys.none { key -> attachments.keys.any { other -> other != key && !other.endsWith('/') && key.startsWith("$other/") } }) { "文件和目录路径冲突" }
        val next = previous.copy(localModified = previous.source != "LOCAL" || previous.localModified, name = name, author = author, description = description, version = version, revision = previous.revision + 1, processName = processName ?: previous.processName)
        sourceFile(next).apply { parentFile!!.mkdirs(); writeText(source) }
        writeAttachments(next, attachments)
        libraries(previous).forEach { file -> file.copyTo(File(libraryDirectory(next).apply { mkdirs() }, file.name)) }
        dao.updatePlugin(next)
    } }
    suspend fun enable(id: String, enabled: Boolean) = withContext(Dispatchers.IO) {
        val plugin = requireNotNull(dao.plugin(id))
        require(plugin.runtime == "BEANSHELL") { "Native启停必须通过发布服务" }
        val installed = dao.plugins(plugin.projectId)
        require(!enabled || requireNotNull(dao.project(plugin.projectId)).userId == android.os.Process.myUid() / 100000) { "Java组件须在目标用户的管理器中启用" }
        if (enabled) require(ReleaseDependencies.installedOrder(plugin, installed).all { it.id == id || it.desiredEnabled }) { "请启用固定发行与依赖" }
        else ReleaseDependencies.requireCanDisable(plugin, installed)
        val next = plugin.copy(desiredEnabled = enabled)
        publishJava(plugin.projectId, installed.map { if (it.id == id) next else it }.filter { it.desiredEnabled })
        dao.updatePlugin(next)
    }
    suspend fun deleteRelease(id: String) = withContext(Dispatchers.IO) {
        val plugin = requireNotNull(dao.plugin(id))
        val installed = dao.plugins(plugin.projectId)
        val rows = ReleaseDependencies.components(plugin, installed)
        require(rows.none { it.desiredEnabled }) { "请先整组停用发行" }
        ReleaseDependencies.requireCanDisableSet(rows, installed)
        transaction { rows.forEach { dao.deletePlugin(it.id) } }
        rows.forEach { File(root, it.id).deleteRecursively() }
    }
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
    fun program(plugin: PluginEntity) = io.github.easylsp.runtime.PluginProgram(readFiles(plugin).filterKeys { !it.endsWith('/') })
    fun programFile(plugin: PluginEntity): File {
        val program = program(plugin)
        program.validate()
        val directory = File(context.cacheDir, "java-programs").apply { mkdirs() }
        return File.createTempFile(plugin.id + "-r" + plugin.revision + "-", ".zip", directory).apply {
            outputStream().use { program.write(it) }
        }
    }
    fun javaOrder(plugins: List<PluginEntity>): List<PluginEntity> {
        val java = plugins.filter { it.runtime == "BEANSHELL" }
        val programs = java.associate { it.id to program(it) }
        for (consumer in java) for (service in programs.getValue(consumer.id).requires.keys) {
            val provider = java.singleOrNull { service in programs.getValue(it.id).provides } ?: error("服务提供者缺失或重复：$service")
            val packageName = requireNotNull(dao.project(consumer.projectId)).packageName
            require(provider.processScope == consumer.processScope && (provider.processScope == "all" || provider.processName.ifEmpty { packageName } == consumer.processName.ifEmpty { packageName })) { "服务提供者与消费者须使用相同进程范围：$service" }
        }
        val installed = plugins.firstOrNull()?.let { dao.plugins(it.projectId) }.orEmpty()
        val dependencies = java.associate { plugin -> plugin.id to ReleaseDependencies.installedOrder(plugin, installed).filter { it.runtime == "BEANSHELL" && it.id != plugin.id }.map { it.id } }
        return io.github.easylsp.runtime.PluginProgram.order(programs, dependencies).map { id -> java.single { it.id == id } }
    }
    suspend fun writeFiles(id: String, files: Map<String, String>, removePaths: List<String>, baseRevision: Long) = withContext(Dispatchers.IO) {
        val previous = requireNotNull(dao.plugin(id))
        require(previous.revision == baseRevision) { "插件版本已变化，请重新读取后保存" }
        files.keys.forEach { PluginPaths.validate(it) }
        removePaths.forEach { PluginPaths.validate(it); require(it != previous.entry) { "不能删除入口" } }
        val next = readFiles(previous).toMutableMap()
        removePaths.forEach { path -> next.keys.removeAll { it == path || path.endsWith('/') && it.startsWith(path) } }
        next.putAll(files)
        save(id, previous.name, previous.author, previous.description, previous.version, next.getValue(previous.entry), files = next, baseRevision = baseRevision)
        previous.revision + 1
    }
    private fun writeAttachments(plugin: PluginEntity, files: Map<String, String>) {
        val directory = attachmentDirectory(plugin)
        files.filterKeys { it != plugin.entry }.forEach { (path, text) ->
            PluginPaths.validate(path)
            if (plugin.runtime == "NATIVE") require(path.startsWith("src/") || path.startsWith("include/") || path in setOf("README.md", "LICENSE", "config.schema.json")) { "native 文本文件放在 src/ 或 include/" }
            val file = File(directory, path)
            if (path.endsWith('/')) file.mkdirs() else { file.parentFile!!.mkdirs(); file.writeText(text) }
        }
    }
    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        val plugin = requireNotNull(dao.plugin(id))
        require(plugin.runtime != "NATIVE" || !plugin.desiredEnabled) { "请先撤销 native 发布再删除" }
        require(ReleaseDependencies.components(plugin, dao.plugins(plugin.projectId)).size == 1) { "联合发行请整组停用并删除" }
        ReleaseDependencies.requireCanDisable(plugin, dao.plugins(plugin.projectId))
        if (plugin.runtime == "BEANSHELL" && plugin.desiredEnabled) publishJava(plugin.projectId, dao.plugins(plugin.projectId).filter { it.desiredEnabled && it.id != id })
        dao.deletePlugin(id); File(root, id).deleteRecursively(); Unit
    }
    suspend fun read(id: String) = withContext(Dispatchers.IO) { sourceFile(requireNotNull(dao.plugin(id))).readText() }
    suspend fun importPlugin(project: ProjectEntity, input: InputStream, marketSignature: org.json.JSONObject? = null, marketSourceId: String = "official") = withContext(Dispatchers.IO) {
        val entries = PluginArchive.read(input)
        if ("manifest.json" in entries) return@withContext installRelease(project, entries, marketSignature = marketSignature, marketSourceId = marketSourceId)
        val info = Properties().apply { load(StringReader(requireNotNull(entries["info.prop"]) { "缺少 info.prop" }.toString(Charsets.UTF_8))) }
        val runtime = if (info.getProperty("runtime", "beanshell").equals("native", true)) "NATIVE" else "BEANSHELL"
        val entry = if (runtime == "NATIVE") info.getProperty("entry") else "main.java"
        if (runtime == "BEANSHELL") require(info.getProperty("apiVersion") in setOf("1", "2") && info.getProperty("entry") == entry) { "不支持的插件 API 或入口" }
        if ("native/arm64-v8a/plugin.so" in entries) require(info.getProperty("minApi").toInt() <= android.os.Build.VERSION.SDK_INT) { "此插件要求 Android API ${info.getProperty("minApi")}，当前为 ${android.os.Build.VERSION.SDK_INT}" }
        val scope = info.getProperty("processScope", "exact"); require(scope in setOf("exact", "main", "children", "all"))
        val plugin = create(project, requireNotNull(info.getProperty("name")) { "缺少插件名" }, requireNotNull(entries[entry]) { "缺少 $entry" }.toString(Charsets.UTF_8), info.getProperty("author", ""), info.getProperty("description", ""), info.getProperty("version", "1.0"), info.getProperty("processName", ""), runtime, nativeEntry = if (runtime == "NATIVE") entry else "src/main.c")
        dao.updatePlugin(plugin.copy(processScope = scope))
        writeAttachments(plugin, entries.filterKeys { it != "info.prop" && it != entry && !it.startsWith("lib/") && !it.startsWith("native/") }.mapValues { it.value.toString(Charsets.UTF_8) })
        entries.filterKeys { it.startsWith("lib/") && !it.endsWith('/') }.forEach { (path, bytes) -> libraryDirectory(plugin).mkdirs(); File(libraryDirectory(plugin), path.removePrefix("lib/")).writeBytes(bytes) }
        entries["native/arm64-v8a/plugin.so"]?.let { bytes ->
            val id = UUID.randomUUID().toString()
            val artifact = File(File(context.filesDir, "workspaces/${project.id}/native-builds/$id"), "plugin.so")
            artifact.parentFile!!.mkdirs(); artifact.writeBytes(bytes)
            nativePlugins.saveBuild(NativeBuildEntity(id, plugin.id, project.id, 1, project.sampleRevision, "1", "IMPORTED (ELF API ${info.getProperty("minApi")})", minApi = info.getProperty("minApi").toInt(), state = "SUCCEEDED", artifactPath = artifact.path))
        }
        plugin.copy(processScope = scope)
    }
    private fun installRelease(project: ProjectEntity, entries: Map<String, ByteArray>, rollback: Boolean = false, restore: Boolean = false, configuration: String? = null, marketSignature: org.json.JSONObject? = null, marketSourceId: String = "official"): PluginEntity {
        val release = PluginRelease(org.json.JSONObject(entries.getValue("manifest.json").toString(Charsets.UTF_8)))
        val components = release.runtimes
        val previous = dao.plugins(project.id).filter { it.catalogPluginId == release.catalogPluginId && it.publisherId == release.publisherId && it.marketSourceId == marketSourceId }
        require(previous.all { it.publisherId == release.publisherId })
        previous.filter { it.releaseId != release.releaseId }.forEach { ReleaseDependencies.requireCanDisableSet(previous, dao.plugins(project.id)) }
        require(previous.all { it.runtime in components }) { "同一发行身份不能移除已有运行时组件" }
        require(restore || previous.none { it.releaseId != release.releaseId } || components.size == 1 && previous.size == 1 || previous.none { it.desiredEnabled }) { "联合发行更新或回退前请先整组停用，再显式启用新版本" }
        require(restore || previous.none { it.localModified }) { "发行含本地修改，请先保留本地副本" }
        var result: PluginEntity? = null
        transaction {
            for (runtime in components) {
                val row = installComponent(project, entries, rollback, restore, configuration, marketSignature, marketSourceId, runtime)
                if (result == null) result = row
            }
        }
        return requireNotNull(result)
    }
    fun releaseCache(catalogPluginId: String, releaseId: String, sourceId: String = "official") = File(context.filesDir, "${if (sourceId == "official") "release-cache" else "release-cache-sources/$sourceId"}/$catalogPluginId/$releaseId")
    private fun trustFile(catalogPluginId: String, releaseId: String, sourceId: String) = File(context.filesDir, "${if (sourceId == "official") "release-trust" else "release-trust-sources/$sourceId"}/$catalogPluginId/$releaseId.json")
    data class ReceivedRelease(val entries: Map<String, ByteArray>, val signature: org.json.JSONObject, val sourceId: String)
    fun installReleases(project: ProjectEntity, releases: List<ReceivedRelease>): PluginEntity {
        var result: PluginEntity? = null
        transaction { for (release in releases) result = installRelease(project, release.entries, marketSignature = release.signature, marketSourceId = release.sourceId) }
        return requireNotNull(result)
    }
    private fun installComponent(project: ProjectEntity, entries: Map<String, ByteArray>, rollback: Boolean, restore: Boolean, configuration: String?, marketSignature: org.json.JSONObject?, marketSourceId: String, componentRuntime: String): PluginEntity {
        val release = PluginRelease(org.json.JSONObject(entries.getValue("manifest.json").toString(Charsets.UTF_8)))
        val info = if (project.kind == "INSTALLED") (context.applicationContext as io.github.easylsp.app.EasylspApp).targetApplications.info(project.packageName, project.userId) else null
        release.targetCompatible(project.packageName, info?.longVersionCode)
        release.platformCompatible(android.os.Build.VERSION.SDK_INT)
        val existing = dao.plugins(project.id).singleOrNull { it.source in setOf("IMPORTED_RELEASE", "MARKET") && it.catalogPluginId == release.catalogPluginId && it.publisherId == release.publisherId && it.marketSourceId == marketSourceId && it.runtime == componentRuntime }
        require(restore || existing?.localModified != true) { "此插件有本地修改，请先保留为本地副本；不会覆盖修改" }
        require(existing == null || existing.runtime == componentRuntime) { "同一发行身份不能改变运行时" }
        require(rollback || existing?.releaseId == release.releaseId || existing?.marketVersionCode == null || release.versionCode > existing.marketVersionCode) { "更新 versionCode 必须递增；回退请显式选择缓存发行" }
        val cache = releaseCache(release.catalogPluginId, release.releaseId, marketSourceId)
        if (cache.exists()) {
            val cached = cache.walkTopDown().filter { it.isFile }.associate { it.relativeTo(cache).invariantSeparatorsPath to it.readBytes() }
            val received = entries.filterKeys { !it.endsWith('/') }
            require(cached.keys == received.keys && cached.all { (path, bytes) -> bytes.contentEquals(received.getValue(path)) }) { "同一 releaseId 的内容不能被替换" }
        } else {
            val temporary = File(cache.parentFile, ".${UUID.randomUUID()}").apply { mkdirs() }
            entries.forEach { (path, bytes) ->
                val file = File(temporary, path)
                if (path.endsWith('/')) file.mkdirs() else { file.parentFile!!.mkdirs(); file.writeBytes(bytes) }
            }
            check(temporary.renameTo(cache)) { "接收发行缓存失败" }
        }
        marketSignature?.let { signature ->
            trustFile(release.catalogPluginId, release.releaseId, marketSourceId).apply {
                parentFile!!.mkdirs(); writeText(signature.toString())
            }
        }
        if (!restore && existing?.releaseId == release.releaseId) return existing
        val schema = if (componentRuntime == "NATIVE") entries["config.schema.json"]?.toString(Charsets.UTF_8) ?: "{}" else "{}"
        val defaults = if (componentRuntime == "NATIVE") release.manifest.optJSONObject("config")?.getJSONObject("defaults")?.toString() ?: "{}" else "{}"
        val config = if (componentRuntime == "BEANSHELL") "{}" else configuration ?: existing?.configJson ?: defaults
        require(schema != "{}" || config == "{}") { "新版移除了配置字段，请先保存兼容配置" }
        if (schema != "{}") PluginConfiguration.validate(org.json.JSONObject(schema), org.json.JSONObject(config))
        val runtime = componentRuntime
        val revision = (existing?.revision ?: 0) + 1
        val nativeEntry = if ("src/main.cpp" in entries && "src/main.c" !in entries) "src/main.cpp" else "src/main.c"
        val row = PluginEntity(existing?.id ?: UUID.randomUUID().toString(), project.id, release.name + if (release.runtimes.size > 1) " · $runtime" else "", release.versionName, release.publisherId,
            release.manifest.getString("description"), existing?.desiredEnabled ?: false, revision,
            existing?.processName ?: release.target.optString("processName", project.packageName), existing?.processScope ?: release.target.getString("processScope"), runtime,
            if (runtime == "NATIVE") nativeEntry else "main.java", if (trustFile(release.catalogPluginId, release.releaseId, marketSourceId).isFile) "MARKET" else "IMPORTED_RELEASE", release.catalogPluginId, release.publisherId, release.releaseId, release.versionCode, config, schema, false, release.manifest.toString(), marketSourceId, release.target.optString("processType", "app"))
        val source = entries[if (runtime == "NATIVE") nativeEntry else "java/main.java"]?.toString(Charsets.UTF_8)
            ?: "#error This precompiled release does not include source. Select its imported build to publish.\n"
        sourceFile(row).apply { parentFile!!.mkdirs(); writeText(source) }
        val text = entries.filterKeys { runtime == "NATIVE" && (it.startsWith("src/") || it.startsWith("include/")) || it in setOf("README.md", "LICENSE", "config.schema.json") }
            .filterKeys { it != row.entry }.mapValues { it.value.toString(Charsets.UTF_8) }
        writeAttachments(row, text)
        if (runtime == "BEANSHELL") writeAttachments(row, entries.filterKeys {
            it.startsWith("java/") && it != "java/main.java" && !it.startsWith("java/lib/") && it != "java/"
        }.mapKeys { it.key.removePrefix("java/") }.mapValues { it.value.toString(Charsets.UTF_8) })
        entries.filterKeys { runtime == "BEANSHELL" && it.startsWith("java/lib/") && !it.endsWith('/') }.forEach { (path, bytes) -> File(libraryDirectory(row), path.removePrefix("java/lib/")).apply { parentFile!!.mkdirs(); writeBytes(bytes) } }
        val build = if (runtime == "NATIVE") {
            val id = UUID.randomUUID().toString()
            val artifact = File(context.filesDir, "workspaces/${project.id}/native-builds/$id/plugin.so").apply { parentFile!!.mkdirs(); writeBytes(entries.getValue("native/arm64-v8a/plugin.so")) }
            NativeBuildEntity(id, row.id, project.id, revision, project.sampleRevision, release.manifest.getString("sdkVersion"), "IMPORTED_RELEASE", minApi = release.artifact("NATIVE").getInt("minApi"), state = "SUCCEEDED", artifactPath = artifact.path, releaseId = release.releaseId, source = "IMPORTED_RELEASE")
        } else null
        transaction { if (existing == null) dao.insertPlugin(row) else dao.updatePlugin(row); build?.let(nativePlugins::saveBuild) }
        return row
    }
    suspend fun installCachedRelease(projectId: String, catalogPluginId: String, releaseId: String, configuration: String? = null, sourceId: String = "official"): PluginEntity = withContext(Dispatchers.IO) {
        require(catalogPluginId.matches(Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) && releaseId.matches(Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")))
        val cache = releaseCache(catalogPluginId, releaseId, sourceId)
        val entries = cache.walkTopDown().filter { it.isFile }.associate { it.relativeTo(cache).invariantSeparatorsPath to it.readBytes() }
        installRelease(requireNotNull(dao.project(projectId)), entries, rollback = true, configuration = configuration, marketSourceId = sourceId)
    }
    suspend fun cachedReleases(id: String): List<PluginRelease> = withContext(Dispatchers.IO) {
        val plugin = requireNotNull(dao.plugin(id))
        plugin.catalogPluginId?.let { catalog ->
            releaseCache(catalog, "", plugin.marketSourceId).listFiles().orEmpty().filter { it.isDirectory && !it.name.startsWith('.') }
                .map { PluginRelease.read(File(it, "manifest.json").readBytes()) }
                .filter { it.publisherId == plugin.publisherId }.sortedByDescending { it.versionCode }
        }.orEmpty()
    }
    suspend fun clearUnusedReleaseCache(): Int = withContext(Dispatchers.IO) {
        val referenced = dao.snapshotProjects().flatMap { dao.plugins(it.id) }.filter { it.catalogPluginId != null && it.releaseId != null }
            .map { releaseCache(it.catalogPluginId!!, it.releaseId!!, it.marketSourceId).canonicalPath }.toSet()
        val cached = listOf("release-cache", "release-cache-sources").asSequence().flatMap { File(context.filesDir, it).walkTopDown() }.filter { it.isFile && it.name == "manifest.json" }.map { it.parentFile!! }.toList()
        cached.count { directory ->
            if (directory.canonicalPath in referenced) false else {
                val manifest = PluginRelease.read(File(directory, "manifest.json").readBytes())
                val source = if (directory.toPath().startsWith(File(context.filesDir, "release-cache").toPath())) "official" else directory.parentFile!!.parentFile!!.name
                if (directory.deleteRecursively()) { trustFile(manifest.catalogPluginId, manifest.releaseId, source).delete(); true } else false
            }
        }
    }
    suspend fun preserveLocalCopy(id: String): PluginEntity = withContext(Dispatchers.IO) {
        val original = requireNotNull(dao.plugin(id))
        require(original.source in setOf("IMPORTED_RELEASE", "MARKET") && original.localModified)
        val copy = original.copy(id = UUID.randomUUID().toString(), name = original.name + " · 本地副本", revision = 1,
            source = "LOCAL", catalogPluginId = null, publisherId = null, releaseId = null, marketVersionCode = null,
            releaseManifest = "{}", localModified = false, desiredEnabled = false)
        sourceFile(copy).apply { parentFile!!.mkdirs(); writeText(sourceFile(original).readText()) }
        writeAttachments(copy, readFiles(original).filterKeys { it != original.entry })
        libraries(original).forEach { it.copyTo(File(libraryDirectory(copy).apply { mkdirs() }, it.name)) }
        dao.insertPlugin(copy)
        val cache = releaseCache(requireNotNull(original.catalogPluginId), requireNotNull(original.releaseId), original.marketSourceId)
        val entries = cache.walkTopDown().filter { it.isFile }.associate { it.relativeTo(cache).invariantSeparatorsPath to it.readBytes() }
        installComponent(requireNotNull(dao.project(original.projectId)), entries, true, true, null, null, original.marketSourceId, original.runtime)
        copy
    }
    suspend fun configurePlugin(id: String, config: String) = withContext(Dispatchers.IO) {
        val row = requireNotNull(dao.plugin(id))
        val values = org.json.JSONObject(config)
        if (row.configSchema != "{}") PluginConfiguration.validate(org.json.JSONObject(row.configSchema), values)
        require(values.toString().toByteArray().size <= 8192)
        dao.updatePlugin(row.copy(configJson = values.toString()))
    }
    /** Export only a caller-selected successful build; manifest and bytes are fixed together. */
    suspend fun exportRelease(id: String, buildId: String?, manifest: org.json.JSONObject, output: OutputStream, javaPluginId: String? = null) = withContext(Dispatchers.IO) {
        val plugin = requireNotNull(dao.plugin(id))
        val entries = linkedMapOf("manifest.json" to manifest.toString(2).toByteArray())
        val capabilities = manifest.getJSONArray("requiredCapabilities")
        val fullProgram = (0 until capabilities.length()).any { capabilities.getString(it) == "java.components" }
        if (manifest.getString("runtime") == "JAVA_NATIVE") {
            require(plugin.runtime == "NATIVE") { "联合发行从Native组件准备" }
            val java = requireNotNull(javaPluginId?.let(dao::plugin)) { "联合发行需要明确的Java组件ID" }
            require(java.projectId == plugin.projectId && java.runtime == "BEANSHELL" && java.processScope == plugin.processScope && java.processName == plugin.processName) { "联合组件必须属于同项目和进程范围" }
            entries["java/main.java"] = sourceFile(java).readBytes()
            readFiles(java).filterKeys { it != "main.java" && (fullProgram || it !in setOf("README.md", "LICENSE", "config.schema.json")) && !it.endsWith('/') }.forEach { (path, text) -> entries["java/" + path] = text.toByteArray() }
            libraries(java).forEach { entries["java/lib/${it.name}"] = it.readBytes() }
        }
        if (plugin.runtime == "NATIVE") {
            val build = requireNotNull(buildId?.let(nativePlugins::build)) { "Native 发行必须选择固定 buildId" }
            require(build.pluginId == id && build.state == "SUCCEEDED") { "构建必须属于此插件并已成功接收" }
            entries["native/arm64-v8a/plugin.so"] = File(build.artifactPath).readBytes()
            if (build.source == "LOCAL_BUILD") {
                val source = plugin.copy(revision = build.sourceRevision)
                readFiles(source).filterKeys { it.startsWith("src/") || it.startsWith("include/") }.forEach { (path, text) -> entries[path] = text.toByteArray() }
            }
        } else {
            entries["java/main.java"] = sourceFile(plugin).readBytes()
            readFiles(plugin).filterKeys { it != "main.java" && (fullProgram || it !in setOf("README.md", "LICENSE", "config.schema.json")) && !it.endsWith('/') }.forEach { (path, text) -> entries["java/" + path] = text.toByteArray() }
            libraries(plugin).forEach { entries["java/lib/${it.name}"] = it.readBytes() }
        }
        val files = readFiles(plugin)
        entries["README.md"] = (files["README.md"] ?: manifest.getString("description")).toByteArray()
        entries["LICENSE"] = requireNotNull(files["LICENSE"]) { "请先在插件附件 LICENSE 中保存许可证全文" }.toByteArray()
        if (manifest.has("config")) entries["config.schema.json"] = plugin.configSchema.toByteArray()
        PluginRelease.validateFiles(entries)
        ZipOutputStream(output).use { zip -> entries.forEach { (path, bytes) -> zip.putNextEntry(ZipEntry(path).apply { time = 0 }); zip.write(bytes); zip.closeEntry() } }
    }
    suspend fun exportPlugin(id: String, output: OutputStream) = withContext(Dispatchers.IO) {
        val plugin = requireNotNull(dao.plugin(id))
        val info = Properties().apply {
            setProperty("id", plugin.id); setProperty("name", plugin.name); setProperty("version", plugin.version)
            setProperty("author", plugin.author); setProperty("description", plugin.description); setProperty("apiVersion", "1"); setProperty("entry", plugin.entry); setProperty("processName", plugin.processName); setProperty("processScope", plugin.processScope)
        }
        if (plugin.runtime == "NATIVE") info.apply {
            setProperty("formatVersion", "2"); setProperty("runtime", "native"); setProperty("nativeApiVersion", "1")
            setProperty("abi", "arm64-v8a"); setProperty("minApi", "31")
        }
        if (plugin.runtime == "BEANSHELL" && "plugin.json" in readFiles(plugin)) info.setProperty("apiVersion", "2")
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("info.prop")); val writer = StringWriter(); info.store(writer, "Easylsp plugin"); zip.write(writer.toString().toByteArray()); zip.closeEntry()
            libraries(plugin).forEach { file -> zip.putNextEntry(ZipEntry("lib/${file.name}")); file.inputStream().use { it.copyTo(zip) }; zip.closeEntry() }
            readFiles(plugin).forEach { (path, text) -> zip.putNextEntry(ZipEntry(path)); if (!path.endsWith('/')) zip.write(text.toByteArray()); zip.closeEntry() }
        }
    }
}
