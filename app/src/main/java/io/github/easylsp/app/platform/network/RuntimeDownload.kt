package io.github.easylsp.app.platform.network

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/** Fixed-version runtime archives can continue after a slow or interrupted download. */
object RuntimeDownload {
    private val client = OkHttpClient.Builder().retryOnConnectionFailure(false)
        .connectTimeout(30, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()

    suspend fun fetch(url: String, archive: File, partial: File, progress: (Long, Long) -> Unit) {
        if (archive.exists()) return
        val offset = partial.length()
        val request = Request.Builder().url(url).header("Accept-Encoding", "identity").apply {
            if (offset > 0) header("Range", "bytes=$offset-")
        }.build()
        val context = currentCoroutineContext()
        HttpCalls.execute(client, request) { response ->
            val resumed = response.code == 206
            if (resumed) require(response.header("Content-Range")?.startsWith("bytes $offset-") == true) { "下载服务器返回了不匹配的续传位置" }
            val body = requireNotNull(response.body)
            var received = if (resumed) offset else 0L
            val total = body.contentLength().let { if (it >= 0) received + it else -1L }
            progress(received, total)
            body.byteStream().use { input -> FileOutputStream(partial, resumed).use { output ->
                val buffer = ByteArray(65536)
                var reportedAt = System.nanoTime()
                while (true) {
                    context.ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                    received += count
                    val now = System.nanoTime()
                    if (now - reportedAt >= 1_000_000_000L) { progress(received, total); reportedAt = now }
                }
            } }
            check(partial.renameTo(archive)) { "下载完成后无法保存安装包" }
            progress(received, total)
        }
    }

    fun label(received: Long, total: Long): String =
        if (total > 0) "${received * 100 / total}% · ${received / 1048576} / ${total / 1048576} MB"
        else "${received / 1048576} MB"
}
