package io.github.easylsp.app.data

import io.github.easylsp.app.util.readUpTo
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.zip.ZipInputStream

object PluginPaths {
    fun validate(path: String, allowLibrary: Boolean = false) {
        val name = path.removeSuffix("/")
        require(allowLibrary || (name != "lib" && !name.startsWith("lib/"))) { "lib/ 由依赖管理工具维护" }
        require(name.isNotBlank() && !name.startsWith('/') && '\\' !in name && name.split('/').none { it.isEmpty() || it == "." || it == ".." } && name != "info.prop" && !name.startsWith("info.prop/")) { "无效的插件文本路径：$path" }
    }
}
object PluginArchive {
    fun read(input: InputStream): Map<String, ByteArray> {
        val files = linkedMapOf<String, ByteArray>()
        var total = 0
        ZipInputStream(input).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val path = entry.name
                if (path != "info.prop") PluginPaths.validate(path, allowLibrary = true)
                require(path !in files && files.size < 200) { "ZIP 文件重复或超过 200 项" }
                val library = (path.startsWith("lib/") || path.startsWith("java/lib/")) && !path.endsWith('/')
                val native = path == "native/arm64-v8a/plugin.so"
                val max = if (library || native) 16 * 1024 * 1024 else 1024 * 1024
                val bytes = zip.readUpTo(max + 1)
                total += bytes.size
                require(bytes.size <= max && total <= 32 * 1024 * 1024) { "文本文件上限 1 MiB，依赖上限 16 MiB，插件合计上限 32 MiB" }
                if (library) PluginLibraries.validate(path.removePrefix("java/"), bytes) else if (!native) {
                Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes))
                require(bytes.none { it == 0.toByte() }) { "附属文件只支持 UTF-8 文本" }
                }
                files[path] = bytes
                entry = zip.nextEntry
            }
        }
        require(files.keys.none { key -> files.keys.any { other -> other != key && !other.endsWith('/') && key.startsWith("$other/") } }) { "ZIP 文件和目录路径冲突" }
        if ("manifest.json" in files) { PluginRelease.validateFiles(files); return files }
        require("info.prop" in files) { "缺少根目录 info.prop" }
        val info = java.util.Properties().apply { load(java.io.StringReader(files.getValue("info.prop").toString(Charsets.UTF_8))) }
        require(info.getProperty("runtime", "beanshell").lowercase() in setOf("native", "beanshell")) { "未知插件 runtime" }
        if (info.getProperty("runtime", "beanshell").equals("native", true)) {
            require(info.getProperty("formatVersion") == "2" && info.getProperty("nativeApiVersion") == "1" && info.getProperty("entry") in setOf("src/main.c", "src/main.cpp") && info.getProperty("abi") == "arm64-v8a" && info.getProperty("minApi") in setOf("31", "34")) { "不支持的 native ZIP 契约" }
            require(info.getProperty("entry") in files) { "缺少 Native 入口源码" }
            require(files.keys.all { it == "info.prop" || it.startsWith("src/") || it.startsWith("include/") || it in setOf("README.md", "LICENSE", "config.schema.json", "native/", "native/arm64-v8a/", "native/arm64-v8a/plugin.so", "native/build.json") }) { "native ZIP 只接受 src/、include/、native/ 和 README.md、LICENSE、config.schema.json" }
            files["native/arm64-v8a/plugin.so"]?.let { NativePluginArtifact.validate(it, info.getProperty("minApi").toInt()) }
        } else {
            require("main.java" in files && files.keys.none { it.startsWith("native/") }) { "缺少 main.java 或 Java ZIP 包含 native 产物" }
            require(info.getProperty("apiVersion") in setOf("1", "2")) { "不支持的 Java API" }
            require("plugin.json" !in files || info.getProperty("apiVersion") == "2") { "多文件执行需要 apiVersion=2" }
            io.github.easylsp.runtime.PluginProgram(files.filterKeys { !it.startsWith("lib/") && !it.endsWith('/') && it != "info.prop" }.mapValues { it.value.toString(Charsets.UTF_8) }).validate()
        }
        require(files.keys.none { key -> files.keys.any { other -> other != key && !other.endsWith('/') && key.startsWith("$other/") } }) { "ZIP 文件和目录路径冲突" }
        return files
    }
}
