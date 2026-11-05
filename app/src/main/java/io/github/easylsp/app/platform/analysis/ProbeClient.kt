package io.github.easylsp.app.platform.analysis
import android.content.*
import android.os.*
import kotlinx.coroutines.*
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

suspend fun runDependencyProbe(context: Context): Bundle = withContext(Dispatchers.Main) {
    lateinit var connection: ServiceConnection
    var bound = false
    try {
        withTimeout(60000) {
            suspendCancellableCoroutine { continuation ->
                val reply = Messenger(Handler(Looper.getMainLooper()) { message ->
                    if (continuation.isActive) continuation.resume(message.data)
                    true
                })
                connection = object : ServiceConnection {
                    override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                        Messenger(binder).send(Message.obtain().apply { replyTo = reply })
                    }
                    override fun onServiceDisconnected(name: ComponentName) {
                        if (continuation.isActive) continuation.resumeWithException(IllegalStateException("探针进程已退出"))
                    }
                }
                bound = context.bindService(Intent(context, ProbeService::class.java), connection, Context.BIND_AUTO_CREATE)
                if (!bound) continuation.resumeWithException(IllegalStateException("无法绑定分析探针服务"))
            }
        }
    } finally { if (bound) context.unbindService(connection) }
}
