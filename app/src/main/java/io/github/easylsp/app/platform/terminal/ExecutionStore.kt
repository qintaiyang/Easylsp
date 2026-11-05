package io.github.easylsp.app.platform.terminal
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class ExecutionRecord(val id: String, val workspaceId: String, val kind: String, val title: String, val state: String, val output: String = "", val exitCode: Int? = null, val startedAt: Long = System.currentTimeMillis(), val workspaceScope: String = "PROJECT") {
    val projectId: String? get() = workspaceId.takeIf { workspaceScope == "PROJECT" }
}
class ExecutionStore(private val root: File) {
    val records = MutableStateFlow<List<ExecutionRecord>>(emptyList())
    init {
        root.mkdirs()
        records.value = root.listFiles().orEmpty().filter { it.extension == "json" }.map { file ->
            val row = JSONObject(file.readText()); ExecutionRecord(row.getString("id"), row.optString("workspaceId", row.optString("projectId")), row.getString("kind"), row.getString("title"), row.getString("state").let { if (it == "RUNNING") "INTERRUPTED" else it }, exitCode = if (row.isNull("exitCode")) null else row.getInt("exitCode"), startedAt = row.getLong("startedAt"), workspaceScope = row.optString("workspaceScope", "PROJECT"))
        }.sortedByDescending { it.startedAt }
        records.value.filter { it.state == "INTERRUPTED" }.forEach(::save)
    }
    private fun checkId(id: String) { UUID.fromString(id) }
    fun file(id: String): File { checkId(id); return File(root, "$id.log") }
    @Synchronized fun save(row: ExecutionRecord) {
        checkId(row.id)
        File(root, "${row.id}.json").writeText(JSONObject().put("id", row.id).put("workspaceId", row.workspaceId).put("workspaceScope", row.workspaceScope).put("kind", row.kind).put("title", row.title).put("state", row.state).put("exitCode", row.exitCode ?: JSONObject.NULL).put("startedAt", row.startedAt).toString())
        records.update { list -> (list.filter { it.id != row.id } + row).sortedByDescending { it.startedAt } }
    }
    @Synchronized fun append(id: String, text: String) {
        file(id).appendText(text)
        records.update { rows -> rows.map { if (it.id == id) it.copy(output = (it.output + text).takeLast(24000)) else it } }
    }
    @Synchronized fun replaceOutput(id: String, text: String) {
        file(id).writeText(text)
        records.update { rows -> rows.map { if (it.id == id) it.copy(output = text.takeLast(24000)) else it } }
    }
    @Synchronized fun clear(projectId: String, keepIds: Set<String> = emptySet()): Int {
        val rows = records.value.filter { it.workspaceId == projectId && it.state != "RUNNING" && it.id !in keepIds }
        rows.forEach { row -> File(root, "${row.id}.json").delete(); file(row.id).delete() }
        records.value = records.value.filter { row -> rows.none { it.id == row.id } }
        return rows.size
    }
    fun page(projectId: String, id: String, offset: Int, limit: Int): JSONObject {
        val row = records.value.firstOrNull { it.id == id && it.workspaceId == projectId } ?: error("执行记录不属于当前项目")
        require(offset >= 0 && limit in 1..12000) { "offset 必须 >= 0；limit 必须在 1..12000 字符范围内" }
        val text = file(id).takeIf { it.exists() }?.reader()?.use { reader ->
            var remaining = offset.toLong(); while (remaining > 0) { val skipped = reader.skip(remaining); if (skipped == 0L) break; remaining -= skipped }
            val chars = CharArray(limit + 1); var size = 0; while (size < chars.size) { val n = reader.read(chars, size, chars.size - size); if (n < 0) break; size += n }; String(chars, 0, size)
        } ?: ""
        return JSONObject().put("id", id).put("kind", row.kind).put("state", row.state).put("exitCode", row.exitCode ?: JSONObject.NULL).put("text", text.take(limit)).put("nextOffset", if (text.length > limit) offset + limit else JSONObject.NULL)
    }
}
