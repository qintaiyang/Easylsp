package io.github.easylsp.app.platform.tools

import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID

/** UTF-16 code-unit cursors; every serialized page stays below Harness's 8192 character prune threshold. */
class ToolOutputStore(private val directory: File) {
    fun clearConversation(id: String) { File(directory, id).deleteRecursively() }
    fun pageResult(conversationId: String, result: JSONObject): JSONObject {
        val text = result.toString()
        if (text.length <= 7600) return result
        val id = UUID.randomUUID().toString()
        val file = File(directory, "$conversationId/$id.utf16")
        file.parentFile!!.mkdirs()
        file.outputStream().buffered().use { it.write(text.toByteArray(Charsets.UTF_16BE)) }
        return page(conversationId, id, 0, 12000)
    }
    fun select(conversationId: String, id: String, field: String, equals: JSONObject?): JSONObject {
        val root = JSONObject(outputFile(conversationId, id).readText(Charsets.UTF_16BE))
        var selected: Any = root
        if (field.isNotEmpty()) field.split('.').forEach { key -> selected = (selected as JSONObject).get(key) }
        if (equals != null) {
            val array = selected as org.json.JSONArray
            selected = org.json.JSONArray((0 until array.length()).map { array.getJSONObject(it) }.filter { row -> equals.keys().asSequence().all { row.opt(it) == equals.get(it) } })
        }
        return pageResult(conversationId, JSONObject().put("sourceOutputId", id).put("field", field).put("value", selected))
    }
    fun page(conversationId: String, id: String, offset: Int, limit: Int): JSONObject {
        require(offset >= 0 && limit in 1..12000)
        val file = outputFile(conversationId, id)
        return RandomAccessFile(file, "r").use { input ->
            val total = (input.length() / 2).toInt()
            require(offset <= total) { "offset 超过输出长度 $total" }
            val size = minOf(maxOf(2, limit), total - offset, 7400)
            input.seek(offset * 2L)
            val chars = CharArray(size) { input.readChar() }
            require(chars.isEmpty() || !chars[0].isLowSurrogate()) { "offset 位于 UTF-16 代理对中间；请使用返回的 nextOffset" }
            val text = String(chars)
            fun result(count: Int) = JSONObject().put("outputId", id).put("offset", offset).put("unit", "utf16")
                .put("text", text.take(count)).put("totalChars", total)
                .put("nextOffset", if (offset + count < total) offset + count else JSONObject.NULL)
                .put("readTool", "read_tool_output")
            var count = text.length
            while (result(count).toString().length > 7600) count = count * 3 / 4
            if (count > 1 && text[count - 1].isHighSurrogate()) count--
            result(count)
        }
    }
    private fun outputFile(conversationId: String, id: String): File {
        require(UUID.fromString(id).toString() == id)
        val file = File(directory, "$conversationId/$id.utf16")
        // Preserve references already persisted by older releases, converting once on first read.
        if (!file.exists()) {
            val original = File(directory, "$conversationId/$id.json")
            original.reader().use { input -> file.writer(Charsets.UTF_16BE).use { input.copyTo(it) } }
        }
        return file
    }
}
