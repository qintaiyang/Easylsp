package io.github.easylsp.app

import android.app.Application
import android.os.Handler
import android.os.RemoteException
import androidx.test.platform.app.InstrumentationRegistry
import io.github.easylsp.api.HookDriver
import io.github.easylsp.api.internal.ipc.IEasylspBridge
import io.github.easylsp.runtime.HostConnection
import org.junit.Assert.assertEquals
import org.junit.Test
import java.lang.reflect.Proxy

class JavaRuntimeBoundaryDeviceTest {
    @Test fun rejectedSessionEventsDoNotEscapeIntoTargetCallbacks() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        val connection = HostConnection(app, HookDriver { _, _, _ -> error("No hooks expected") })
        var publications = 0
        var failure: Exception = SecurityException("会话已结束")
        val bridge = Proxy.newProxyInstance(IEasylspBridge::class.java.classLoader, arrayOf(IEasylspBridge::class.java)) { _, method, _ ->
            check(method.name == "publish")
            publications++
            throw failure
        }
        fun field(name: String) = HostConnection::class.java.getDeclaredField(name).apply { isAccessible = true }
        field("bridge").set(connection, bridge)
        field("session").set(connection, "expired-session")
        try {
            connection.log("plugin", 1, "instance", "INFO", "late callback", null)
            connection.state("plugin", 1, "instance", "STOPPED", "")
            failure = RemoteException("manager died")
            connection.log("plugin", 1, "instance", "INFO", "late callback", null)
            assertEquals(3, publications)
        } finally {
            app.unregisterActivityLifecycleCallbacks(field("inspector").get(connection) as Application.ActivityLifecycleCallbacks)
            (field("worker").get(connection) as Handler).looper.quitSafely()
        }
    }
}
