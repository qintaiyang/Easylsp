package io.github.easylsp.app.platform.tools

import io.github.easylsp.app.util.readUpTo
import io.github.easylsp.app.EasylspApp
import io.github.easylsp.app.platform.network.HttpCalls
import okhttp3.*
import org.json.*
import java.io.File
import java.nio.file.Files

/** File paths locate resources; they do not define a new filesystem sandbox. */
class WorkspaceTools(private val graph: EasylspApp, private val binding: RunBinding) {
    companion object { private val fileLocks = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.sync.Mutex>() }
    private suspend fun <T> editing(paths: List<File>, block: () -> T): T {
        val locks = paths.map { it.canonicalPath }.distinct().sorted().map { fileLocks.getOrPut(it) { kotlinx.coroutines.sync.Mutex() } }
        val held = mutableListOf<kotlinx.coroutines.sync.Mutex>()
        try { for (lock in locks) { lock.lock(); held += lock }; return block() }
        finally { held.asReversed().forEach { it.unlock() } }
    }
    private fun file(path: String): File = File(if (path.startsWith("/workspace/" + binding.workspace.id + "/"))
        graph.executions.directory(binding.workspace.id).path + "/" + path.removePrefix("/workspace/" + binding.workspace.id + "/") else path).let { if (it.isAbsolute) it else File(graph.executions.directory(binding.workspace.id), path) }.canonicalFile
    fun definitions(): List<ToolDefinition> {
        val tools = mutableListOf<ToolDefinition>()
        fun add(name: String, description: String, fields: Map<String,String>, required: List<String>, writes: Boolean = false, call: suspend (JSONObject) -> JSONObject) {
            tools += ToolDefinition(name, description, BuiltinToolSchemas.create(name, fields, required), writes = writes, invoke = call, groupId = "workspace.files", defaultAvailable = true)
        }
        fun page(args: JSONObject): Pair<Int, Int> {
            val offset = args.optInt("cursor", 0); val limit = args.optInt("limit", 100)
            require(offset >= 0 && limit > 0); return offset to limit
        }
        add("list_files", "List a directory. Relative paths use the current conversation workspace; absolute paths use app filesystem permissions. cursor counts entries.", mapOf("path" to "string", "cursor" to "integer", "limit" to "integer"), emptyList()) { args ->
            val directory = file(args.optString("path", ".")); val rows = requireNotNull(directory.listFiles()) { "无法读取目录：$directory" }.sortedBy { it.name }
            val (offset, limit) = page(args); val selected = rows.drop(offset).take(limit)
            JSONObject().put("path", directory.path).put("entries", JSONArray(selected.map { JSONObject().put("name", it.name).put("directory", it.isDirectory).put("bytes", it.length()) }))
                .put("nextCursor", if (offset + selected.size < rows.size) offset + selected.size else JSONObject.NULL)
        }
        add("read_file", "Read a UTF-8 text file by zero-based line offset; nextOffset continues reading the same file.", mapOf("path" to "string", "offset" to "integer", "limit" to "integer"), listOf("path")) { args ->
            val target = file(args.getString("path")); val offset = args.optInt("offset", 0); val limit = args.optInt("limit", 200)
            require(offset >= 0 && limit > 0)
            require(target.canRead()) { "无法读取 " + target.path + "；读取身份 uid=" + android.os.Process.myUid() + "；请由生成该产物的任务赋予项目组读取权限" }
            val lines = target.useLines { it.drop(offset).take(limit + 1).toList() }
            JSONObject().put("path", target.path).put("unit", "lines").put("startLine", offset + 1).put("text", lines.take(limit).joinToString("\n"))
                .put("nextOffset", if (lines.size > limit) offset + limit else JSONObject.NULL)
        }
        add("search_files", "Search filenames and UTF-8 file contents for literal query text. Returns paths, line numbers and a match cursor. Binary files are skipped.", mapOf("query" to "string", "path" to "string", "cursor" to "integer", "limit" to "integer"), listOf("query")) { args ->
            val base = file(args.optString("path", ".")); val query = args.getString("query"); val (offset, limit) = page(args)
            val matches = mutableListOf<JSONObject>(); var count = 0
            Files.walk(base.toPath()).use { stream ->
                val paths = stream.sorted().iterator()
                while (paths.hasNext() && matches.size <= limit) {
                    val target = paths.next().toFile(); if (!target.isFile) continue
                    if (target.name.contains(query, true)) { if (count++ >= offset) matches += JSONObject().put("path", target.path).put("line", 0).put("text", target.name) }
                    target.inputStream().use { input ->
                        val prefix = input.readUpTo(4096)
                        if (prefix.contains(0.toByte())) return@use
                        target.useLines { lines ->
                            val iterator = lines.iterator(); var line = 0
                            while (iterator.hasNext() && matches.size <= limit) {
                                val text = iterator.next(); line++
                                if (text.contains(query, true) && count++ >= offset) matches += JSONObject().put("path", target.path).put("line", line).put("text", text)
                            }
                        }
                    }
                }
            }
            JSONObject().put("matches", JSONArray(matches.take(limit))).put("nextCursor", if (matches.size > limit) offset + limit else JSONObject.NULL)
        }
        add("write_file", "Write UTF-8 text to a file, creating its parent directories. Returns the actual path and byte count.", mapOf("path" to "string", "text" to "string"), listOf("path", "text"), true) { args ->
            val target = file(args.getString("path"))
            editing(listOf(target)) { target.parentFile!!.mkdirs(); target.writeText(args.getString("text")); JSONObject().put("path", target.path).put("bytes", target.length()) }
        }
        add("apply_patch", "Apply a standard unified diff with ---/+++ paths and @@ line hunks. /dev/null adds or deletes a file; context must match the actual file. Relative paths use this workspace.", mapOf("patch" to "string"), listOf("patch"), true) { args ->
            val patch = args.getString("patch")
            val paths = patch.lines().filter { it.startsWith("--- ") || it.startsWith("+++ ") }.map { it.substring(4).substringBefore('\t').removePrefix("a/").removePrefix("b/") }.filter { it != "/dev/null" }.map(::file)
            editing(paths) {
            val changes = UnifiedPatch.parse(patch, ::file)
            changes.forEach { (target, text) -> if (text == null) check(target.delete()) { "删除失败：$target" } else { target.parentFile!!.mkdirs(); target.writeText(text) } }
            JSONObject().put("paths", JSONArray(changes.keys.map { it.path }))
            }
        }
        tools += ToolDefinition("fetch_url", "Fetch an HTTP(S) URL as text. Returns its actual final URL, content type and body; this is URL fetching, not a search engine.", BuiltinToolSchemas.create("fetch_url", mapOf("url" to "string"), listOf("url")), invoke = { args ->
            val client = OkHttpClient.Builder().retryOnConnectionFailure(false).build()
            HttpCalls.execute(client, Request.Builder().url(args.getString("url")).build()) { response ->
                JSONObject().put("url", response.request.url.toString()).put("status", response.code).put("contentType", response.header("Content-Type")).put("text", requireNotNull(response.body).string())
            }
        }, groupId = "network.fetch")
        return tools
    }
}

