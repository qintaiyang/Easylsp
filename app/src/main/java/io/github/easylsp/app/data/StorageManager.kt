package io.github.easylsp.app.data

import io.github.easylsp.app.EasylspApp
import org.json.*
import java.io.File

class StorageManager(private val graph: EasylspApp) {
    fun summary(): JSONObject {
        fun size(file: File): Long = if (file.isFile) file.length() else file.listFiles().orEmpty().sumOf(::size)
        val files = graph.filesDir
        return JSONObject().put("chatConversations", graph.store.workflows.allConversations().size).put("samples", size(File(files, "samples"))).put("editorDrafts", File(files, "plugins").listFiles().orEmpty().sumOf { File(it, "draft.json").length() }).put("viewerCache", size(File(files, "viewers"))).put("executions", size(File(files, "executions"))).put("temporaryCache", size(graph.cacheDir)).put("diagnostics", graph.store.diagnostics.all().sumOf { size(File(files, "workspaces/${it.projectId}/diagnostics/${it.id}")) }).put("linuxInstalled", graph.linux.installed)
    }
    fun clearGlobal(category: String): JSONObject {
        val rows = graph.store.workflows.allConversations().filter { it.projectId == null && !graph.chat.isRunning(it.id) }
        var removed = 0
        for (row in rows) when (category) {
            "executions" -> removed += graph.executions.store.clear(row.id)
            "chat" -> {
                graph.store.transaction {
                    val dao = graph.store.workflows
                    dao.deleteMessages(row.id); dao.deleteToolRuns(row.id); dao.deleteTurns(row.id); dao.deleteInteractions(row.id); dao.deleteInputs(row.id)
                    dao.clearPlan(row.id); dao.deleteDraft(row.id); dao.deleteReading(row.id); dao.deleteUsage(row.id); dao.deleteGlobalConversation(row.id)
                }
                File(graph.filesDir, "conversation-media/${row.id}").deleteRecursively()
                removed++
            }
            else -> error("全局清理支持 chat/executions")
        }
        return JSONObject().put("removed", removed).put("scope", "GLOBAL").put("category", category)
    }
    fun clear(projectId: String, category: String): JSONObject {
        var removed = 0; var kept = 0
        when (category) {
            "chat" -> {
                val rows = graph.store.workflows.allConversations().filter { it.projectId == projectId && !graph.chat.isRunning(it.id) }
                graph.store.transaction { rows.forEach { row -> graph.store.workflows.deleteMessages(row.id); graph.store.workflows.deleteToolRuns(row.id); graph.store.workflows.clearPlan(row.id); graph.store.workflows.deleteConversation(projectId, row.id) } }; removed = rows.size
            }
            "samples" -> {
                val references = (graph.store.workflows.allConversations().map { it.projectId to it.sampleRevision } + graph.store.diagnostics.all().map { it.projectId to it.sampleRevision } + graph.store.nativePlugins.projectBuilds(projectId).map { it.projectId to it.sampleRevision }).toSet()
                val project = requireNotNull(graph.store.dao.project(projectId))
                graph.store.dao.allSamples().filter { it.projectId == projectId }.forEach { sample ->
                    if (sample.revision == project.sampleRevision || (sample.projectId to sample.revision) in references) kept++
                    else { File(graph.filesDir, "samples/$projectId/${sample.revision}").deleteRecursively(); graph.store.dao.deleteSample(projectId, sample.revision); removed++ }
                }
            }
            "drafts" -> graph.store.dao.plugins(projectId).forEach { if (graph.store.draftFile(it.id).delete()) removed++ }
            "diagnostics" -> removed = graph.diagnostics.store.clear(projectId)
            "executions" -> removed = graph.executions.store.clear(projectId, graph.store.nativePlugins.projectBuilds(projectId).map { it.executionId }.toSet())
            "viewers" -> { val directory = File(graph.filesDir, "viewers"); removed = directory.listFiles().orEmpty().count { it.delete() } }
            else -> error("清理范围只能为 chat/samples/drafts/diagnostics/executions/viewers")
        }
        return JSONObject().put("removed", removed).put("keptReferencedOrCurrent", kept).put("category", category).put("projectId", projectId)
    }
}
