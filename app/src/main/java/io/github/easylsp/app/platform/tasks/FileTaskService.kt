package io.github.easylsp.app.platform.tasks

import android.app.Service
import android.content.Intent
import android.os.*
import io.github.easylsp.api.internal.ipc.*
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

abstract class FileTaskService : Service() {
    companion object { private val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher() }
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val tasks = ConcurrentHashMap<String, Job>()
    protected open fun onTaskCancelled() {}
    protected abstract suspend fun execute(payload: JSONObject, progress: (String) -> Unit): JSONObject
    private val binder = object : ITaskService.Stub() {
        override fun execute(requestId: String, arguments: Bundle, callback: ITaskCallback) {
            val job = scope.launch(start = CoroutineStart.LAZY) {
                val file = File(cacheDir, "$requestId.json")
                try {
                    val result = execute(JSONObject(arguments.getString("payload")!!)) { callback.onProgress(requestId, it) }
                    ensureActive()
                    file.writeText(result.toString())
                    ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { callback.onResult(requestId, true, "", it) }
                } catch (error: CancellationException) {
                    callback.onResult(requestId, false, "任务已取消", null)
                    throw error
                } catch (error: Exception) {
                    callback.onResult(requestId, false, error.message ?: error.toString(), null)
                } finally { tasks.remove(requestId); file.delete() }
            }
            tasks[requestId] = job
            job.start()
        }
        override fun cancel(requestId: String) { tasks[requestId]?.let { onTaskCancelled(); it.cancel() } }
    }
    override fun onBind(intent: Intent): IBinder = binder
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
