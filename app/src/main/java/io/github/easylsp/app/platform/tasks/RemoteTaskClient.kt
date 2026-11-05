package io.github.easylsp.app.platform.tasks

import android.content.*
import android.os.*
import io.github.easylsp.api.internal.ipc.*
import kotlinx.coroutines.*
import org.json.JSONObject
import java.util.UUID

class RemoteTaskClient(private val context: Context, private val serviceClass: Class<*>) {
    suspend fun call(payload: JSONObject, progress: (String) -> Unit = {}): JSONObject {
        val id = UUID.randomUUID().toString()
        val ready = CompletableDeferred<ITaskService>()
        val result = CompletableDeferred<String>()
        val stopped = CompletableDeferred<Unit>()
        var bound = false
        var remote: ITaskService? = null
        val callback = object : ITaskCallback.Stub() {
            override fun onProgress(requestId: String, message: String) { progress(message) }
            override fun onResult(requestId: String, success: Boolean, error: String, descriptor: ParcelFileDescriptor?) {
                try {
                    if (!success) result.completeExceptionally(IllegalStateException(error))
                    else ParcelFileDescriptor.AutoCloseInputStream(requireNotNull(descriptor)).bufferedReader().use { result.complete(it.readText()) }
                }
                catch (failure: Exception) { result.completeExceptionally(failure) }
                finally { stopped.complete(Unit) }
            }
        }
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) { ready.complete(ITaskService.Stub.asInterface(binder)) }
            override fun onServiceDisconnected(name: ComponentName) { fail("工具进程已断开") }
            override fun onBindingDied(name: ComponentName) { fail("工具进程绑定已失效") }
            override fun onNullBinding(name: ComponentName) { fail("工具服务未提供 Binder") }
            fun fail(message: String) { val error = IllegalStateException(message); ready.completeExceptionally(error); result.completeExceptionally(error); stopped.complete(Unit) }
        }
        try {
            withContext(Dispatchers.Main) { bound = context.bindService(Intent(context, serviceClass), connection, Context.BIND_AUTO_CREATE); check(bound) { "无法绑定工具服务" } }
            remote = withTimeout(15000) { ready.await() }
            remote.execute(id, Bundle().apply { putString("payload", payload.toString()) }, callback)
            return JSONObject(result.await())
        } finally {
            withContext(NonCancellable) {
                try {
                    if (!result.isCompleted && remote != null) {
                        remote.cancel(id)
                        // Wait for completion or Android's disconnect callback before another bind.
                        withTimeout(5000) { stopped.await() }
                    }
                } catch (error: RemoteException) { android.util.Log.w("Easylsp", "工具进程已退出", error) }
                finally { withContext(Dispatchers.Main) { if (bound) context.unbindService(connection) } }
            }
        }
    }
}
