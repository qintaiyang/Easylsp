package io.github.easylsp.app.platform.root

import io.github.easylsp.app.EasylspApp
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class ManagerKeepaliveState(
    val enabled: Boolean = false, val running: Boolean = false, val keeperUid: Int? = null,
    val keeperPid: Int? = null, val managerPid: Int? = null, val oomScoreAdj: Int? = null, val cgroup: String? = null,
    val binderStatus: String? = null, val hansStatus: String? = null,
    val detail: String = "实际开发任务运行时按需保护"
)

/** Root-owned service survives app restarts; the application keeps its Android UID. */
class ManagerKeepalive(private val graph: EasylspApp) {
    private val lock = Mutex()
    val state = MutableStateFlow(ManagerKeepaliveState())
    private val service = "/data/adb/service.d/easylsp-manager-keepalive.sh"
    private val directory = "/data/adb/easylsp-manager-keepalive"

    private suspend fun root(script: String): String {
        check(graph.root.state.value.granted || graph.root.refresh().await().granted) { "UID0 保活需要 root 授权" }
        val output = StringBuilder()
        val result = graph.executions.runner.execute(UUID.randomUUID().toString(), listOf(graph.root.su, "-c", script), graph.filesDir, closeInput = true, timeoutSeconds = 30) { output.append(it) }
        check(result.exitCode == 0) { "UID0 保活操作失败：$output" }
        return output.toString().trim()
    }
    private suspend fun install() {
        val staging = File(graph.filesDir, "manager-keepalive").apply { mkdirs() }
        for (name in listOf("service.sh", "unfreeze-uid")) graph.assets.open("manager-keepalive/$name").use { input -> File(staging, name).outputStream().use { input.copyTo(it) } }
        root("""
            set -e
            if [ ! -f $directory/version ] || [ "${'$'}(cat $directory/version)" != 3 ]; then
                if [ -f $directory/pid ]; then
                    read -r old < $directory/pid
                    case "${'$'}old" in ''|*[!0-9]*) old=;; esac
                    if [ -n "${'$'}old" ] && [ -r /proc/${'$'}old/cmdline ]; then
                        case "${'$'}(tr '\000' ' ' < /proc/${'$'}old/cmdline)" in
                            *"$service run "*) sh $service stop;;
                            *"$service "*)
                                kill -TERM "${'$'}old"
                                for manager in ${'$'}(pidof io.github.easylsp); do echo 0 > /proc/${'$'}manager/oom_score_adj; done;;
                        esac
                    fi
                fi
                mkdir -p $directory /data/adb/service.d
                cp ${RootCommand.quote(File(staging, "service.sh").path)} $service
                cp ${RootCommand.quote(File(staging, "unfreeze-uid").path)} $directory/unfreeze-uid
                chmod 700 $service $directory/unfreeze-uid
                rm -f $directory/pid $directory/managers.json
                printf 3 > $directory/version
            fi
        """.trimIndent())
    }
    @Volatile private var protectingTask = false
    private val owners = mutableSetOf<String>()
    suspend fun taskActive(owner: String, active: Boolean) = lock.withLock {
        if (active) owners.add(owner) else owners.remove(owner)
        protectingTask = owners.isNotEmpty()
        if (graph.root.state.value.granted) apply(protectingTask)
    }
    suspend fun reconcile(): ManagerKeepaliveState = withContext(Dispatchers.IO) {
        lock.withLock { apply(protectingTask) }
    }
    private suspend fun apply(enabled: Boolean): ManagerKeepaliveState {
        try {
            install()
            root(if (enabled) "touch $directory/enabled && sh $service start" else "rm -f $directory/enabled && sh $service stop")
            return if (enabled) checkNotNull(withTimeoutOrNull(10000) {
                var value = readStatus(true)
                while (value.managerPid != android.os.Process.myPid() || value.oomScoreAdj != -1000 || value.cgroup != "/") {
                    delay(500)
                    value = readStatus(true)
                }
                value
            }) { "未能确认管理器受到 UID0 保活，请检查 root 授权与系统限制" } else readStatus(false)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            state.value = state.value.copy(detail = error.message ?: error.toString())
            throw error
        }
    }
    suspend fun refresh(): ManagerKeepaliveState = withContext(Dispatchers.IO) { lock.withLock { readStatus(protectingTask) } }
    private suspend fun readStatus(requested: Boolean): ManagerKeepaliveState {
        val json = JSONObject(root("if [ -f $directory/version ]; then sh $service status; else echo '{\"enabled\":false,\"running\":false,\"managers\":[]}'; fi"))
        val managers = json.getJSONArray("managers")
        val manager = (0 until managers.length()).map { managers.getJSONObject(it) }.find { it.getInt("pid") == android.os.Process.myPid() }
        val running = json.getBoolean("running")
        val keeperUid = if (running) json.getInt("keeperUid") else null
        val oom = manager?.getInt("oomScoreAdj")
        val unfreeze = manager?.getJSONObject("unfreeze")
        val binder = unfreeze?.getString("binder")
        val hans = unfreeze?.getString("hans")
        val detail = if (!requested) "没有后台开发任务，保活已关闭" else if (!running) "后台任务保活未运行" else if (manager == null) "后台任务保活已运行，等待管理器进程" else "UID$keeperUid 任务保活运行中 · 管理器 oom=$oom"
        val limitations = listOfNotNull(
            if (binder in setOf("unsupported", "unavailable")) "系统未提供 Binder 解冻接口" else if (binder == "error") "Binder 解冻失败" else null,
            if (hans == "error") "厂商网络解冻失败" else null
        )
        return ManagerKeepaliveState(enabled = requested, running = running, keeperUid = keeperUid,
            keeperPid = if (running) json.getInt("keeperPid") else null, managerPid = manager?.getInt("pid"),
            oomScoreAdj = oom, cgroup = manager?.getString("cgroup"), binderStatus = binder, hansStatus = hans,
            detail = (listOf(detail) + limitations).joinToString(" · ")).also { state.value = it }
    }
}
