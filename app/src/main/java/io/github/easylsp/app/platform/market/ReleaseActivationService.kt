package io.github.easylsp.app.platform.market

import io.github.easylsp.app.EasylspApp
import io.github.easylsp.app.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Shared UI/tool boundary for fixed release components and dependency activation. */
class ReleaseActivationService(private val graph: EasylspApp) {
    suspend fun enable(plugin: PluginEntity): JSONObject = withContext(Dispatchers.IO) {
        val ordered=ReleaseDependencies.installedOrder(plugin,graph.store.dao.plugins(plugin.projectId))
        val project=requireNotNull(graph.store.dao.project(plugin.projectId))
        require(project.userId == android.os.Process.myUid()/100000 || ordered.none { it.runtime=="BEANSHELL" }) { "跨用户Java组件需要该用户的管理器与框架" }
        if (ordered.any { it.runtime=="NATIVE" }) graph.nativeDeployments.enableReleaseSet(plugin.projectId,ordered)
        else {
            graph.store.transaction { ordered.forEach { graph.store.dao.updatePlugin(it.copy(desiredEnabled=true)) } }
            JSONObject().put("status","ENABLED_PENDING_RESTART")
        }
    }
    suspend fun disable(plugin: PluginEntity, delete: Boolean = false): JSONObject = withContext(Dispatchers.IO) {
        val installed=graph.store.dao.plugins(plugin.projectId)
        val rows=ReleaseDependencies.components(plugin,installed)
        ReleaseDependencies.requireCanDisableSet(rows,installed)
        val project=requireNotNull(graph.store.dao.project(plugin.projectId))
        if (project.kind == "INSTALLED" && rows.any { it.runtime=="NATIVE" }) graph.nativeDeployments.disableReleaseSet(plugin)
        else graph.store.transaction { rows.forEach { graph.store.dao.updatePlugin(it.copy(desiredEnabled=false)) } }
        for (row in rows.filter { it.runtime=="BEANSHELL" }) {
            val sessions=graph.bridge.sessions.value.values.filter { it.uid/100000==project.userId && it.packageName==project.packageName && PluginProcesses.matches(row,project.packageName,it.processName) }
            sessions.forEach { graph.bridge.commandSession(it.id,"unloadPlugin",row.id) }
        }
        if (delete) graph.store.deleteRelease(plugin.id)
        JSONObject().put("status",if (delete) "DELETED_PENDING_RESTART" else "DISABLED_PENDING_RESTART")
    }
}
