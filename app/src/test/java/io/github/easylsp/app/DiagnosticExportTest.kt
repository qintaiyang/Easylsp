package io.github.easylsp.app

import io.github.easylsp.app.data.*
import io.github.easylsp.app.platform.diagnostics.DiagnosticExport
import kotlinx.coroutines.flow.flowOf
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.UUID
import java.util.zip.ZipInputStream

class DiagnosticExportTest {
    @Test fun exportIncludesOnlySelectedEvidenceAndRedactsPrivatePaths() {
        val root = Files.createTempDirectory("diagnostic-export").toFile()
        val row = DiagnosticEntity(UUID.randomUUID().toString(), UUID.randomUUID().toString(), 1, "test.target", "IMPORTED", 1, 2, "COMPLETE")
        val dao = object : DiagnosticDao {
            override fun save(row: DiagnosticEntity) = Unit
            override fun observe(projectId: String) = flowOf(listOf(row))
            override fun list(projectId: String) = listOf(row)
            override fun get(projectId: String, id: String) = row.takeIf { it.projectId == projectId && it.id == id }
            override fun all() = listOf(row)
            override fun delete(id: String) = Unit
        }
        try {
            val store = DiagnosticStore(root, dao)
            val base = store.directory(row.projectId, row.id).apply { mkdirs() }
            File(base, "raw").mkdirs()
            File(base, "report.json").writeText(JSONObject().put("schemaVersion", 2).put("path", "/data/user/0/test.target/files/payload.so")
                .put("tombstones", JSONArray().put(JSONObject().put("source", "raw/tombstone_01"))).put("processes", JSONArray()).toString())
            File(base, "report.md").writeText("/data/data/test.target/files/payload.so")
            File(base, "raw/tombstone_01").writeText("/data/user/0/test.target/files/payload.so")
            File(base, "raw/logcat-window.txt").writeText("UNRELATED_APP_LOG")
            File(base, "raw/tombstone_01.pb").writeText("binary")
            File(base, "base.apk").writeText("APK")
            File(base, "events.jsonl").writeText("{\"path\":\"/data/user/0/test.target/files/payload.so\"}\n")
            fun export(raw: Boolean): Map<String, String> {
                val output = ByteArrayOutputStream()
                DiagnosticExport(store).write(row.projectId, row.id, raw, output)
                return ZipInputStream(output.toByteArray().inputStream()).use { zip -> buildMap {
                    while (true) { val entry = zip.nextEntry ?: break; put(entry.name, zip.readBytes().toString(Charsets.UTF_8)) }
                } }
            }
            val normal = export(false)
            assertFalse(normal.containsKey("raw/tombstone_01"))
            val raw = export(true)
            assertTrue(raw.containsKey("raw/tombstone_01"))
            assertFalse(raw.containsKey("raw/logcat-window.txt"))
            assertFalse(raw.containsKey("raw/tombstone_01.pb"))
            assertFalse(raw.containsKey("base.apk"))
            assertTrue(raw.values.none { it.contains("/data/user/0/test.target") || it.contains("/data/data/test.target") })
            assertEquals("<app-private:test.target>/files/payload.so", JSONObject(raw.getValue("report.json")).getString("path"))
        } finally { root.deleteRecursively() }
    }
}
