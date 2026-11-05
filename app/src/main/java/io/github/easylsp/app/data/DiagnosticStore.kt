package io.github.easylsp.app.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

@Entity(tableName = "diagnostics", indices = [Index("projectId")])
data class DiagnosticEntity(@PrimaryKey val id: String, val projectId: String, val sampleRevision: Long,
    val packageName: String, val mode: String, val startedAt: Long, val finishedAt: Long? = null,
    val state: String = "PREPARING", val executionId: String? = null)

@Dao interface DiagnosticDao {
    @Upsert fun save(row: DiagnosticEntity)
    @Query("SELECT * FROM diagnostics WHERE projectId = :projectId ORDER BY startedAt DESC") fun observe(projectId: String): Flow<List<DiagnosticEntity>>
    @Query("SELECT * FROM diagnostics WHERE projectId = :projectId ORDER BY startedAt DESC") fun list(projectId: String): List<DiagnosticEntity>
    @Query("SELECT * FROM diagnostics WHERE id = :id AND projectId = :projectId") fun get(projectId: String, id: String): DiagnosticEntity?
    @Query("SELECT * FROM diagnostics") fun all(): List<DiagnosticEntity>
    @Query("DELETE FROM diagnostics WHERE id = :id") fun delete(id: String)
}

/** Room holds the index; evidence remains readable independently of a target session. */
class DiagnosticStore(private val files: File, val dao: DiagnosticDao) {
    fun directory(projectId: String, id: String): File {
        UUID.fromString(projectId); UUID.fromString(id)
        return File(files, "workspaces/$projectId/diagnostics/$id")
    }
    fun requireRun(projectId: String, id: String) = requireNotNull(dao.get(projectId, id)) { "诊断不属于当前项目或已删除" }
    @Synchronized fun read(projectId: String, id: String): JSONObject {
        val row = requireRun(projectId, id)
        val report = JSONObject(File(directory(projectId, id), "report.json").readText())
        if (report.getInt("schemaVersion") == 1) {
            val tombstones = report.getJSONArray("tombstones")
            for (i in 0 until tombstones.length()) {
                val old = tombstones.getJSONObject(i)
                val source = old.getString("source")
                val corrected = requireNotNull(io.github.easylsp.app.platform.diagnostics.DiagnosticEvidence.tombstone(File(directory(projectId, id), source).readText(), source))
                for (key in listOf("protobuf", "protobufUnavailable")) if (old.has(key)) corrected.put(key, old.get(key))
                tombstones.put(i, corrected)
            }
            report.put("schemaVersion", 2)
            write(row, report)
        }
        return report
    }
    @Synchronized fun write(row: DiagnosticEntity, report: JSONObject) {
        val dir = directory(row.projectId, row.id).apply { mkdirs() }
        report.put("state", row.state).put("executionId", row.executionId ?: JSONObject.NULL)
            .put("finishedAt", row.finishedAt ?: JSONObject.NULL)
        val temp = File(dir, "report.json.tmp").apply { writeText(report.toString(2)) }
        Files.move(temp.toPath(), File(dir, "report.json").toPath(), StandardCopyOption.REPLACE_EXISTING)
        File(dir, "report.md").writeText(io.github.easylsp.app.platform.diagnostics.DiagnosticEvidence.markdown(report))
        dao.save(row)
    }
    @Synchronized fun event(row: DiagnosticEntity, event: JSONObject) {
        File(directory(row.projectId, row.id), "events.jsonl").appendText(event.toString() + "\n")
    }
    fun clear(projectId: String): Int = dao.list(projectId).filter { it.finishedAt != null }.count {
        directory(projectId, it.id).deleteRecursively(); dao.delete(it.id); true
    }
}
