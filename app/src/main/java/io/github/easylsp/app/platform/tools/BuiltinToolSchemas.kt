package io.github.easylsp.app.platform.tools

import org.json.JSONArray
import org.json.JSONObject

/** Parameter contracts advertised by both built-in catalogs at the MCP boundary. */
object BuiltinToolSchemas {
    fun create(name: String, fields: Map<String, String>, required: List<String>): JSONObject {
        val properties = JSONObject().apply { fields.forEach { (key, type) -> put(key, JSONObject().put("type", type)) } }
        fields.filterValues { it == "array" }.keys.forEach { key ->
            val item = when (key) {
                "sourceMessageIds" -> JSONObject().put("type", "integer")
                "items" -> JSONObject().put("type", "object").put("additionalProperties", false)
                    .put("properties", JSONObject().put("description", JSONObject().put("type", "string"))
                        .put("status", JSONObject().put("type", "string").put("enum", JSONArray(listOf("pending", "in_progress", "completed")))))
                    .put("required", JSONArray(listOf("description", "status")))
                else -> JSONObject().put("type", "string")
            }
            properties.getJSONObject(key).put("items", item)
        }
        if (name == "update_skill") properties.getJSONObject("files").put("additionalProperties", JSONObject().put("type", "string"))
        fun range(key: String, minimum: Int, maximum: Int, description: String) {
            properties.getJSONObject(key).put("minimum", minimum).put("maximum", maximum).put("description", description)
        }
        val pageUnit = when (name) {
            "read_plugin", "read_development_reference" -> "行"
            "read_plugin_logs" -> "条日志"
            "read_execution", "read_tool_output", "read_native_diagnostic" -> "字符"
            else -> null
        }
        if (pageUnit != null) {
            val maximum = if (pageUnit == "字符") 12000 else 200
            range("limit", 1, maximum, "每页数量（单位：$pageUnit），1..$maximum")
            properties.getJSONObject("offset").put("minimum", 0).put("description", "从 0 开始的$pageUnit 偏移；下一页使用返回的 nextOffset")
        }
        if (name == "run_command" || name == "read_execution") {
            range("waitSeconds", 1, 60, "等待本次执行的秒数，1..60；等待结束后仍可用 read_execution 查询同一 executionId")
        }
        if (name == "run_command" || name == "run_linux") {
            range("timeoutSeconds", 1, 3600, "命令执行时限，1..3600 秒；与模型 HTTP 请求时限独立")
        }
        if (name == "start_native_diagnostic") {
            range("durationSeconds", 5, 120, "采集时限，5..120 秒；读取结果不会重新启动目标")
            properties.getJSONObject("mode").put("enum", JSONArray(listOf("EXTERNAL_ONLY", "ENTRY_ONLY", "NATIVE_OBSERVE", "JAVA_NO_PLUGINS", "NORMAL")))
            properties.getJSONObject("restart").put("description", "true 会先停止目标的全部进程；入口对照必须为 true，默认 false")
        }
        if (name == "list_native_modules") {
            range("limit", 1, 200, "每页 SO 数量，1..200")
            properties.getJSONObject("offset").put("minimum", 0)
        }
        if (name == "disassemble_native") range("radiusBytes", 4, 1024, "选中地址前后各读取的字节数；AArch64 指令为 4 字节")
        if (name == "symbolize_native_address" || name == "disassemble_native") {
            properties.getJSONObject("addressKind").put("enum", JSONArray(listOf("ELF_VADDR", "TOMBSTONE_RELATIVE_PC", "FILE_OFFSET", "RUNTIME_PC")))
        }
        if (name == "create_plugin") properties.getJSONObject("runtime").put("enum", JSONArray(listOf("BEANSHELL", "NATIVE")))
        if (name == "enable_native_plugin") properties.getJSONObject("policy").put("enum", JSONArray(listOf("NATIVE_ONLY", "NATIVE_AND_JAVA")))
        return JSONObject().put("type", "object").put("additionalProperties", false)
            .put("properties", properties).put("required", JSONArray(required))
    }

    /** Flat object schemas work across model adapters; the controller enforces action-specific requirements. */
    fun phone(canonical: JSONObject): JSONObject {
        fun expand(source: JSONObject): JSONObject {
            if (source.has("\$ref")) return expand(canonical.getJSONObject("\$defs").getJSONObject(source.getString("\$ref").substringAfterLast('/')))
            if (source.has("oneOf")) {
                val properties = JSONObject()
                val alternatives = source.getJSONArray("oneOf")
                for (i in 0 until alternatives.length()) {
                    val fields = expand(alternatives.getJSONObject(i)).getJSONObject("properties")
                    fields.keys().forEach { properties.put(it, fields.get(it)) }
                }
                return JSONObject().put("type", "object").put("additionalProperties", false).put("properties", properties)
            }
            val result = JSONObject(source.toString())
            result.optJSONObject("properties")?.let { fields -> fields.keys().forEach { fields.put(it, expand(fields.getJSONObject(it))) } }
            result.optJSONObject("items")?.let { result.put("items", expand(it)) }
            if (result.has("enum") && !result.has("type")) result.put("type", "string")
            result.remove("allOf")
            return result
        }
        val schema = expand(canonical).put("required", JSONArray(listOf("action")))
        val actions = canonical.getJSONArray("oneOf")
        schema.getJSONObject("properties").put("action", JSONObject().put("type", "string")
            .put("enum", JSONArray((0 until actions.length()).map { actions.getJSONObject(it).getJSONObject("properties").getJSONObject("action").getString("const") })))
        return schema
    }
}
