package io.github.easylsp.app.data

import org.json.JSONArray
import org.json.JSONObject

/** Format-v3 input validation is performed once at the ZIP acceptance boundary. */
data class PluginRelease(val manifest: JSONObject) {
    val catalogPluginId: String = manifest.getString("catalogPluginId")
    val releaseId: String = manifest.getString("releaseId")
    val publisherId: String = manifest.getString("publisherId")
    val versionCode: Long = manifest.getLong("versionCode")
    val versionName: String = manifest.getString("versionName")
    val runtime: String = manifest.getString("runtime")
    val name: String = manifest.getString("name")
    val runtimes: List<String> = if (runtime == "JAVA_NATIVE") listOf("NATIVE", "BEANSHELL") else listOf(runtime)
    val dependencies: JSONArray = manifest.optJSONArray("dependencies") ?: JSONArray()
    fun artifact(runtime: String): JSONObject = (0 until artifacts.length()).map { artifacts.getJSONObject(it) }
        .single { it.getString("artifactPath") == if (runtime == "NATIVE") "native/arm64-v8a/plugin.so" else "java/main.java" }
    val target: JSONObject = manifest.getJSONObject("target")
    val artifacts: JSONArray = manifest.getJSONArray("artifacts")
    fun targetCompatible(packageName: String, versionCode: Long?) {
        require(target.getString("packageName") == packageName) { "发行目标为 ${target.getString("packageName")}，请选择对应项目" }
        if (versionCode != null) {
            require(versionCode >= target.optLong("minVersionCode", 0) && (!target.has("maxVersionCode") || versionCode <= target.getLong("maxVersionCode"))) { "目标应用版本不在发行支持范围内" }
        }
    }
    fun platformCompatible(api: Int) {
        val minimum = (0 until artifacts.length()).maxOf { artifacts.getJSONObject(it).getInt("minApi") }
        require(minimum <= api) { "此发行要求 Android API $minimum，当前为 $api" }
    }
    companion object {
        val capabilities = setOf("native.symbol", "native.elf-vaddr", "native.module-lifecycle", "native.chain", "native.jni-register", "native.intercept", "native.plt", "native.runtime-register", "java.beanshell", "java.components", "java.services", "release.joint", "release.dependencies")
        fun read(bytes: ByteArray): PluginRelease {
            val m = JSONObject(bytes.toString(Charsets.UTF_8))
            require(m.getInt("formatVersion") == 3) { "不支持的发行格式" }
            listOf("catalogPluginId", "releaseId", "publisherId").forEach { key -> require(m.getString(key).matches(Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}"))) { "无效发行身份：$key" } }
            listOf("name", "versionName", "license", "sdkVersion", "channel", "publishedAt").forEach { require(m.getString(it).isNotBlank()) { "缺少发行字段：$it" } }
            m.getString("description"); m.getString("changelog")
            require(m.getString("sourceUrl").startsWith("https://")) { "源码 URL 必须使用 HTTPS" }
            require(m.getLong("versionCode") > 0 && m.getInt("pluginAbi") == 1 && m.getInt("minHostVersion") in 1..10) { "发行版本或宿主/插件 ABI 不兼容" }
            val runtime = m.getString("runtime")
            require(runtime in setOf("NATIVE", "BEANSHELL", "JAVA_NATIVE")) { "未知发行运行时" }
            val required = m.getJSONArray("requiredCapabilities")
            for (i in 0 until required.length()) require(required.getString(i) in capabilities) { "宿主不支持能力：${required.getString(i)}" }
            val target = m.getJSONObject("target")
            require(target.getString("packageName").matches(Regex("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+"))) { "无效目标包名" }
            require(target.getString("processScope") in setOf("main", "children", "all", "exact")) { "无效进程范围" }
            val processType = target.optString("processType", "app")
            require(processType in setOf("app", "isolated", "app_zygote")) { "无效进程类型" }
            require(processType == "app" || runtime == "NATIVE" && target.getString("processScope") == "exact") { "特殊进程发行必须为精确范围的Native" }
            if (target.getString("processScope") == "exact") {
                val process = target.getString("processName")
                require(processType == "app" && process == target.getString("packageName") || process.startsWith(target.getString("packageName") + ":") && process.length > target.getString("packageName").length + 1) { "进程必须属于发行目标" }
            }
            require(target.optLong("minVersionCode", 0) >= 0 && target.optLong("maxVersionCode", Long.MAX_VALUE) >= target.optLong("minVersionCode", 0))
            val artifacts = m.getJSONArray("artifacts")
            val types = if (runtime == "JAVA_NATIVE") listOf("NATIVE", "BEANSHELL") else listOf(runtime)
            require(artifacts.length() == types.size) { "发行产物必须与运行时一一对应" }
            val paths = (0 until artifacts.length()).map { artifacts.getJSONObject(it).getString("artifactPath") }
            require(paths.toSet() == types.map { if (it == "NATIVE") "native/arm64-v8a/plugin.so" else "java/main.java" }.toSet()) { "无效或重复产物路径" }
            for (i in 0 until artifacts.length()) {
                val artifact = artifacts.getJSONObject(i)
                require(artifact.getInt("minApi") in 1..36 && artifact.getString("abi") == (if (artifact.getString("artifactPath").startsWith("native/")) "arm64-v8a" else "any")) { "产物平台不兼容" }
            }
            m.optJSONArray("dependencies")?.let { deps ->
                require(deps.length() <= 16) { "最多16项直接依赖" }
                val identities = mutableSetOf<String>()
                for (i in 0 until deps.length()) {
                    val dep = deps.getJSONObject(i)
                    listOf("catalogPluginId", "publisherId", "releaseId").forEach { key -> require(dep.getString(key).matches(Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}"))) { "无效依赖身份：$key" } }
                    require(!dep.has("sourceId") || dep.getString("sourceId").matches(Regex("[a-zA-Z0-9_-]{1,64}"))) { "无效依赖市场来源" }
                    require(dep.getString("catalogPluginId") != m.getString("catalogPluginId") || dep.getString("publisherId") != m.getString("publisherId")) { "发行不能依赖自身" }
                    require(identities.add("${dep.optString("sourceId")}:${dep.getString("publisherId")}:${dep.getString("catalogPluginId")}")) { "依赖身份重复" }
                }
            }
            if (m.has("config")) {
                require(runtime != "BEANSHELL") { "当前 Java 运行时尚不支持发行配置" }
                val config = m.getJSONObject("config")
                require(config.getString("schemaPath") == "config.schema.json" && config.getInt("version") > 0)
                require(config.getJSONObject("defaults").toString().toByteArray().size <= 8192) { "配置超过 8 KiB" }
            }
            return PluginRelease(m)
        }
        fun validateFiles(files: Map<String, ByteArray>): PluginRelease {
            val release = read(files.getValue("manifest.json"))
            require("info.prop" !in files) { "v3 包不能混用旧 info.prop" }
            require("README.md" in files && "LICENSE" in files) { "发行必须附带 README.md 与 LICENSE" }
            val common = setOf("manifest.json", "README.md", "LICENSE", "config.schema.json")
            require(files.keys.all { path -> path in common || ("NATIVE" in release.runtimes && (path in setOf("native/", "native/arm64-v8a/", "native/arm64-v8a/plugin.so") || path.startsWith("src/") || path.startsWith("include/"))) || ("BEANSHELL" in release.runtimes && path.startsWith("java/")) }) { "发行包含未声明的载荷" }
            if ("NATIVE" in release.runtimes) NativePluginArtifact.validate(files.getValue("native/arm64-v8a/plugin.so"), release.artifact("NATIVE").getInt("minApi"))
            if ("BEANSHELL" in release.runtimes) {
                require("java/main.java" in files) { "缺少Java入口" }
                files.filterKeys { it.startsWith("java/lib/") && !it.endsWith('/') }.forEach { (path, data) -> PluginLibraries.validate(path.removePrefix("java/"), data) }
                files.keys.filter { it.startsWith("java/") && it != "java/" && it != "java/lib/" && !it.startsWith("java/lib/") }.forEach { PluginPaths.validate(it.removePrefix("java/")) }
                val program = io.github.easylsp.runtime.PluginProgram(files.filterKeys { it.startsWith("java/") && !it.startsWith("java/lib/") && !it.endsWith('/') }.mapKeys { it.key.removePrefix("java/") }.mapValues { it.value.toString(Charsets.UTF_8) })
                program.validate()
                val capabilities = release.manifest.getJSONArray("requiredCapabilities").let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
                if (program.files.keys.any { it != "main.java" }) require("java.components" in capabilities && release.manifest.getInt("minHostVersion") >= 10) { "多文件程序需要 java.components 和 minHostVersion=10" }
                if (program.requires.isNotEmpty() || program.provides.isNotEmpty()) require("java.services" in capabilities) { "服务声明需要 java.services" }
            }
            release.manifest.optJSONObject("config")?.let { config ->
                val schema = JSONObject(files.getValue("config.schema.json").toString(Charsets.UTF_8))
                PluginConfiguration.validate(schema, config.getJSONObject("defaults"))
            }
            return release
        }
    }
}

/** Deliberately small form contract; plugin-supplied scripts are never evaluated. */
object PluginConfiguration {
    fun validate(schema: JSONObject, values: JSONObject) {
        require(schema.getString("type") == "object" && schema.optBoolean("additionalProperties", false) == false) { "配置 schema 只支持固定对象字段" }
        require(schema.keys().asSequence().all { it in setOf("type", "properties", "required", "additionalProperties", "title", "description") }) { "不支持的配置 schema 关键字" }
        val properties = schema.getJSONObject("properties")
        properties.keys().forEach { key ->
            val field = properties.getJSONObject(key)
            require(field.keys().asSequence().all { it in setOf("type", "title", "description", "enum", "minimum", "maximum", "minLength", "maxLength") }) { "不支持的配置字段约束：$key" }
        }
        val required = schema.optJSONArray("required") ?: JSONArray()
        for (i in 0 until required.length()) require(values.has(required.getString(i))) { "缺少配置：${required.getString(i)}" }
        values.keys().forEach { key ->
            val field = properties.getJSONObject(key)
            val value = values.get(key)
            require(when (field.getString("type")) {
                "boolean" -> value is Boolean
                "number" -> value is Number
                "integer" -> value is Number && value.toDouble() == value.toLong().toDouble()
                "string" -> value is String
                else -> false
            }) { "配置类型不正确：$key" }
            field.optJSONArray("enum")?.let { choices -> require((0 until choices.length()).any { choices.get(it) == value }) { "配置枚举不匹配：$key" } }
            if (value is Number) require(value.toDouble().isFinite() && value.toDouble() >= field.optDouble("minimum", -Double.MAX_VALUE) && value.toDouble() <= field.optDouble("maximum", Double.MAX_VALUE)) { "配置数值超限：$key" }
            if (value is String) require(value.length >= field.optInt("minLength", 0) && value.length <= field.optInt("maxLength", 8192)) { "配置字符串长度超限：$key" }
        }
        // Validate every field even when omitted: unsupported nested forms must be rejected.
        properties.keys().forEach { key -> require(properties.getJSONObject(key).getString("type") in setOf("boolean", "number", "integer", "string")) { "暂不支持嵌套配置：$key" } }
        require(values.toString().toByteArray().size <= 8192) { "配置超过 8 KiB" }
    }
}
