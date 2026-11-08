package io.github.easylsp.app.platform.tools

import org.json.JSONArray
import org.json.JSONObject

/** Parameter contracts advertised by both built-in catalogs at the MCP boundary. */
object BuiltinToolSchemas {
    fun analysis(action: String): JSONObject {
        val fields = when (action) {
            "info", "manifest" -> emptyMap()
            "classes", "strings", "resources" -> mapOf("query" to "string")
            "methods", "fields" -> mapOf("query" to "string", "owner" to "string")
            "references" -> mapOf("target" to "string")
            "java", "smali" -> mapOf("className" to "string", "signature" to "string", "source" to "string")
            "resource" -> mapOf("apk" to "string", "entry" to "string")
            else -> error("未知分析操作 " + action)
        }
        val required = when (action) { "java" -> listOf("className"); "smali" -> listOf("className", "signature"); "references" -> listOf("target"); "resource" -> listOf("apk", "entry"); else -> emptyList() }
        return create("analysis_" + action, fields + mapOf("offset" to "integer", "limit" to "integer"), required)
    }
    fun inspection(operation: String): JSONObject {
        val fields = when (operation) {
            "viewTree" -> mapOf("query" to "string", "offset" to "integer", "limit" to "integer")
            "viewNode" -> mapOf("path" to "string", "snapshotId" to "string", "relation" to "string", "includeSnapshot" to "boolean", "snapshotLimit" to "integer")
            "viewClick" -> mapOf("path" to "string", "snapshotId" to "string", "includeSnapshot" to "boolean", "snapshotLimit" to "integer")
            "viewScroll" -> mapOf("path" to "string", "snapshotId" to "string", "forward" to "boolean", "includeSnapshot" to "boolean", "snapshotLimit" to "integer")
            "inspectOverlay" -> mapOf("enabled" to "boolean", "x" to "integer", "y" to "integer")
            "creationStacks", "performanceOverlay" -> mapOf("enabled" to "boolean")
            else -> emptyMap()
        }
        return create(operation, fields, when(operation) { "viewNode", "viewClick", "viewScroll" -> listOf("path", "snapshotId"); "creationStacks" -> listOf("enabled"); else -> emptyList() })
    }
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
        if (name == "analysis_query" || name == "inspect_target") {
            val operations = if (name == "analysis_query") listOf("info", "classes", "methods", "fields", "strings", "references", "java", "smali", "manifest", "resources", "resource")
                else listOf("viewTree", "viewNode", "viewClick", "viewScroll", "creationStacks", "inspectOverlay", "performanceStart", "performanceRead", "performanceStop", "performanceOverlay", "memory")
            val key = if (name == "analysis_query") "action" else "operation"
            properties.getJSONObject(key).put("enum", JSONArray(operations))
            properties.getJSONObject("arguments").put("anyOf", JSONArray(operations.map { op -> (if (name == "analysis_query") analysis(op) else inspection(op)).put("title", op) }))
            properties.getJSONObject("arguments").put("description", operations.joinToString("; ") { op -> op + ": " + (if (name == "analysis_query") analysis(op) else inspection(op)).toString() })
        }
        if (name == "viewTree") { rangeView(properties) }
        if (name in setOf("viewNode", "viewClick", "viewScroll")) {
            properties.getJSONObject("snapshotLimit").put("minimum", 1).put("maximum", 100).put("description", "可选快照节点上限，默认 10；截断时返回 snapshotTruncated")
            properties.getJSONObject("includeSnapshot").put("description", "默认只返回 node 摘要；viewNode relation=children 默认含直接子节点快照。true 返回有界快照。")
            if (name == "viewNode") properties.getJSONObject("relation").put("enum", JSONArray(listOf("self", "parent", "children", "ancestors")))
        }
        if (name == "read_tool_output") properties.getJSONObject("offset").put("description", "UTF-16 code units；使用原结果引用返回的 nextOffset。field 为点分字段路径；equals 为数组行字段的精确匹配。")
        if (name in setOf("update_skill", "write_plugin_files", "validate_plugin")) {
            if (properties.has("files")) properties.getJSONObject("files").put("additionalProperties", JSONObject().put("type", "string"))
        }
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
        if (name == "run_command" || name == "run_linux" || name == "read_execution") {
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
        if (name == "get_native_plugin_status") {
            properties.getJSONObject("scope").put("enum", JSONArray(listOf("current", "history")))
            range("limit", 1, 50, "每页实例数，默认 10；逐条来源通过 evidenceRead 读取")
            properties.getJSONObject("cursor").put("minimum", 0)
        }
        if (name == "get_plugin_coverage") {
            range("limit", 1, 50, "每页插件数，默认 10；历史 Native 实例使用 get_native_plugin_status(scope=history)")
            properties.getJSONObject("cursor").put("minimum", 0)
        }
        if (name == "enable_native_plugin") properties.getJSONObject("policy").put("enum", JSONArray(listOf("NATIVE_ONLY", "NATIVE_AND_JAVA")))
        return JSONObject().put("type", "object").put("additionalProperties", false)
            .put("properties", properties).put("required", JSONArray(required))
    }

    private fun rangeView(properties: JSONObject) {
        properties.getJSONObject("limit").put("minimum", 1).put("maximum", 100).put("description", "View 节点数，1..100；不支持 maxDepth/maxNodes")
        properties.getJSONObject("offset").put("minimum", 0)
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