object UnifiedPatch {
    fun parse(patch: String, resolve: (String) -> File): Map<File, String?> {
        val lines = patch.lines(); val changes = linkedMapOf<File, String?>(); var index = 0
        fun path(header: String) = header.substring(4).substringBefore('\t').removePrefix("a/").removePrefix("b/")
        while (index < lines.size) {
            if (lines[index].isEmpty() || lines[index].startsWith("diff ") || lines[index].startsWith("index ")) { index++; continue }
            require(lines[index].startsWith("--- ")) { "需要 unified diff 的 --- 文件头" }
            val oldPath = path(lines[index++]); require(index < lines.size && lines[index].startsWith("+++ "))
            val newPath = path(lines[index++]); val target = resolve(if (newPath == "/dev/null") oldPath else newPath)
            require(oldPath == "/dev/null" || newPath == "/dev/null" || oldPath == newPath) { "重命名请分为删除和新增" }
            val original = if (oldPath == "/dev/null") "" else target.readText()
            if (oldPath == "/dev/null") require(!target.exists()) { "新增文件已存在：$target" }
            val source = if (original.isEmpty()) emptyList() else original.removeSuffix("\n").split('\n')
            val output = mutableListOf<String>(); var consumed = 0; var finalNewline = original.endsWith("\n") || oldPath == "/dev/null"
            while (index < lines.size && lines[index].startsWith("@@")) {
                val header = requireNotNull(Regex("@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@.*").matchEntire(lines[index++])) { "无效 hunk 头" }
                val start = header.groupValues[1].toInt().let { if (header.groupValues[2] == "0") it else it - 1 }
                require(start in consumed..source.size) { "补丁行号超出文件" }; output += source.subList(consumed, start); consumed = start
                val oldCount = header.groupValues[2].ifEmpty { "1" }.toInt(); val newCount = header.groupValues[4].ifEmpty { "1" }.toInt()
                var removed = 0; var added = 0
                while (removed < oldCount || added < newCount) {
                    require(index < lines.size); val line = lines[index++]
                    require(line.isNotEmpty()) { "补丁行必须含空格、+ 或 - 前缀" }
                    when (line.first()) {
                        ' ', '-' -> { require(consumed < source.size && source[consumed] == line.drop(1)) { "补丁上下文不匹配：$target:${consumed + 1}" }; consumed++; removed++; if (line.first() == ' ') { output += line.drop(1); added++ } }
                        '+' -> { output += line.drop(1); added++; if (consumed == source.size) finalNewline = true }
                        '\\' -> { require(line == "\\ No newline at end of file"); if (index >= 2 && lines[index - 2].firstOrNull() != '-') finalNewline = false }
                        else -> error("无效补丁行")
                    }
                }
                if (index < lines.size && lines[index].startsWith("\\ No newline")) { if (lines[index - 1].firstOrNull() != '-') finalNewline = false; index++ }
                require(removed == oldCount && added == newCount)
            }
            if (newPath == "/dev/null") require(consumed == source.size && output.isEmpty()) { "删除补丁必须覆盖完整文件" }
            output += source.drop(consumed)
            changes[target] = if (newPath == "/dev/null") null else output.joinToString("\n") + if (finalNewline && output.isNotEmpty()) "\n" else ""
        }
        require(changes.isNotEmpty()) { "补丁没有文件修改" }; return changes
    }
}
