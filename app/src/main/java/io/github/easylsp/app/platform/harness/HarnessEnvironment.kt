package io.github.easylsp.app.platform.harness

import io.github.easylsp.app.EasylspApp
import io.github.easylsp.app.platform.network.HttpCalls
import io.github.easylsp.app.platform.root.RootCommand
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Explicit install and host lifecycle, separate from ordinary prompt submission. */
class HarnessEnvironment(private val graph: EasylspApp) {
    private val directory = File(graph.filesDir, "harness").apply { mkdirs() }
    private val marker = File(directory, "installed.json")
    private val hostFile = File(directory, "host.json")
    private val lifecycle = Mutex()
    val installed get() = marker.exists()
    val status = MutableStateFlow(if (installed) "Harness 已安装" else "尚未安装 Harness")
    val serviceStatus = MutableStateFlow("执行服务未启动")
    val busy = MutableStateFlow(false)
    private val tokenKey = "harness-host"
    private fun q(value: String) = RootCommand.quote(value)

    suspend fun install(): Unit = lifecycle.withLock { withContext(Dispatchers.IO) {
        require(graph.linux.installed) { "请先安装 Ubuntu" }
        require(!hostFile.exists()) { "请先停止会话宿主再安装" }
        busy.value = true
        val archive = File(directory, "node-v24.11.0-linux-arm64.tar.gz")
        try {
            status.value = "下载 Node 24.11.0…"
            val client = OkHttpClient.Builder().retryOnConnectionFailure(false).callTimeout(10, TimeUnit.MINUTES).build()
            HttpCalls.execute(client, Request.Builder().url("https://nodejs.org/dist/v24.11.0/node-v24.11.0-linux-arm64.tar.gz").build()) { response ->
                requireNotNull(response.body).byteStream().use { input -> archive.outputStream().use { input.copyTo(it) } }
            }
            val assets = extractAssets()
            status.value = "安装固定 Node 与 Harness…"
            run("""
                printf '127.0.0.1 localhost\n::1 localhost\n' > /etc/hosts
                export DEBIAN_FRONTEND=noninteractive
                apt-get update
                apt-get install -y ca-certificates libstdc++6
                mkdir -p /opt/easylsp-node-v24.11.0 /opt/easylsp-harness
                tar -xzf ${q(archive.path)} -C /opt/easylsp-node-v24.11.0 --strip-components=1
                cp ${q(assets.path)}/* /opt/easylsp-harness/
                cd /opt/easylsp-harness
                npm ci --omit=dev --no-audit --no-fund
                node --input-type=module -e 'import {readFileSync} from "node:fs"; if (process.version !== "v24.11.0" || JSON.parse(readFileSync("node_modules/@deepseek-ai/dsh/package.json")).version !== "0.2.2-alpha.3" || JSON.parse(readFileSync("node_modules/ws/package.json")).version !== "8.22.1") process.exit(1)'
            """.trimIndent(), appFiles = true, timeout = 1800)
            marker.writeText(JSONObject().put("node", "24.11.0").put("harness", "0.2.2-alpha.3").put("ws", "8.22.1").put("installedAt", System.currentTimeMillis()).toString())
            status.value = "Harness 0.2.2-alpha.3 已安装 · Node 24.11.0"
        } catch (error: Exception) { status.value = "Harness 安装未完成：${error.message}"; throw error }
        finally { archive.delete(); busy.value = false }
    } }
    suspend fun start(): JSONObject = lifecycle.withLock { withContext(Dispatchers.IO) {
        require(installed) { "请先安装 Harness" }
        if (hostFile.exists()) {
            if (hostAlive()) return@withContext probe()
            hostFile.delete(); graph.secrets.delete(tokenKey)
        }
        status.value = "正在启动会话宿主…"
        val assets = extractAssets()
        val token = UUID.randomUUID().toString()
        graph.secrets.put(tokenKey, token)
        val result = run("cp ${q(assets.path)}/*.mjs /opt/easylsp-harness/; exec node /opt/easylsp-harness/boot.mjs", appFiles = true, input = JSONObject()
            .put("token", token).put("directory", "/var/lib/easylsp-harness").toString() + "\n")
        val endpoint = JSONObject(result.trim())
        hostFile.writeText(endpoint.toString())
        probe()
    } }
    suspend fun connect(): HarnessConnection = withContext(Dispatchers.IO) {
        require(hostFile.exists()) { "会话宿主未启动" }
        HarnessConnection.connect(JSONObject(hostFile.readText()).getInt("port"), graph.secrets.get(tokenKey))
    }
    private suspend fun probe(): JSONObject = connect().use { connection ->
        connection.request("host/status").also { status.value = "会话宿主：${it.getString("status")}" }
    }
    suspend fun refresh(): JSONObject = lifecycle.withLock { withContext(Dispatchers.IO) {
        if (hostFile.exists()) probe() else JSONObject().put("status", "stopped").also { status.value = if (installed) "Harness 已安装 · 宿主未启动" else "尚未安装 Harness" }
    } }
    suspend fun stop(): Unit = lifecycle.withLock { withContext(Dispatchers.IO) {
        if (hostFile.exists()) {
            if (hostAlive()) connect().use { it.request("host/stop"); it.awaitClosed() }
            hostFile.delete(); graph.secrets.delete(tokenKey)
        }
        status.value = "Harness 已安装 · 宿主已停止"
    } }
    private fun extractAssets(): File = File(directory, "runtime").apply {
        mkdirs()
        graph.assets.list("harness")!!.forEach { name -> graph.assets.open("harness/$name").use { input -> File(this, name).outputStream().use { input.copyTo(it) } } }
    }
    private suspend fun hostAlive(): Boolean {
        val pid = JSONObject(hostFile.readText()).getInt("pid")
        val command = StringBuilder()
        require(graph.root.useRoot()) { "会话宿主需要设备 su 授权" }
        graph.executions.runner.execute(UUID.randomUUID().toString(), listOf(graph.root.su, "-c", "cat /proc/$pid/cmdline 2>/dev/null"), graph.filesDir,
            closeInput = true, timeoutSeconds = 10) { command.append(it) }
        return command.contains("/opt/easylsp-harness/daemon.mjs")
    }
    private suspend fun run(script: String, appFiles: Boolean = false, input: String? = null, timeout: Int = 60): String {
        val text = StringBuilder()
        val result = graph.executions.runner.execute(UUID.randomUUID().toString(), graph.linux.environmentCommand(script, appFiles), graph.filesDir,
            input = input, closeInput = true, timeoutSeconds = timeout) { text.append(it) }
        require(result.exitCode == 0) { "Harness 操作失败：$text" }
        return text.toString()
    }
}
