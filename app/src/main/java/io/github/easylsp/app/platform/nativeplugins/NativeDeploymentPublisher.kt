package io.github.easylsp.app.platform.nativeplugins

import io.github.easylsp.app.EasylspApp
import io.github.easylsp.app.data.NativeDeploymentEntity
import io.github.easylsp.app.data.PluginEntity
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Single publishing boundary shared by tools and UI. The next process reads a complete snapshot. */
class NativeDeploymentPublisher(private val graph: EasylspApp) {
    @Synchronized fun enable(plugin: PluginEntity, buildId: String, policy: String): JSONObject {
        require(plugin.runtime == "NATIVE")
        require(policy in setOf("NATIVE_ONLY", "NATIVE_AND_JAVA")) { "发布时必须明确选择 NATIVE_ONLY 或 NATIVE_AND_JAVA" }
        require(plugin.nativeProcessType == "app" || policy == "NATIVE_ONLY") { "特殊进程仅发布Native-only" }
        val closure = io.github.easylsp.app.data.ReleaseDependencies.installedOrder(plugin, graph.store.dao.plugins(plugin.projectId))
        require(closure.all { it.id == plugin.id || it.desiredEnabled }) { "请使用启用固定发行与依赖" }
        val build = requireNotNull(graph.store.nativePlugins.build(buildId)) { "构建不存在" }
        require(build.pluginId == plugin.id && build.projectId == plugin.projectId && build.state == "SUCCEEDED") { "构建必须属于此插件并已成功接收" }
        require(build.minApi <= android.os.Build.VERSION.SDK_INT) { "此构建要求 Android API ${build.minApi}，当前为 ${android.os.Build.VERSION.SDK_INT}" }
        check(graph.store.nativePlugins.deployments(plugin.projectId).none { it.state in setOf("PREPARED", "OLD_DISABLED") }) { "请先恢复未完成的后端迁移" }
        val snapshot = snapshot(plugin.projectId, policy)
        val items = snapshot.getJSONArray("plugins")
        val retained = JSONArray((0 until items.length()).map { items.getJSONObject(it) }.filter { it.getString("pluginId") != plugin.id })
        val remote = "native-${UUID.randomUUID()}.so"
        val previousIndex = (0 until items.length()).firstOrNull { items.getJSONObject(it).getString("pluginId") == plugin.id }
        val replacement = JSONObject().put("pluginId", plugin.id).put("buildId", buildId).put("sourceRevision", build.sourceRevision)
            .put("remoteFile", remote).put("processType", plugin.nativeProcessType).put("processScope", plugin.processScope).put("processName", plugin.processName.ifEmpty { snapshot.getString("packageName") }).put("config", plugin.configJson).put("releaseId", build.releaseId ?: JSONObject.NULL)
        if (previousIndex == null) retained.put(replacement) else {
            val ordered = (0 until items.length()).map { if (it == previousIndex) replacement else items.getJSONObject(it) }
            while (retained.length() > 0) retained.remove(0)
            ordered.forEach { retained.put(it) }
        }
        require(retained.length() <= 16) { "首版每进程最多 16 个 native 插件" }
        snapshot.put("plugins", retained)
        return commit(plugin.projectId, snapshot, remote to File(build.artifactPath))
    }
    @Synchronized fun enableReleaseSet(projectId: String, plugins: List<PluginEntity>): JSONObject {
        check(graph.store.nativePlugins.deployments(projectId).none { it.state in setOf("PREPARED", "OLD_DISABLED") }) { "请先恢复未完成的后端迁移" }
        val natives = plugins.filter { it.runtime == "NATIVE" }
        val hasJava = plugins.any { it.runtime == "BEANSHELL" }
        val project = requireNotNull(graph.store.dao.project(projectId))
        require(project.userId == android.os.Process.myUid() / 100000 || !hasJava) { "跨用户联合Java发行需要在对应用户安装管理器与框架" }
        require(natives.none { it.nativeProcessType != "app" } || !hasJava) { "特殊进程仅允许Native-only" }
        val value = snapshot(projectId, if (hasJava) "NATIVE_AND_JAVA" else if (natives.any { it.nativeProcessType != "app" }) "NATIVE_ONLY" else project.nativePolicy)
        val old = value.getJSONArray("plugins")
        val nativeIds = natives.map { it.id }.toSet()
        val ordered = JSONArray()
        // Dependencies precede dependents. Retain unrelated already published plugins.
        for (plugin in natives) {
            val build = requireNotNull(graph.store.nativePlugins.builds(plugin.id).find { it.state == "SUCCEEDED" && it.releaseId == plugin.releaseId && it.sourceRevision == plugin.revision }) { "发行缺少固定预编译构建：${plugin.name}" }
            require(build.minApi <= android.os.Build.VERSION.SDK_INT)
            ordered.put(JSONObject().put("pluginId", plugin.id).put("buildId", build.buildId).put("sourceRevision", build.sourceRevision)
                .put("remoteFile", "native-${UUID.randomUUID()}.so").put("processType", plugin.nativeProcessType).put("processScope", plugin.processScope)
                .put("processName", plugin.processName.ifEmpty { value.getString("packageName") }).put("config", plugin.configJson).put("releaseId", plugin.releaseId))
            if (project.nativeBackend == "LIBXPOSED") graph.framework.writeNativeArtifact(ordered.getJSONObject(ordered.length() - 1).getString("remoteFile"), File(build.artifactPath))
        }
        for (i in 0 until old.length()) if (old.getJSONObject(i).getString("pluginId") !in nativeIds) ordered.put(old.getJSONObject(i))
        require(ordered.length() <= 16) { "每进程最多16个Native插件" }
        value.put("plugins", ordered)
        value.put("javaPlugins", JSONArray((graph.store.dao.plugins(projectId).filter { it.runtime == "BEANSHELL" && it.desiredEnabled } + plugins.filter { it.runtime == "BEANSHELL" }).map { it.id }.distinct()))
        return commit(projectId, value, null)
    }
    @Synchronized fun disable(plugin: PluginEntity): JSONObject {
        require(plugin.runtime == "NATIVE")
        io.github.easylsp.app.data.ReleaseDependencies.requireCanDisable(plugin, graph.store.dao.plugins(plugin.projectId))
        val published = graph.store.nativePlugins.published(plugin.projectId)
        val snapshot = snapshot(plugin.projectId, published?.policy ?: "JAVA_ONLY", allowChangedTarget = true)
        val items = snapshot.getJSONArray("plugins")
        val retained = JSONArray((0 until items.length()).map { items.getJSONObject(it) }.filter { it.getString("pluginId") != plugin.id })
        snapshot.put("plugins", retained)
        if (retained.length() == 0) {
            snapshot.put("policy", "JAVA_ONLY")
        }
        return commit(plugin.projectId, snapshot, null)
    }
    @Synchronized fun disableReleaseSet(plugin: PluginEntity): JSONObject {
        val installed = graph.store.dao.plugins(plugin.projectId)
        val rows = io.github.easylsp.app.data.ReleaseDependencies.components(plugin, installed)
        io.github.easylsp.app.data.ReleaseDependencies.requireCanDisableSet(rows, installed)
        val ids = rows.map { it.id }.toSet()
        val value = snapshot(plugin.projectId, graph.store.nativePlugins.published(plugin.projectId)?.policy ?: "JAVA_ONLY", allowChangedTarget = true)
        val old = value.getJSONArray("plugins")
        val retained = JSONArray((0 until old.length()).map { old.getJSONObject(it) }.filter { it.getString("pluginId") !in ids })
        value.put("plugins", retained)
        if (retained.length() == 0) value.put("policy", "JAVA_ONLY")
        value.put("javaPlugins", JSONArray(graph.store.dao.plugins(plugin.projectId).filter { it.runtime == "BEANSHELL" && it.desiredEnabled && it.id !in ids }.map { it.id }))
        return commit(plugin.projectId, value, null)
    }
    @Synchronized fun disableProject(projectId: String): JSONObject {
        val snapshot = snapshot(projectId, "JAVA_ONLY", allowChangedTarget = true)
        snapshot.put("plugins", JSONArray()).put("policy", "JAVA_ONLY")
        return commit(projectId, snapshot, null)
    }
    /** Deletion also revokes a backend reached by an interrupted migration. */
    @Synchronized fun revokeProject(projectId: String) {
        val project = requireNotNull(graph.store.dao.project(projectId))
        val deployments = graph.store.nativePlugins.deployments(projectId)
        val pending = deployments.filter { it.state in setOf("PREPARED", "OLD_DISABLED") }.map { JSONObject(it.snapshot) }
        val backends = (listOf(project.nativeBackend) + pending.flatMap { listOf(it.getString("migrationFrom"), it.getString("backend")) }).toSet()
        val known = deployments.firstOrNull()
        val app = try { requireNotNull(graph.targetApplications.info(project.packageName, project.userId).applicationInfo) }
            catch (_: android.content.pm.PackageManager.NameNotFoundException) { null }
        if (app == null && known == null) return // An uninstalled target with no publication has nothing to revoke.
        val uid = app?.uid ?: known!!.uid
        if ("ZYGISK" in backends) graph.nativeHost.disable(uid, project.packageName)
        if ("LIBXPOSED" in backends || graph.framework.state.value.active) {
            val empty = JSONObject().put("schema", 1).put("backend", project.nativeBackend).put("generation", UUID.randomUUID().toString())
                .put("uid", uid).put("packageName", project.packageName)
                .put("targetApkPath", app?.sourceDir ?: JSONObject(known!!.snapshot).getString("targetApkPath"))
                .put("policy", "JAVA_ONLY").put("plugins", JSONArray())
            graph.framework.publishNativeSnapshot("$uid:${project.packageName}", empty)
        }
    }

