package io.github.easylsp.app.platform.nativeplugins

import io.github.easylsp.app.EasylspApp
import io.github.easylsp.app.data.NativeBuildEntity
import io.github.easylsp.app.data.PluginEntity
import io.github.easylsp.app.platform.root.RootCommand
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Immutable source/build snapshots; compilation never needs a running target session. */
class NativeBuildService(private val graph: EasylspApp) {
    private val sdk = "/opt/easylsp-native-sdk-r28c-api34-p0-v1"
    suspend fun prepare(projectId: String): JSONObject {
        val archive = File(graph.executions.directory(projectId), "native-sdk-android34-r28c.tar")
        graph.assets.open("native-sdk/android34-r28c.tar").use { input -> archive.outputStream().use { input.copyTo(it) } }
        val script = "set -e; test \"\$(uname -m)\" = aarch64; apt-get update; DEBIAN_FRONTEND=noninteractive apt-get install -y clang-18 lld-18 python3; " +
            "mkdir -p $sdk; tar -xf ${RootCommand.quote("/workspace/$projectId/${archive.name}")} -C $sdk; clang-18 --version; ld.lld-18 --version; cat $sdk/sdk.json"
        val command = graph.linux.command(projectId, script)
        val execution = graph.executions.start(projectId, "native-build-tools", "准备 Android C17 编译工具") { id, output ->
            graph.executions.runner.execute(id, command, graph.executions.directory(projectId), closeInput = true, timeoutSeconds = 600, output = output).exitCode
        }
        return JSONObject().put("executionId", execution).put("sdkVersion", "1").put("ndkVersion", "28.2.13676358")
            .put("compiler", "Ubuntu clang-18/lld-18").put("abi", "arm64-v8a").put("minApi", 34)
    }
    fun directory(projectId: String, buildId: String): File {
        UUID.fromString(buildId)
        return File(graph.executions.directory(projectId), "native-builds/$buildId")
    }
    suspend fun build(plugin: PluginEntity, sourceRevision: Long, sampleRevision: Long): JSONObject {
        require(plugin.runtime == "NATIVE" && sourceRevision in 1..plugin.revision) { "需要 native 插件的已保存源码 revision" }
        return compile(plugin.projectId, plugin.id, sourceRevision, sampleRevision, graph.store.readFiles(plugin.copy(revision = sourceRevision)))
    }
    suspend fun buildPrototype(projectId: String, pluginId: String, sourceRevision: Long, sampleRevision: Long, source: String): JSONObject {
        return compile(projectId, pluginId, sourceRevision, sampleRevision, mapOf("src/main.c" to source))
    }
    private suspend fun compile(projectId: String, pluginId: String, sourceRevision: Long, sampleRevision: Long, files: Map<String, String>): JSONObject {
        UUID.fromString(pluginId)
        require(sourceRevision > 0 && sampleRevision >= 0)
        val buildId = UUID.randomUUID().toString()
        val directory = directory(projectId, buildId)
        check(directory.mkdirs())
        File(directory, "src").mkdir()
        File(directory, "include").mkdir()
        files.forEach { (path, source) ->
            val file = File(directory, path)
            if (path.endsWith('/')) file.mkdirs() else { file.parentFile!!.mkdirs(); file.writeText(source) }
        }
        graph.assets.open("native-sdk/easylsp_native_plugin.h").use { input ->
            File(directory, "include/easylsp_native_plugin.h").outputStream().use { input.copyTo(it) }
        }
        val script = "set -e; test -f $sdk/sdk.json; exec /bin/bash $sdk/bin/build-plugin.sh " +
            RootCommand.quote("/workspace/$projectId/native-builds/$buildId") + " " + RootCommand.quote(buildId)
        val command = graph.linux.command(projectId, script)
        val execution = graph.executions.start(projectId, "native-build", "Android native 构建 $buildId") { id, output ->
            val row = NativeBuildEntity(buildId, pluginId, projectId, sourceRevision, sampleRevision, "1", "Ubuntu Clang/LLD 18 + NDK r28c", executionId = id)
            graph.store.nativePlugins.saveBuild(row)
            val record = JSONObject().put("buildId", buildId).put("pluginId", pluginId).put("projectId", projectId)
                .put("sourceRevision", sourceRevision).put("sampleRevision", sampleRevision).put("sdkVersion", "1")
                .put("toolchainVersion", "Ubuntu clang-18/lld-18 + NDK 28.2.13676358").put("abi", "arm64-v8a")
                .put("minApi", 34).put("executionId", id).put("state", "BUILDING")
            val file = File(directory, "build.json")
            file.writeText(record.toString(2))
            try {
                val code = graph.executions.runner.execute(id, command, graph.executions.directory(projectId), closeInput = true, timeoutSeconds = 120, output = output).exitCode
                record.put("state", if (code == 0) "SUCCEEDED" else "FAILED").put("exitCode", code)
                if (code == 0) record.put("artifact", "plugin.so").put("elf", JSONObject(File(directory, "elf.json").readText()))
                file.writeText(record.toString(2))
                graph.store.nativePlugins.saveBuild(row.copy(state = if (code == 0) "SUCCEEDED" else "FAILED", artifactPath = if (code == 0) File(directory, "plugin.so").path else "", error = if (code == 0) "" else "编译/产物接收失败，读取 executionId=$id"))
                code
            } catch (error: Exception) {
                record.put("state", "FAILED").put("error", error.toString())
                file.writeText(record.toString(2))
                graph.store.nativePlugins.saveBuild(row.copy(state = "FAILED", error = error.toString()))
                throw error
            }
        }
        return JSONObject().put("buildId", buildId).put("executionId", execution).put("sourceRevision", sourceRevision).put("state", "BUILDING")
    }
}
