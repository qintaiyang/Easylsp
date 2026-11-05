package io.github.easylsp.app.platform.tools

import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Long results are stored once; Harness keeps the first page and a scoped reference. */
class ToolOutputStore(private val directory: File) {
    fun pageResult(conversationId: String, result: JSONObject): JSONObject {
        val text = result.toString()
        if (text.length <= 12000) return result
        val id = UUID.randomUUID().toString()
        File(directory, "$conversationId/$id.json").apply { parentFile!!.mkdirs(); writeText(text) }
        return page(conversationId, id, 0, 12000)
    }
    fun page(conversationId: String, id: String, offset: Int, limit: Int): JSONObject {
        require(UUID.fromString(id).toString() == id && offset >= 0 && limit in 1..12000)
        val file = File(directory, "$conversationId/$id.json")
        val total = file.reader().use { reader -> var count = 0; val buffer = CharArray(8192); while (true) { val n = reader.read(buffer); if (n < 0) break; count += n }; count }
        val text = file.reader().use { reader ->
            var remaining = offset.toLong(); while (remaining > 0) { val skipped = reader.skip(remaining); if (skipped == 0L) break; remaining -= skipped }
            val chars = CharArray(limit); var size = 0; while (size < limit) { val n = reader.read(chars, size, limit - size); if (n < 0) break; size += n }; String(chars, 0, size)
        }
        return JSONObject().put("outputId", id).put("text", text).put("totalChars", total)
            .put("nextOffset", if (offset + limit < total) offset + limit else JSONObject.NULL)
    }
}