    private fun snapshot(projectId: String, policy: String, allowChangedTarget: Boolean = false): JSONObject {
        val project = requireNotNull(graph.store.dao.project(projectId))
        require(project.kind == "INSTALLED") { "导入 APK 项目可构建，发布需要已安装目标" }
        require(project.userId == android.os.Process.myUid() / 100000 || project.nativeBackend == "ZYGISK" && policy != "NATIVE_AND_JAVA") { "跨用户发布使用Zygisk Native-only" }
        val info = try { graph.targetApplications.info(project.packageName, project.userId) }
            catch (error: android.content.pm.PackageManager.NameNotFoundException) { if (!allowChangedTarget) throw error else null }
        val known = graph.store.nativePlugins.published(projectId)
        val app = info?.applicationInfo
        val uid = app?.uid ?: requireNotNull(known) { "目标已移除且没有发布记录" }.uid
        val current = if (project.nativeBackend == "ZYGISK") graph.nativeHost.current(uid, project.packageName)?.let(graph.nativeHost::canonical)
            else graph.framework.nativeSnapshot("$uid:${project.packageName}")?.let(::JSONObject)
        val apkPath = app?.sourceDir ?: JSONObject(requireNotNull(known).snapshot).getString("targetApkPath")
        val version = info?.longVersionCode ?: requireNotNull(known).targetVersion
        if (!allowChangedTarget && current != null && current.getJSONArray("plugins").length() > 0)
            require(current.getString("targetApkPath") == apkPath) { "目标已升级，请先停用旧发布再按新样本重新发布" }
        return JSONObject().put("schema", 1).put("backend", project.nativeBackend).put("generation", UUID.randomUUID().toString()).put("uid", uid)
            .put("packageName", project.packageName).put("targetApkPath", if (allowChangedTarget && current != null) current.getString("targetApkPath") else apkPath)
            .put("targetVersionCode", if (allowChangedTarget && current != null) current.optLong("targetVersionCode", version) else version)
            .put("policy", policy).put("plugins", current?.getJSONArray("plugins") ?: JSONArray())
            .put("javaPlugins", JSONArray(graph.store.dao.plugins(projectId).filter { it.runtime == "BEANSHELL" && it.desiredEnabled }.map { it.id }))
    }
    private fun commit(projectId: String, snapshot: JSONObject, artifact: Pair<String, File>?): JSONObject {
        val projectSettings = requireNotNull(graph.store.dao.project(projectId))
        val pluginItems = snapshot.getJSONArray("plugins")
        require((0 until pluginItems.length()).all { pluginItems.getJSONObject(it).optString("processType", "app") == "app" || projectSettings.nativeBackend == "ZYGISK" && snapshot.getString("policy") == "NATIVE_ONLY" }) { "特殊进程使用Zygisk Native-only发布" }
        val row = NativeDeploymentEntity(snapshot.getString("generation"), projectId, snapshot.getInt("uid"), snapshot.getString("packageName"),
            snapshot.getLong("targetVersionCode"), snapshot.getString("policy"), snapshot.toString(), "PUBLISHED")
        var step = "WRITE_ARTIFACT"
        try {
            if (snapshot.getString("backend") == "ZYGISK") {
                step = "COMMIT_ROOT_SNAPSHOT"
                if (snapshot.getJSONArray("plugins").length() == 0) graph.nativeHost.disable(row.uid, row.packageName)
                else graph.nativeHost.publish(snapshot)
                if (graph.framework.state.value.active) graph.framework.publishNativeSnapshot("${row.uid}:${row.packageName}", snapshot)
            } else {
                artifact?.let { graph.framework.writeNativeArtifact(it.first, it.second) }
                step = "COMMIT_SNAPSHOT"
                graph.framework.publishNativeSnapshot("${row.uid}:${row.packageName}", snapshot)
            }
        } catch (error: Exception) {
            graph.store.nativePlugins.saveDeployment(row.copy(state = "FAILED", failedStep = step, error = error.toString()))
            throw IllegalStateException("native 发布失败：$step；${error.message}", error)
        }
        graph.store.transaction {
            graph.store.nativePlugins.saveDeployment(row)
            val project = requireNotNull(graph.store.dao.project(projectId))
            graph.store.dao.updateProject(project.copy(nativePolicy = snapshot.getString("policy").takeIf { it != "JAVA_ONLY" } ?: project.nativePolicy))
            val items = snapshot.getJSONArray("plugins")
            val enabled = (0 until items.length()).map { items.getJSONObject(it).getString("pluginId") }.toSet()
            graph.store.dao.plugins(projectId).filter { it.runtime == "NATIVE" }.forEach { graph.store.dao.updatePlugin(it.copy(desiredEnabled = it.id in enabled)) }
            snapshot.optJSONArray("javaPlugins")?.let { java ->
                val javaIds = (0 until java.length()).map { java.getString(it) }.toSet()
                graph.store.dao.plugins(projectId).filter { it.runtime == "BEANSHELL" }.forEach { graph.store.dao.updatePlugin(it.copy(desiredEnabled = it.id in javaIds)) }
            }
        }
        return snapshot.put("status", "PUBLISHED_PENDING_RESTART").put("note", "下次目标新进程生效；当前进程的 SO 和 Hook 保留到退出")
    }
    @Synchronized fun switchBackend(projectId: String, backend: String, policy: String): JSONObject {
        require(projectId.isNotBlank())
        require(backend in setOf("LIBXPOSED", "ZYGISK") && policy in setOf("NATIVE_ONLY", "NATIVE_AND_JAVA"))
        val project = requireNotNull(graph.store.dao.project(projectId))
        require(project.kind == "INSTALLED")
        require(project.userId == android.os.Process.myUid() / 100000 || backend == "ZYGISK" && policy == "NATIVE_ONLY") { "其他用户使用Zygisk Native-only发布" }
        if (backend == project.nativeBackend) {
            graph.store.dao.updateProject(project.copy(nativePolicy = policy))
            return JSONObject().put("status", "SETTINGS_SAVED").put("note", "请显式发布固定版本使策略生效")
        }
        check(graph.store.nativePlugins.deployments(projectId).none { it.state in setOf("PREPARED", "OLD_DISABLED") }) { "已有待恢复迁移" }
        val latest = graph.store.nativePlugins.published(projectId)
        val info = graph.targetApplications.info(project.packageName, project.userId)
        val app = requireNotNull(info.applicationInfo)
        val value = JSONObject(latest?.snapshot ?: "{}").put("schema", 1).put("generation", UUID.randomUUID().toString())
            .put("backend", backend).put("migrationFrom", project.nativeBackend).put("uid", app.uid)
            .put("packageName", project.packageName).put("targetApkPath", app.sourceDir).put("targetVersionCode", info.longVersionCode)
            .put("policy", policy)
        if (!value.has("plugins")) value.put("plugins", JSONArray())
        val row = NativeDeploymentEntity(value.getString("generation"), projectId, app.uid, project.packageName,
            info.longVersionCode, policy, value.toString(), "PREPARED")
        graph.store.nativePlugins.saveDeployment(row)
        return finishMigration(row)
    }
    @Synchronized fun resumeMigration(projectId: String): JSONObject {
        val row = requireNotNull(graph.store.nativePlugins.deployments(projectId).firstOrNull { it.state in setOf("PREPARED", "OLD_DISABLED") }) { "没有待恢复迁移" }
        return finishMigration(row)
    }
    private fun finishMigration(row: NativeDeploymentEntity): JSONObject {
        val snapshot = JSONObject(row.snapshot)
        val project = requireNotNull(graph.store.dao.project(row.projectId))
        val key = "${row.uid}:${row.packageName}"
        val backend = snapshot.getString("backend")
        if (row.state == "PREPARED") {
            if (snapshot.getString("migrationFrom") == "ZYGISK") graph.nativeHost.disable(row.uid, row.packageName)
            else if (graph.framework.state.value.active) graph.framework.publishNativeSnapshot(key,
                JSONObject(snapshot.toString()).put("plugins", JSONArray()).put("backend", backend))
            else check(graph.store.nativePlugins.published(row.projectId) == null) { "撤销旧 Native 发布需要连接 libxposed 服务" }
            graph.store.nativePlugins.saveDeployment(row.copy(state = "OLD_DISABLED"))
        }
        if (backend == "ZYGISK") {
            if (snapshot.getJSONArray("plugins").length() > 0) graph.nativeHost.publish(snapshot) else graph.nativeHost.call("status")
        } else {
            val items = snapshot.getJSONArray("plugins")
            for (i in 0 until items.length()) {
                val plugin = items.getJSONObject(i)
                val build = requireNotNull(graph.store.nativePlugins.build(plugin.getString("buildId")))
                val remote = "native-${UUID.randomUUID()}.so"
                graph.framework.writeNativeArtifact(remote, File(build.artifactPath)); plugin.put("remoteFile", remote)
            }
            graph.framework.publishNativeSnapshot(key, snapshot)
        }
        graph.store.transaction {
            graph.store.dao.updateProject(project.copy(nativeBackend = backend, nativePolicy = row.policy))
            graph.store.nativePlugins.saveDeployment(row.copy(snapshot = snapshot.toString(), state = "PUBLISHED"))
        }
        return snapshot.put("status", "NEW_COMMITTED_PENDING_RESTART")
    }
    @Synchronized fun reorder(projectId: String, pluginIds: List<String>): JSONObject {
        val project = requireNotNull(graph.store.dao.project(projectId))
        val value = snapshot(projectId, project.nativePolicy)
        val items = value.getJSONArray("plugins")
        val byId = (0 until items.length()).map { items.getJSONObject(it) }.associateBy { it.getString("pluginId") }
        require(pluginIds.size == byId.size && pluginIds.toSet() == byId.keys) { "顺序必须包含全部已发布插件且不重复" }
        value.put("plugins", JSONArray(pluginIds.map { byId.getValue(it) }))
        return commit(projectId, value, null)
    }
    fun reconcile(projectId: String): JSONObject {
        val project = requireNotNull(graph.store.dao.project(projectId))
        require(project.nativeBackend == "ZYGISK")
        val app = requireNotNull(graph.targetApplications.info(project.packageName, project.userId).applicationInfo)
        val current = graph.nativeHost.current(app.uid, project.packageName)
            ?: return JSONObject().put("status", "NO_ROOT_DEPLOYMENT")
        val value = graph.nativeHost.canonical(current)
        graph.store.transaction {
            graph.store.nativePlugins.saveDeployment(NativeDeploymentEntity(value.getString("generation"), projectId, app.uid,
                project.packageName, value.getLong("targetVersionCode"), value.getString("policy"), value.toString(), "PUBLISHED"))
            val items = value.getJSONArray("plugins")
            val enabled = (0 until items.length()).map { items.getJSONObject(it).getString("pluginId") }.toSet()
            graph.store.dao.plugins(projectId).filter { it.runtime == "NATIVE" }.forEach { graph.store.dao.updatePlugin(it.copy(desiredEnabled = it.id in enabled)) }
            value.optJSONArray("javaPlugins")?.let { java ->
                val javaIds = (0 until java.length()).map { java.getString(it) }.toSet()
                graph.store.dao.plugins(projectId).filter { it.runtime == "BEANSHELL" }.forEach { graph.store.dao.updatePlugin(it.copy(desiredEnabled = it.id in javaIds)) }
            }
            graph.store.dao.updateProject(project.copy(nativePolicy = value.getString("policy")))
        }
        return value.put("status", "RECONCILED")
    }
    fun publishPrototype(projectId: String, buildId: String, policy: String = "NATIVE_ONLY"): JSONObject {
        val project = requireNotNull(graph.store.dao.project(projectId))
        require(project.packageName == "io.github.easylsp.demo") { "P0 只发布受控 Demo；其他目标等待早期链路验收" }
        require(policy in setOf("NATIVE_ONLY", "NATIVE_AND_JAVA"))
        val directory = graph.nativeBuilds.directory(projectId, buildId)
        val record = JSONObject(File(directory, "build.json").readText())
        require(record.getString("state") == "SUCCEEDED") { "只能发布已通过产物接收的固定 buildId" }
        require(record.getInt("minApi") <= android.os.Build.VERSION.SDK_INT) { "此构建要求 Android API ${record.getInt("minApi")}" }
        val info = graph.targetApplications.info(project.packageName, project.userId)
        val app = requireNotNull(info.applicationInfo)
        val remote = "native-${UUID.randomUUID()}.so"
        val snapshot = JSONObject().put("schema", 1).put("generation", UUID.randomUUID().toString())
            .put("uid", app.uid).put("packageName", project.packageName).put("targetApkPath", app.sourceDir)
            .put("targetVersionCode", info.longVersionCode).put("policy", policy)
            .put("plugins", JSONArray().put(JSONObject().put("pluginId", record.getString("pluginId"))
                .put("buildId", buildId).put("sourceRevision", record.getLong("sourceRevision"))
                .put("remoteFile", remote).put("processScope", "main").put("processName", project.packageName).put("config", "{}")))
        var step = "WRITE_ARTIFACT"
        try {
            graph.framework.writeNativeArtifact(remote, File(directory, "plugin.so"))
            step = "COMMIT_SNAPSHOT"
            graph.framework.publishNativeSnapshot("${app.uid}:${project.packageName}", snapshot)
            File(directory, "publication-${snapshot.getString("generation")}.json").writeText(snapshot.toString(2))
        } catch (error: Exception) {
            throw IllegalStateException("native 发布失败：$step；${error.message}", error)
        }
        return snapshot.put("status", "PUBLISHED_PENDING_RESTART")
    }
    fun disablePrototype(projectId: String): JSONObject {
        val project = requireNotNull(graph.store.dao.project(projectId))
        require(project.packageName == "io.github.easylsp.demo")
        val app = requireNotNull(graph.targetApplications.info(project.packageName, project.userId).applicationInfo)
        val snapshot = JSONObject().put("schema", 1).put("generation", UUID.randomUUID().toString())
            .put("uid", app.uid).put("packageName", project.packageName).put("targetApkPath", app.sourceDir)
            .put("policy", "JAVA_ONLY").put("plugins", JSONArray())
        graph.framework.publishNativeSnapshot("${app.uid}:${project.packageName}", snapshot)
        return snapshot.put("status", "DISABLED_PENDING_RESTART")
    }
}
