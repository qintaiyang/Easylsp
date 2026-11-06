package io.github.easylsp.app.platform.nativeplugins

import io.github.easylsp.app.EasylspApp
import io.github.easylsp.app.platform.root.RootCommand
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import java.util.UUID

/** Root operations use the existing manager-neutral su boundary. */
class NativeHostClient(private val graph: EasylspApp) {
    val state = MutableStateFlow(JSONObject().put("status", "NOT_QUERIED"))
    private val ctl = "/data/adb/modules/easylsp_native_host/bin/easylsp-hostctl"
    fun call(vararg arguments: String): JSONObject = runBlocking {
        check(graph.root.useRoot()) { "需要 root 授权才能管理 Native 宿主" }
        val output = StringBuilder()
        val command = (listOf(ctl) + arguments).joinToString(" ", transform = RootCommand::quote)
        val pendingCtl = "/data/adb/modules_update/easylsp_native_host/bin/easylsp-hostctl"
        val script = if (arguments.contentEquals(arrayOf("status"))) """
            if [ -x ${RootCommand.quote(pendingCtl)} ]; then
              printf '%s' '{"pendingHostUpdate":true,"host":'
              ${RootCommand.quote(pendingCtl)} status || exit ${'$'}?
              printf '%s' '}'
            elif [ -x ${RootCommand.quote(ctl)} ]; then
              $command
            else
              printf '%s' '{"status":"NOT_INSTALLED","runtimeStatus":"NOT_INSTALLED"}'
            fi
        """.trimIndent() else command
        val result = graph.executions.runner.execute(UUID.randomUUID().toString(), listOf(graph.root.su, "-c", script), graph.filesDir, closeInput = true, timeoutSeconds = 30) { output.append(it) }
        check(result.exitCode == 0) { "宿主操作失败：${output.toString().trim()}" }
        val raw = output.toString().trim()
        if (raw.startsWith("[")) JSONObject().put("items", JSONArray(raw)) else JSONObject(raw).let {
            if (it.optBoolean("pendingHostUpdate")) it.getJSONObject("host").put("pendingHostUpdate", true).put("runtimeStatus", "PENDING_REBOOT") else it
        }
    }
    fun refresh(): JSONObject = try { call("status").also { state.value = it } }
        catch (error: Exception) { JSONObject().put("status", "UNAVAILABLE").put("detail", error.message).also { state.value = it } }
    fun current(uid: Int, packageName: String): JSONObject? {
        val rows = call("status").getJSONArray("deployments")
        return (0 until rows.length()).map { rows.getJSONObject(it) }.firstOrNull { it.getInt("uid") == uid && it.getString("packageName") == packageName }
    }
    fun publish(snapshot: JSONObject): JSONObject {
        val directory = File(graph.cacheDir, "native-publish/${snapshot.getString("generation")}").apply { mkdirs() }
        try {
            val items = snapshot.getJSONArray("plugins")
            val ordered = JSONArray()
            for (i in 0 until items.length()) {
                val item = items.getJSONObject(i)
                val build = requireNotNull(graph.store.nativePlugins.build(item.getString("buildId")))
                val target = File(directory, "artifacts/${item.getString("pluginId")}/${build.buildId}/plugin.so")
                target.parentFile!!.mkdirs(); File(build.artifactPath).copyTo(target)
                ordered.put(JSONObject(item.toString()).put("localPluginId", item.getString("pluginId")).put("config", JSONObject(item.getString("config"))))
            }
            val wire = JSONObject(snapshot.toString()).put("backend", "ZYGISK").put("hostProtocolVersion", 1)
                .put("publishRequestId", snapshot.getString("generation")).put("userId", snapshot.getInt("uid") / 100000)
                .put("targetInstallIdentity", snapshot.getString("targetApkPath")).put("orderedPlugins", ordered)
            val input = File(directory, "snapshot.json").apply { writeText(wire.toString()) }
            return call("publish", input.path, File(directory, "artifacts").path)
        } finally { directory.deleteRecursively() }
    }
    fun diagnostic(uid: Int, packageName: String, settings: JSONObject) {
        val info = graph.targetApplications.info(packageName, uid / 100000)
        val run = settings.getString("diagnosticRunId")
        val snapshot = JSONObject().put("schema", 1).put("hostProtocolVersion", 1).put("backend", "ZYGISK")
            .put("generation", run).put("publishRequestId", run).put("uid", uid).put("userId", uid / 100000).put("packageName", packageName)
            .put("targetVersionCode", info.longVersionCode).put("targetInstallIdentity", info.applicationInfo!!.sourceDir)
            .put("policy", "NATIVE_ONLY").put("orderedPlugins", JSONArray()).put("diagnostic", settings)
        val file = File(graph.cacheDir, "native-diagnostic-$run.json").apply { writeText(snapshot.toString()) }
        try { call("diagnostic", file.path) } finally { file.delete() }
    }
    fun disable(uid: Int, packageName: String) = call("disable", uid.toString(), packageName)
    fun canonical(wire: JSONObject): JSONObject {
        val plugins = wire.getJSONArray("orderedPlugins")
        val items = JSONArray((0 until plugins.length()).map { i -> val p = plugins.getJSONObject(i)
            JSONObject(p.toString()).put("pluginId", p.getString("localPluginId")).put("config", p.getJSONObject("config").toString()) })
        return JSONObject(wire.toString()).put("plugins", items).put("targetApkPath", wire.getString("targetInstallIdentity"))
    }
}
