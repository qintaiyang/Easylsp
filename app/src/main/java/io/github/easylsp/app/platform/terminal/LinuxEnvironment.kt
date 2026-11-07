package io.github.easylsp.app.platform.terminal

import io.github.easylsp.app.EasylspApp
import io.github.easylsp.app.platform.network.HttpCalls
import io.github.easylsp.app.platform.root.RootCommand
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.*
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/** An explicit Ubuntu base installation, separate from Android root permission and the app UID. */
class LinuxEnvironment(private val graph: EasylspApp) {
    val root = File(graph.filesDir, "linux/ubuntu")
    private val namespace = MountNamespace(graph)
    private val marker = File(graph.filesDir, "linux/installed.json")
    val status = MutableStateFlow(if (marker.exists()) "Ubuntu 已安装" else "尚未安装 Ubuntu")
    val progress = MutableStateFlow(0f)
    val installed get() = marker.exists()
    private val source = "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.3-base-arm64.tar.gz"
    suspend fun install(): JSONObject = withContext(Dispatchers.IO) {
        require(graph.root.useRoot()) { "安装 chroot 环境需要设备 su 授权，请在 root 管理器允许 Easylsp" }
        require(!installed) { "Ubuntu 已安装" }
        val archive = File(graph.cacheDir, "ubuntu-base-arm64.tar.gz")
        try {
            status.value = "下载 Ubuntu 24.04 arm64…"; progress.value = 0f
            val client = OkHttpClient.Builder().retryOnConnectionFailure(false).callTimeout(10, TimeUnit.MINUTES).build()
            val downloadContext = currentCoroutineContext()
            HttpCalls.execute(client, Request.Builder().url(source).build()) { response ->
                val body = requireNotNull(response.body); val length = body.contentLength()
                body.byteStream().use { input -> archive.outputStream().use { output ->
                    val buffer = ByteArray(65536); var read = 0L
                    while (true) { downloadContext.ensureActive(); val n = input.read(buffer); if (n < 0) break; output.write(buffer, 0, n); read += n; if (length > 0) progress.value = read.toFloat() / length }
                } }
            }
            status.value = "解压 Ubuntu 文件系统…"
            // A failed install can leave absolute rootfs symlinks that Android tar refuses to overwrite.
            runRoot("rm -rf ${q(root.path)}; mkdir -p ${q(root.path)}; tar -xpf ${q(archive.path)} -C ${q(root.path)}; mkdir -p ${q(root.path + "/proc")} ${q(root.path + "/sys")} ${q(root.path + "/dev")} ${q(root.path + "/workspace")}; printf 'nameserver 1.1.1.1\\nnameserver 8.8.8.8\\n' > ${q(root.path + "/etc/resolv.conf")}; printf '127.0.0.1 localhost\\n::1 localhost\\n' > ${q(root.path + "/etc/hosts")}")
            runRoot("chroot ${q(root.path)} /bin/bash -c 'test -x /usr/bin/apt-get && /usr/bin/id && /bin/cat /etc/os-release'")
            val info = JSONObject().put("distribution", "Ubuntu 24.04.3 arm64").put("source", source).put("installedAt", System.currentTimeMillis()).put("backend", "chroot")
            marker.parentFile!!.mkdirs(); marker.writeText(info.toString())
            status.value = "Ubuntu 已安装 · chroot 内用户 root"; progress.value = 1f
            info
        } catch (e: Exception) { status.value = "安装未完成：${e.message}"; throw e }
        finally { archive.delete() }
    }
    private fun q(text: String) = RootCommand.quote(text)
    /** Global runtime commands have no project workspace; all mounts stay in the root child. */
    suspend fun environmentCommand(script: String, appFiles: Boolean = false): List<String> {
        require(installed) { "请先安装 Ubuntu" }
        require(graph.root.useRoot()) { "Linux 环境需要设备 su 授权" }
        val mounts = listOf("/dev", "/proc").joinToString("; ") { path -> "mount --rbind ${q(path)} ${q(root.path + path)}" }
        val files = if (appFiles) "mkdir -p ${q(root.path + graph.filesDir.path)}; mount --bind ${q(graph.filesDir.path)} ${q(root.path + graph.filesDir.path)}; " else ""
        val inner = "set -e; export HOME=/root TMPDIR=/tmp PATH=/opt/easylsp-node-v24.11.0/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin; $script"
        return listOf(graph.root.su, "-c", namespace.shell("$mounts; $files exec chroot ${q(root.path)} /bin/bash -c ${q(inner)}"))
    }
    private suspend fun runRoot(script: String): String {
        val text = StringBuilder()
        val result = graph.executions.runner.execute(java.util.UUID.randomUUID().toString(), listOf(graph.root.su, "-c", namespace.shell(script)), graph.filesDir, closeInput = true, timeoutSeconds = 600) { text.append(it) }
        require(result.exitCode == 0) { "Linux 环境操作失败：$text" }; return text.toString()
    }
    suspend fun command(projectId: String, script: String, backend: String = "chroot", appFiles: Boolean = false): List<String> {
        require(installed) { "尚未安装 Ubuntu，可调用 install_linux 工具安装" }
        require(graph.root.useRoot()) { "Linux 环境需要设备 su 授权" }
        val workspace = graph.executions.directory(projectId)
        val mount = listOf("/dev", "/proc", "/sys").joinToString("; ") { path -> "mount --rbind ${q(path)} ${q(root.path + path)}" }
        val projectDirectory = root.path + "/workspace/$projectId"
        val fileMount = if (appFiles) "mkdir -p ${q(root.path + graph.filesDir.path)}; mount --bind ${q(graph.filesDir.path)} ${q(root.path + graph.filesDir.path)}; " else ""
        val setup = "$mount; $fileMount mkdir -p ${q(projectDirectory)}; mount --bind ${q(workspace.path)} ${q(projectDirectory)}"
        val inner = "umask 0007; cd ${q("/workspace/$projectId")}; export HOME=/root TMPDIR=/tmp TERM=xterm-256color PATH=/opt/easylsp-node-v24.11.0/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin; $script"
        if (backend == "proot") {
            // Existing Termux proot is usable through su without granting access to Termux sessions.
            val proot = "/data/data/com.termux/files/usr/bin/proot"
            val prefix = "/data/data/com.termux/files/usr/lib"
            return listOf(graph.root.su, "-c", "test -x ${q(proot)} || { echo '未安装 proot；请使用 chroot 后端'; exit 1; }; export LD_LIBRARY_PATH=${q(prefix)}; exec ${q(proot)} -0 -r ${q(root.path)} -b /dev -b /proc -b /sys -b ${q(workspace.path + ":/workspace/$projectId")} /bin/bash -lc ${q(inner)}")
        }
        return listOf(graph.root.su, "-c", namespace.shell("$setup; exec chroot ${q(root.path)} /bin/bash -lc ${q(inner)}"))
    }
    suspend fun terminal(projectId: String): TerminalSessions.Entry = graph.terminals.create(projectId, "Ubuntu · root", command(projectId, "exec /bin/bash -i"))
    suspend fun scriptCommand(projectId: String, source: File, runtime: String, arguments: JSONObject): List<String> {
        require(installed) { "${runtime} 需要先安装 Linux 环境及对应运行时" }
        val interpreter = when (runtime) { "python" -> "/usr/bin/python3"; "node" -> "node"; "bash" -> "/bin/bash"; else -> error("未知运行时") }
        return command(projectId, "export ${q("EASYLSP_ARGUMENTS=$arguments")}; exec $interpreter ${q(source.path)}", appFiles = true)
    }
    suspend fun remove() = withContext(Dispatchers.IO) {
        require(graph.terminals.entries.value.none { it.session.isRunning && it.title.startsWith("Ubuntu") }) { "请先停止 Linux 终端" }
        require(graph.root.useRoot())
        runRoot("cat /proc/mounts | awk '{print \$2}' | sort -r | while read m; do case \"\$m\" in ${q(root.path)}/*) umount \"\$m\";; esac; done; rm -rf ${q(root.path)}")
        marker.delete(); status.value = "尚未安装 Ubuntu"; progress.value = 0f
    }
}
