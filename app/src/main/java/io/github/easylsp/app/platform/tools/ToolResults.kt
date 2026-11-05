package io.github.easylsp.app.platform.tools

import org.json.JSONArray
import org.json.JSONObject

/** Keep MCP multimodal blocks out of text paging and preserve upstream attachment admission. */
class ToolPayload(val response: JSONObject) : JSONObject(response.optJSONObject("structuredContent")?.toString() ?: "{}")
object ToolResults {
    fun isMedia(value: JSONObject) = value is ToolPayload
    fun mcp(value: JSONObject): JSONObject = if (value is ToolPayload) value.response else JSONObject()
        .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", value.toString()))).put("structuredContent", value)
    fun image(metadata: JSONObject, mimeType: String, data: String): ToolPayload = ToolPayload(JSONObject()
        .put("structuredContent", metadata).put("content", JSONArray()
            .put(JSONObject().put("type", "text").put("text", metadata.toString()))
            .put(JSONObject().put("type", "image").put("mimeType", mimeType).put("data", data))))
}
