package io.github.easylsp.app.platform.skills

import io.github.easylsp.app.util.readUpTo
import io.github.easylsp.app.EasylspApp
import io.github.easylsp.app.data.SkillEntity
import io.github.easylsp.app.platform.network.HttpCalls
import okhttp3.*
import org.json.*
import java.io.*
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

object PackagePaths {
    fun check(path: String) {
        require(path.isNotEmpty() && !path.startsWith('/') && !path.contains('\\') && path.trimEnd('/').split('/').none { it.isEmpty() || it == "." || it == ".." }) { "无效包内路径：$path" }
    }
    fun read(input: InputStream): Map<String, ByteArray> {
        val files = linkedMapOf<String, ByteArray>(); var total = 0
        ZipInputStream(input).use { zip -> while (true) {
            val entry = zip.nextEntry ?: break
            check(entry.name)
            if (entry.isDirectory) continue
            require(files.size < 500 && !files.containsKey(entry.name)) { "包条目过多或路径重复" }
            val bytes = zip.readUpTo(2 * 1024 * 1024 + 1); total += bytes.size
            require(bytes.size <= 2 * 1024 * 1024 && total <= 16 * 1024 * 1024) { "包大小超过限制：单文件 2 MiB、合计 16 MiB" }
            files[entry.name] = bytes
        } }
        val roots = files.keys.filter { it == "SKILL.md" || it.endsWith("/SKILL.md") }
        require(roots.size == 1) { "请选择只包含一个 SKILL.md 的 Skill 包" }
        val prefix = roots.single().removeSuffix("SKILL.md")
        return files.filterKeys { it.startsWith(prefix) }.mapKeys { it.key.removePrefix(prefix) }
    }
}
class SkillPackages(private val graph: EasylspApp) {
    fun import(input: InputStream, source: String = "本地 ZIP"): SkillEntity {
        val files = PackagePaths.read(input)
        val id = UUID.randomUUID().toString()
        val directory = File(graph.filesDir, "skill-packages/$id")
        val text = files.getValue("SKILL.md").toString(Charsets.UTF_8)
        val metadata = SkillMetadata.parse(text)
        val name = metadata.first
        files.forEach { (path, data) -> File(directory, path).apply { parentFile!!.mkdirs(); writeBytes(data) } }
        val skill = SkillEntity(id, name, text, false, "package", packagePath = directory.path, sourceUrl = source, description = metadata.second)
        graph.store.workflows.saveSkill(skill)
        return skill
    }
    fun metadata(skill: SkillEntity): Pair<String, String> = if (skill.text.trimStart().startsWith("---")) SkillMetadata.parse(skill.text)
        else skill.name to skill.text.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
    fun available(projectId: String?, includeDisabled: Boolean = false) = graph.store.workflows.skills().filter {
        (includeDisabled || it.enabled) && (it.projectId == null || it.projectId == projectId)
    }
    fun requireSkill(id: String, projectId: String?, enabled: Boolean = true): SkillEntity = requireNotNull(graph.store.workflows.skill(id)) { "Skill 不存在" }.also {
        require(it.projectId == null || it.projectId == projectId) { "Skill 不属于当前作用域" }
        require(!enabled || it.enabled) { "Skill 已停用" }
    }
    fun list(projectId: String?, args: JSONObject): JSONObject {
        val query = args.optString("query"); val cursor = args.optInt("cursor", 0); val limit = args.optInt("limit", 20)
        require(cursor >= 0 && limit > 0)
        val rows = available(projectId, args.optBoolean("includeDisabled")).filter { "${it.name} ${metadata(it).second}".contains(query, true) }
        val page = rows.drop(cursor).take(limit)
        return JSONObject().put("skills", JSONArray(page.map { val meta = metadata(it); JSONObject().put("id", it.id).put("name", meta.first).put("description", meta.second)
            .put("enabled", it.enabled).put("scope", if (it.projectId == null) "GLOBAL" else "PROJECT").put("revision", it.revision).put("runtime", it.runtime) }))
            .put("nextCursor", if (cursor + page.size < rows.size) cursor + page.size else JSONObject.NULL)
    }
    fun readResult(projectId: String?, args: JSONObject): JSONObject {
        val item = requireSkill(args.getString("skillId"), projectId, enabled = false)
        val path = args.optString("path", "SKILL.md")
        val text = if (item.packagePath.isEmpty()) { require(path == "SKILL.md"); item.text } else read(item, path)
        val offset = args.optInt("offset", 0); val limit = args.optInt("limit", 200); require(offset >= 0 && limit > 0)
        val lines = text.lines()
        return JSONObject().put("skillId", item.id).put("name", metadata(item).first).put("revision", item.revision).put("path", path)
            .put("instructions", lines.drop(offset).take(limit).joinToString("\n")).put("resources", JSONArray(resources(item)))
            .put("nextOffset", if (offset + limit < lines.size) offset + limit else JSONObject.NULL).put("unit", "lines")
    }
    fun guidance(id: String, projectId: String?): io.github.easylsp.app.platform.tools.SkillGuidance {
        val skill = requireSkill(id, projectId)
        val manifest = if (skill.packagePath.isNotEmpty() && File(skill.packagePath, "easylsp.json").exists()) JSONObject(read(skill, "easylsp.json")) else JSONObject()
        require(!manifest.has("schemaVersion") || manifest.getInt("schemaVersion") == 1) { "不支持的 Skill manifest 版本" }
        fun strings(key: String) = manifest.optJSONArray(key)?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
        return io.github.easylsp.app.platform.tools.SkillGuidance(id, skill.revision, if (skill.packagePath.isEmpty()) skill.text else read(skill, "SKILL.md"), strings("toolGroups"), strings("optionalToolGroups"), strings("scopes").ifEmpty { listOf("GLOBAL", "PROJECT") })
    }
    fun save(item: SkillEntity): SkillEntity {
        val meta = metadata(item)
        val text = if (item.text.trimStart().startsWith("---")) item.text else "---\nname: ${JSONObject.quote(meta.first)}\ndescription: ${JSONObject.quote(meta.second.ifBlank { item.name })}\n---\n\n${item.text}"
        val directory = File(item.packagePath.ifEmpty { File(graph.filesDir, "skill-packages/${item.id}").path })
        directory.mkdirs(); File(directory, "SKILL.md").writeText(text)
        return item.copy(name = meta.first, description = meta.second, text = text, packagePath = directory.path, revision = item.revision + 1).also(graph.store.workflows::saveSkill)
    }
    fun installPresets() {
        val preferences = graph.getSharedPreferences("skill-presets", 0)
        if (preferences.getBoolean("installed-v1", false)) return
        for (name in graph.assets.list("skill-presets").orEmpty()) {
            val files = JSONObject()
            fun read(path: String) {
                val children = graph.assets.list("skill-presets/$name/$path").orEmpty()
                if (children.isEmpty()) files.put(path, graph.assets.open("skill-presets/$name/$path").bufferedReader().use { it.readText() })
                else children.forEach { read(if (path.isEmpty()) it else "$path/$it") }
            }
            read(""); create(null, files)
        }
        graph.store.workflows.skills().filter { it.packagePath.isEmpty() }.forEach(::save)
        preferences.edit().putBoolean("installed-v1", true).commit()
    }
    fun create(projectId: String?, files: JSONObject): SkillEntity {
        val metadata = SkillMetadata.parse(files.getString("SKILL.md")); val id = UUID.randomUUID().toString()
        val directory = File(graph.filesDir, "skill-packages/$id")
        files.keys().forEach { path -> PackagePaths.check(path); File(directory, path).apply { parentFile!!.mkdirs(); writeText(files.getString(path)) } }
        return SkillEntity(id, metadata.first, files.getString("SKILL.md"), true, "package", packagePath = directory.path, description = metadata.second, projectId = projectId).also(graph.store.workflows::saveSkill)
    }
    fun update(projectId: String?, id: String, files: JSONObject, remove: JSONArray): SkillEntity {
        val item = requireSkill(id, projectId, enabled = false)
        val directory = File(item.packagePath.ifEmpty { File(graph.filesDir, "skill-packages/$id").path })
        val nextText = files.optString("SKILL.md", item.text); val meta = SkillMetadata.parse(nextText)
        val paths = files.keys().asSequence().toList(); paths.forEach(PackagePaths::check)
        val deleted = (0 until remove.length()).map { remove.getString(it) }; deleted.forEach { PackagePaths.check(it); require(it != "SKILL.md") }
        directory.mkdirs(); paths.forEach { File(directory, it).apply { parentFile!!.mkdirs(); writeText(files.getString(it)) } }
        deleted.forEach { File(directory, it).delete() }; File(directory, "SKILL.md").writeText(nextText)
        return item.copy(name = meta.first, description = meta.second, text = nextText, packagePath = directory.path, revision = item.revision + 1).also(graph.store.workflows::saveSkill)
    }
    fun export(skill: SkillEntity, output: OutputStream) {
        java.util.zip.ZipOutputStream(output).use { zip ->
            val paths = resources(skill).ifEmpty { listOf("SKILL.md") }
            for (path in paths) { zip.putNextEntry(java.util.zip.ZipEntry(path)); zip.write(if (skill.packagePath.isEmpty()) skill.text.toByteArray() else File(skill.packagePath, path).readBytes()); zip.closeEntry() }
        }
    }
    suspend fun github(url: String): SkillEntity {
        val uri = java.net.URI(url)
        require(uri.scheme == "https" && uri.host == "github.com") { "请输入 GitHub 仓库或 tree 目录地址" }
        val path = uri.path.trim('/').split('/')
        require(path.size >= 2) { "GitHub 地址缺少仓库" }
        val owner = path[0]; val repo = path[1].removeSuffix(".git")
        val ref = if (path.size > 3 && path[2] == "tree") path[3] else "HEAD"
        val subdir = if (path.size > 4 && path[2] == "tree") path.drop(4).joinToString("/") + "/" else ""
        val client = OkHttpClient.Builder().retryOnConnectionFailure(false).callTimeout(90, TimeUnit.SECONDS).build()
        return HttpCalls.execute(client, Request.Builder().url("https://api.github.com/repos/$owner/$repo/zipball/$ref").build()) { response ->
            val bytes = requireNotNull(response.body).byteStream().use { it.readUpTo(16 * 1024 * 1024 + 1) }
            require(bytes.size <= 16 * 1024 * 1024) { "仓库 ZIP 超过 16 MiB，请导入单个 Skill 的 ZIP" }
            if (subdir.isEmpty()) import(bytes.inputStream(), url) else {
                val selected = ByteArrayOutputStream()
                var selectedBytes = 0; var selectedCount = 0
                java.util.zip.ZipOutputStream(selected).use { output -> ZipInputStream(bytes.inputStream()).use { zip -> while (true) {
                    val entry = zip.nextEntry ?: break
                    val relative = entry.name.substringAfter('/', "")
                    if (!entry.isDirectory && relative.startsWith(subdir)) {
                        val data = zip.readUpTo(2 * 1024 * 1024 + 1); selectedBytes += data.size; selectedCount++
                        require(data.size <= 2 * 1024 * 1024 && selectedBytes <= 16 * 1024 * 1024 && selectedCount <= 500) { "Skill 包超过大小或条目限制" }
                        output.putNextEntry(java.util.zip.ZipEntry(relative.removePrefix(subdir))); output.write(data); output.closeEntry()
                    }
                } } }
                import(selected.toByteArray().inputStream(), url)
            }
        }
    }
    fun read(skill: SkillEntity, path: String): String {
        require(skill.packagePath.isNotEmpty()) { "此 Skill 没有目录包" }; PackagePaths.check(path)
        val file = File(skill.packagePath, path); require(file.isFile) { "包内文件不存在：$path" }
        return file.readText()
    }
    fun resources(skill: SkillEntity): List<String> = if (skill.packagePath.isEmpty()) emptyList() else File(skill.packagePath).walkTopDown().filter { it.isFile }.map { it.relativeTo(File(skill.packagePath)).invariantSeparatorsPath }.toList()
    suspend fun execute(projectId: String, skillId: String, path: String, arguments: JSONObject, runtime: String): String {
        val skill = requireNotNull(graph.store.workflows.skill(skillId)); require(skill.enabled)
        PackagePaths.check(path); require(path.startsWith("scripts/")) { "仅执行 scripts/ 下的脚本" }
        val source = File(skill.packagePath, path); require(source.isFile) { "脚本不存在" }
        val workspace = graph.executions.directory(projectId)
        val command = when (runtime) {
            "shell" -> graph.rootCommandForSkill(source, arguments)
            "python", "node", "bash" -> graph.linux.scriptCommand(projectId, source, runtime, arguments)
            else -> error("请选择 shell / python / node / bash 运行时")
        }
        return graph.executions.start(projectId, "skill", "${skill.name} · $path") { id, output -> graph.executions.runner.execute(id, command, workspace, closeInput = true, timeoutSeconds = skill.timeoutSeconds, output = output).exitCode }
    }
}
private fun EasylspApp.rootCommandForSkill(source: File, arguments: JSONObject): List<String> = listOf("/system/bin/sh", "-c", "export EASYLSP_ARGUMENTS=\"\$1\"; . \"\$2\"", "skill", arguments.toString(), source.path)

object SkillMetadata {
    fun parse(text: String): Pair<String, String> {
        val lines = text.removePrefix("\uFEFF").lines()
        require(lines.firstOrNull()?.trim() == "---") { "SKILL.md 需要 YAML frontmatter" }
        val end = (1 until lines.size).firstOrNull { lines[it].trim() in setOf("---", "...") } ?: error("frontmatter 未结束")
        val loader = org.yaml.snakeyaml.Yaml(org.yaml.snakeyaml.constructor.SafeConstructor(org.yaml.snakeyaml.LoaderOptions()))
        val data = loader.load<Map<String, Any>>(lines.subList(1, end).joinToString("\n"))
        val name = data["name"] as? String; val description = data["description"] as? String
        require(!name.isNullOrBlank() && !description.isNullOrBlank()) { "frontmatter 必须包含 name 和 description 字符串" }
        return name to description
    }
}
