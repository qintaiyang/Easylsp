package io.github.easylsp.app.platform.network

import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** Compare a bounded real download, including connection time, on the device's network. */
class RuntimeSources(private val client: OkHttpClient = OkHttpClient.Builder().retryOnConnectionFailure(false)
    .connectTimeout(5, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS).callTimeout(8, TimeUnit.SECONDS).build()) {
    data class Source(val name: String, val url: String, val probeUrl: String = url)
    private data class Measurement(val source: Source, val bytesPerSecond: Long)

    suspend fun fastest(label: String, sources: List<Source>, output: (String) -> Unit): Source = coroutineScope {
        output("[测速] $label · 比较 ${sources.size} 个源\n")
        val results = sources.map { source -> async {
            try {
                val started = System.nanoTime()
                val bytes = HttpCalls.execute(client, Request.Builder().url(source.probeUrl)
                    .header("Range", "bytes=0-131071").header("Accept-Encoding", "identity").build()) { response ->
                    require(response.header("Content-Type")?.contains("text/html") != true) { "返回了网页" }
                    requireNotNull(response.body).byteStream().use { input ->
                        val buffer = ByteArray(8192)
                        var count = 0
                        while (count < 131072) {
                            val read = input.read(buffer, 0, minOf(buffer.size, 131072 - count))
                            if (read < 0) break
                            count += read
                        }
                        require(count > 0) { "内容为空" }
                        count
                    }
                }
                Measurement(source, (bytes * 1_000_000_000.0 / (System.nanoTime() - started)).toLong())
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { output("[测速] ${source.name} 不可用：${error.message}\n"); null }
        } }.awaitAll().filterNotNull()
        results.forEach { output("[测速] ${it.source.name} · ${it.bytesPerSecond / 1024} KB/s\n") }
        val selected = results.maxByOrNull { it.bytesPerSecond } ?: error("$label 的下载源均不可用，请检查网络后继续准备")
        output("[选源] $label · ${selected.source.name} · ${selected.source.url}\n")
        selected.source
    }

    companion object {
        private const val nodeFile = "v24.11.0/node-v24.11.0-linux-arm64.tar.gz"
        val node = listOf(Source("Node 官方", "https://nodejs.org/dist/$nodeFile"), Source("npmmirror", "https://npmmirror.com/mirrors/node/$nodeFile"), Source("华为云 Node", "https://mirrors.huaweicloud.com/nodejs/$nodeFile"))
        private const val ubuntuFile = "24.04/release/ubuntu-base-24.04.3-base-arm64.tar.gz"
        val ubuntu = listOf(Source("Ubuntu 官方", "https://cdimage.ubuntu.com/ubuntu-base/releases/$ubuntuFile"), Source("中科大 Ubuntu", "https://mirrors.ustc.edu.cn/ubuntu-cdimage/ubuntu-base/releases/$ubuntuFile"), Source("清华 Ubuntu", "https://mirrors.tuna.tsinghua.edu.cn/ubuntu-cdimage/ubuntu-base/releases/$ubuntuFile"))
        val apt = listOf("Ubuntu Ports 官方" to "http://ports.ubuntu.com/ubuntu-ports", "中科大 APT" to "http://mirrors.ustc.edu.cn/ubuntu-ports", "清华 APT" to "http://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports").map { (name, url) -> Source(name, url, "$url/dists/noble/main/binary-arm64/Packages.xz") }
        val npm = listOf("npm 官方" to "https://registry.npmjs.org", "npmmirror npm" to "https://registry.npmmirror.com").map { (name, url) -> Source(name, url, "$url/ws/-/ws-8.22.1.tgz") }
    }
}
