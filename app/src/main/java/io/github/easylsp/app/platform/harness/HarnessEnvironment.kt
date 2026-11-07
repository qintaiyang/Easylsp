package io.github.easylsp.app.platform.harness

import io.github.easylsp.app.EasylspApp
import io.github.easylsp.app.platform.network.HttpCalls
import io.github.easylsp.app.platform.root.RootCommand
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
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

    private val preparation = Mutex()
    private val preparationWorkspace = "c354fefb-5129-4e1b-8afc-d89f80537e20"
    private val preparationPrefs = graph.getSharedPreferences("ai-preparation", 0)
    val preparationId = MutableStateFlow<String?>(preparationPrefs.getString("executionId", null))
    fun preparationPage(offset: Int = 0) = preparationId.value?.let { graph.executions.store.page(preparationWorkspace, it, offset, 12000) }
    @Synchronized fun prepare(): String {
        preparationId.value?.let { id -> if (graph.executions.store.records.value.any { it.id == id && it.state == "RUNNING" }) return id }
        graph.executions.bind(io.github.easylsp.app.platform.terminal.Workspace(preparationWorkspace, "GLOBAL"))
        val id = graph.executions.start(preparationWorkspace, "environment", "准备 AI 环境") { _, output ->
            preparation.withLock {
                output("[ROOT] 检查授权\n")
                require(graph.root.useRoot()) { "请在设备 Root 管理器允许 Easylsp" }
                if (!graph.linux.installed) coroutineScope {
                    val watcher = launch { graph.linux.status.collect { output(it + "\n") } }
                    try { graph.linux.install() } finally { watcher.cancel() }
                }
                if (!installed) { output("[HARNESS] Node / APT / NPM\n"); install(output) }
                output("[START] 启动宿主\n"); start(output); output("AI 环境已就绪\n")
            }
            0
        }
        preparationId.value = id
        preparationPrefs.edit().putString("executionId", id).commit()
        return id
    }
    suspend fun prepareAndWait() {
        val id = prepare()
        val result = graph.executions.await(preparationWorkspace, id)
        check(result.getString("state") == "COMPLETE") { result.getString("text") }
    }
    suspend fun install(output: (String) -> Unit = {}): Unit = lifecycle.withLock { withContext(Dispatchers.IO) {
        require(graph.linux.installed) { "请先安装 Ubuntu" }
        require(!hostFile.exists() || !hostAlive()) { "请先停止运行中的会话宿主再安装" }
        hostFile.delete()
        busy.value = true
        val archive = File(directory, "node-v24.11.0-linux-arm64.tar.gz")
        try {
            status.value = "下载 Node 24.11.0…"
            output("[DOWNLOAD] Node 24.11.0" + if (archive.exists()) " 使用已下载文件\n" else " 开始下载\n")
            val client = OkHttpClient.Builder().retryOnConnectionFailure(false).callTimeout(10, TimeUnit.MINUTES).build()
            if (!archive.exists()) HttpCalls.execute(client, Request.Builder().url("https://nodejs.org/dist/v24.11.0/node-v24.11.0-linux-arm64.tar.gz").build()) { response ->
                val partial = File(directory, "node-download.partial")
                requireNotNull(response.body).byteStream().use { input -> partial.outputStream().use { input.copyTo(it) } }
                check(partial.renameTo(archive)) { "下载完成后无法保存 Node 安装包" }
            }
            val assets = extractAssets()
            status.value = "安装固定 Node 与 Harness…"
            marker.delete()
            run("""
                printf '127.0.0.1 localhost\n::1 localhost\n' > /etc/hosts
                export DEBIAN_FRONTEND=noninteractive
                if [ ! -f /opt/easylsp-apt-ready ]; then
                    echo '[APT] 更新目录并安装依赖'
                    apt-get update
                    apt-get install -y ca-certificates libstdc++6
                    touch /opt/easylsp-apt-ready
                fi
                mkdir -p /opt/easylsp-node-v24.11.0 /opt/easylsp-harness-next
                echo '[NODE] 解压固定运行时'
                tar -xzf ${q(archive.path)} -C /opt/easylsp-node-v24.11.0 --strip-components=1
                cp ${q(assets.path)}/* /opt/easylsp-harness-next/
                cd /opt/easylsp-harness-next
                echo '[NPM] 安装 Harness 依赖'
                npm ci --omit=dev --no-audit --no-fund
                node --input-type=module -e 'import {readFileSync} from "node:fs"; if (process.version !== "v24.11.0" || JSON.parse(readFileSync("node_modules/@deepseek-ai/dsh/package.json")).version !== "0.2.2-alpha.3" || JSON.parse(readFileSync("node_modules/ws/package.json")).version !== "8.22.1") process.exit(1)'
            """.trimIndent(), appFiles = true, timeout = 1800, output = output)
            run("rm -rf /opt/easylsp-harness-previous; if [ -d /opt/easylsp-harness ]; then mv /opt/easylsp-harness /opt/easylsp-harness-previous; fi; mv /opt/easylsp-harness-next /opt/easylsp-harness")
            marker.writeText(JSONObject().put("node", "24.11.0").put("harness", "0.2.2-alpha.3").put("ws", "8.22.1").put("installedAt", System.currentTimeMillis()).toString())
            status.value = "Harness 0.2.2-alpha.3 已安装 · Node 24.11.0"
        } catch (error: Exception) { status.value = "Harness 安装未完成：${error.message}"; throw error }
        finally { if (installed) archive.delete(); busy.value = false }
    } }
    suspend fun start(output: (String) -> Unit = {}): JSONObject = lifecycle.withLock { withContext(Dispatchers.IO) {
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
            .put("token", token).put("directory", "/var/lib/easylsp-harness").toString() + "\n", output = output)
        val endpoint = JSONObject(result.trim())
        hostFile.writeText(endpoint.toString())
        probe()
    } }
    suspend fun connect(): HarnessConnection = withContext(Dispatchers.IO) {
        require(hostFile.exists()) { "会话宿主未启动" }
        HarnessConnection.connect(JSONObject(hostFile.readText()).getInt("port"), graph.secrets.get(tokenKey))
    }
    suspend fun testModel(provider: io.github.easylsp.app.data.ProviderEntity, secret: String): String {
        val id = UUID.randomUUID().toString()
        val config = HarnessConfig.model(provider, secret, "Reply OK.").put("directory", "/opt/easylsp-harness/probes/" + id).put("sessionId", id)
        prepareAndWait()
        return run("cd /opt/easylsp-harness && node probe.mjs", input = config.toString() + "\n", timeout = provider.timeoutSeconds)
    }
    private suspend fun probe(): JSONObject = connect().use { connection ->
        connection.request("host/status").also { status.value = "会话宿主：${it.getString("status")}" }
    }
    suspend fun refresh(): JSONObject = lifecycle.withLock { withContext(Dispatchers.IO) {
        if (hostFile.exists() && hostAlive()) probe() else {
            hostFile.delete()
            JSONObject().put("status", "stopped").also { status.value = if (installed) "Harness 已安装 · 宿主未启动" else "尚未安装 Harness" }
        }
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
    private suspend fun run(script: String, appFiles: Boolean = false, input: String? = null, timeout: Int = 60, output: (String) -> Unit = {}): String {
        val text = StringBuilder()
        val result = graph.executions.runner.execute(UUID.randomUUID().toString(), graph.linux.environmentCommand(script, appFiles), graph.filesDir,
            input = input, closeInput = true, timeoutSeconds = timeout) {
                text.append(it)
                output(it)
            }
        require(result.exitCode == 0) { "Harness 操作失败：$text" }
        return text.toString()
    }
}
