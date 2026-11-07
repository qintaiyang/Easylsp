package io.github.easylsp.app.platform.ai

import io.github.easylsp.app.EasylspApp
import io.github.easylsp.app.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/** The same ownership and stop-before-delete contract serves lists and storage cleanup. */
class LocalManagementService(private val graph: EasylspApp) {
    private val serial = Mutex()
    private val dao get() = graph.store.workflows
    suspend fun deleteConversation(id: String) = withContext(Dispatchers.IO) { serial.withLock { graph.chat.deletingConversations {
        val stopped = linkedMapOf<String, ConversationEntity>()
        stopTree(id, stopped)
        stopped.values.forEach { removeConversation(it) }
    } } }
    private suspend fun stopTree(id: String, stopped: MutableMap<String, ConversationEntity>) {
        if (id in stopped) return
        val row = dao.conversation(id) ?: return
        // Closing the parent prevents its tools from admitting new owned children.
        graph.chat.quiesceConversation(id)
        if (row.projectId == null) {
            graph.executions.stopWorkspace(id)
            graph.terminals.closeWorkspace(id)
        }
        graph.executions.stopConversation(id)
        graph.terminals.closeExecutions(graph.executions.store.records.value.filter { it.conversationId == id }.map { it.id }.toSet())
        dao.allConversations().filter { it.parentConversationId == id ||
            (it.task == "__memory_consolidation__" && dao.harnessSession(it.id)?.sourceConversationId == id)
        }.forEach { stopTree(it.id, stopped) }
        stopped[id] = row
    }
    private suspend fun removeConversation(row: ConversationEntity) {
            val id = row.id
            graph.executions.store.clearConversation(id)
            val branches = dao.branchHistory(id)
            graph.store.transaction {
                fun clear(owned: String) {
                    dao.deleteMessages(owned); dao.deleteToolRuns(owned); dao.deleteTurns(owned)
                    dao.deleteInteractions(owned); dao.deleteInputs(owned); dao.clearPlan(owned)
                    dao.deleteDraft(owned); dao.deleteReading(owned); dao.deleteUsage(owned)
                }
                branches.forEach { clear(it.id) }
                clear(id); dao.deleteBranches(id); dao.detachForks(id); dao.detachSources(id); dao.removeConversation(id)
            }
            (branches.map { it.id } + id).forEach { owned ->
                File(graph.filesDir, "conversation-media/$owned").deleteRecursively()
                graph.toolOutputs.clearConversation(owned)
            }
            graph.memories.preferences.edit().remove("organized:$id").apply {
                if (graph.memories.preferences.getString("lastTask", null) == id) remove("lastTask")
            }.commit()
            val delivered = graph.getSharedPreferences("agent-notifications", 0)
            delivered.edit().apply { delivered.all.keys.filter { it.startsWith("agent-result-$id-") }.forEach { remove(it) } }.commit()
            listOf("conversation-capabilities", "pending-skill-guidance").forEach { graph.getSharedPreferences(it, 0).edit().remove(id).commit() }
            if (row.projectId == null) {
                graph.executions.store.clear(id)
                File(graph.filesDir, "workspaces/$id").deleteRecursively()
            }
    }
    suspend fun deleteProject(id: String) = withContext(Dispatchers.IO) { serial.withLock {
        if (java.io.File(graph.filesDir, "java-publications/" + id + ".json").exists()) graph.javaDeployments.publish(id, emptyList())
        val project = requireNotNull(graph.store.dao.project(id)) { "项目已删除" }
        val plugins = graph.store.dao.plugins(id)
        graph.chat.deletingConversations {
        val stopped = linkedMapOf<String, ConversationEntity>()
        dao.allConversations().filter { it.projectId == id }.forEach { stopTree(it.id, stopped) }
        graph.diagnostics.stopProject(id)
        graph.executions.stopWorkspace(id)
        graph.terminals.closeWorkspace(id)
        // A failed revocation leaves stopped conversations and the project manageable.
        if (project.kind == "INSTALLED") {
            if (plugins.any { it.runtime == "NATIVE" } || graph.store.nativePlugins.deployments(id).isNotEmpty())
                graph.nativeDeployments.revokeProject(id)
            graph.store.transaction { plugins.forEach { graph.store.dao.updatePlugin(it.copy(desiredEnabled = false)) } }
            for (plugin in plugins.filter { it.runtime == "BEANSHELL" }) {
                val sessions = graph.bridge.sessions.value.values.filter { it.uid / 100000 == project.userId && it.packageName == project.packageName && PluginProcesses.matches(plugin, project.packageName, it.processName) }
                for (session in sessions) graph.bridge.commandSession(session.id, "unloadPlugin", plugin.id)
            }
        }
        stopped.values.forEach { removeConversation(it) }
        graph.diagnostics.store.clear(id)
        graph.store.transaction {
            graph.store.nativePlugins.deleteProjectBuilds(id); graph.store.nativePlugins.deleteProjectDeployments(id)
            graph.store.dao.deleteSamples(id)
            dao.memories(id).filter { it.projectId == id }.forEach { dao.deleteMemory(it.id) }
            graph.store.dao.deleteProject(id)
        }
        dao.skills().filter { it.projectId == id }.forEach { skill ->
            if (skill.packagePath.isNotEmpty()) File(skill.packagePath).deleteRecursively()
            dao.deleteSkill(skill.id)
        }
        graph.models.clearProject(id)
        graph.nativeInstances.clearProject(id)
        plugins.forEach { File(graph.filesDir, "plugins/${it.id}").deleteRecursively() }
        graph.releasePreparations.deleteProject(id)
        File(graph.filesDir, "samples/$id").deleteRecursively()
        File(graph.filesDir, "workspaces/$id").deleteRecursively()
        graph.executions.store.clear(id)
        }
    } }
}
