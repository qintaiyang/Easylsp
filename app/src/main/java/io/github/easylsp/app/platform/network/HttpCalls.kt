package io.github.easylsp.app.platform.network
import kotlinx.coroutines.*
import okhttp3.*
object HttpCalls {
    suspend fun <T> execute(client: OkHttpClient, request: Request, read: (Response) -> T): T = coroutineScope {
        val call = client.newCall(request)
        val work = async(Dispatchers.IO) { try { call.execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code}: ${response.body?.string()?.take(2000)}")
            read(response)
        } } catch (error: java.io.IOException) {
            // OkHttp reports a cancelled socket as IOException; preserve coroutine cancellation.
            ensureActive()
            throw error
        } }
        try { work.await() } finally { call.cancel() }
    }
}
