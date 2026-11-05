package io.github.easylsp.app

import io.github.easylsp.app.data.PluginArchive
import io.github.easylsp.app.platform.tools.BuiltinToolSchemas
import io.github.easylsp.app.platform.tools.ToolArguments
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class NativePluginContractTest {
    private fun archive(vararg files: Pair<String, String>): ByteArray = ByteArrayOutputStream().also { bytes ->
        ZipOutputStream(bytes).use { zip -> files.forEach { (path, text) -> zip.putNextEntry(ZipEntry(path)); zip.write(text.toByteArray()); zip.closeEntry() } }
    }.toByteArray()
    private val metadata = "formatVersion=2\nruntime=native\nnativeApiVersion=1\nentry=src/main.c\nabi=arm64-v8a\nminApi=34\n"
    @Test fun nativeSourceArchiveIsTypedAndRejectsUnsupportedContract() {
        val accepted = PluginArchive.read(archive("info.prop" to metadata, "src/main.c" to "int main;", "include/types.h" to "typedef int value;").inputStream())
        assertTrue("src/main.c" in accepted)
        for (info in listOf(metadata.replace("arm64-v8a", "x86_64"), metadata.replace("=34", "=33"), metadata.replace("nativeApiVersion=1", "nativeApiVersion=2")))
            assertThrows(IllegalArgumentException::class.java) { PluginArchive.read(archive("info.prop" to info, "src/main.c" to "int main;").inputStream()) }
        assertThrows(IllegalArgumentException::class.java) { PluginArchive.read(archive("info.prop" to metadata, "main.java" to "void onLoad() {}").inputStream()) }
        assertThrows(IllegalArgumentException::class.java) { PluginArchive.read(archive("info.prop" to metadata, "src/main.c" to "int main;", "native/arm64-v8a/plugin.so" to "not ELF").inputStream()) }
    }
    @Test fun runtimeAndPolicyAreExplicitToolContracts() {
        val create = BuiltinToolSchemas.create("create_plugin", mapOf("name" to "string", "source" to "string", "runtime" to "string"), listOf("name", "source"))
        ToolArguments.validate(create, JSONObject().put("name", "native").put("source", "C").put("runtime", "NATIVE"))
        ToolArguments.validate(create, JSONObject().put("name", "java").put("source", "Java"))
        assertThrows(IllegalArgumentException::class.java) { ToolArguments.validate(create, JSONObject().put("name", "x").put("source", "x").put("runtime", "C++")) }
        val publish = BuiltinToolSchemas.create("enable_native_plugin", mapOf("pluginId" to "string", "buildId" to "string", "policy" to "string"), listOf("pluginId", "buildId", "policy"))
        assertThrows(IllegalArgumentException::class.java) { ToolArguments.validate(publish, JSONObject().put("pluginId", "x").put("buildId", "y")) }
    }
}
