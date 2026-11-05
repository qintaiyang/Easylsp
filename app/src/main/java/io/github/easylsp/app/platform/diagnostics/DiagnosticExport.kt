package io.github.easylsp.app.platform.diagnostics

import io.github.easylsp.app.data.DiagnosticStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Export only attributed evidence. The system-wide logcat window never enters a share ZIP. */
class DiagnosticExport(private val store: DiagnosticStore) {
    fun files(project: String, id: String, includeRaw: Boolean): List<File> {
        require(store.requireRun(project, id).finishedAt != null) { "请先结束采集" }
        val report = store.read(project, id)
        val base = store.directory(project, id)
        val names = mutableSetOf("report.json", "report.md", "events.jsonl", "native-events.jsonl", "native-plugin-events.jsonl", "modules.json")
        File(base, "disassembly").listFiles()?.filter { it.extension == "txt" }?.forEach { names += "disassembly/${it.name}" }
        if (includeRaw) {
            val tombstones = report.getJSONArray("tombstones")
            for (i in 0 until tombstones.length()) names += tombstones.getJSONObject(i).getString("source")
            val processes = report.getJSONArray("processes")
            for (i in 0 until processes.length()) processes.getJSONObject(i).optString("maps").takeIf { it.isNotEmpty() }?.let { names += it }
        }
        return names.map { File(base, it) }.filter { it.isFile }
    }
    private fun redact(text: String): String = text.replace(Regex("/data/(?:user(?:_de)?/\\d+|data)/([^/\\s\"<>]+)")) { "<app-private:${it.groupValues[1]}>" }
    private fun redactJson(value: Any): Any = when (value) {
        is JSONObject -> JSONObject().also { result -> value.keys().forEach { result.put(it, redactJson(value.get(it))) } }
        is JSONArray -> JSONArray((0 until value.length()).map { redactJson(value.get(it)) })
        is String -> redact(value)
        else -> value
    }
    fun write(project: String, id: String, includeRaw: Boolean, output: OutputStream) {
        val base = store.directory(project, id)
        val files = files(project, id, includeRaw)
        val manifest = JSONObject().put("diagnosticRunId", id).put("includeRaw", includeRaw)
            .put("files", JSONArray(files.map { it.relativeTo(base).invariantSeparatorsPath }))
            .put("privatePaths", "app-private placeholders").put("excluded", JSONArray(listOf("APK", "SO", "memory", "system-wide logcat", "binary protobuf")))
        ZipOutputStream(output).use { zip ->
            fun entry(name: String, text: String) { zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray()); zip.closeEntry() }
            entry("manifest.json", manifest.toString(2))
            files.forEach { file ->
                val text = when (file.extension) {
                    "json" -> (redactJson(JSONObject(file.readText())) as JSONObject).toString(2)
                    "jsonl" -> file.useLines { lines -> lines.filter { it.isNotBlank() }.joinToString("\n", postfix = "\n") { redactJson(JSONObject(it)).toString() } }
                    else -> redact(file.readText())
                }
                entry(file.relativeTo(base).invariantSeparatorsPath, text)
            }
        }
    }
}
