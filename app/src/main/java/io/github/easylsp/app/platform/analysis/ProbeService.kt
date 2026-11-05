package io.github.easylsp.app.platform.analysis

import android.app.Service
import android.content.Intent
import android.os.*
import dalvik.system.PathClassLoader
import io.github.easylsp.runtime.PluginEngine
import io.github.easylsp.api.*
import jadx.api.JadxArgs
import jadx.api.JadxDecompiler
import org.luckypray.dexkit.DexKitBridge
import java.io.File
import java.io.StringReader

/** M0 integration probe only; this is not the M3 analysis implementation. */
class ProbeService : Service() {
    private val thread = HandlerThread("analysis-probe").apply { start() }
    private val messenger = Messenger(Handler(thread.looper) { request ->
        val response = Bundle()
        try {
            val apk = packageManager.getApplicationInfo("io.github.easylsp.demo", 0).sourceDir
            val loader = PathClassLoader(apk, classLoader)
            val reports = mutableListOf<String>()
            val driver = HookDriver { _, _, _ -> error("依赖探针不安装 Hook") }
            val events = object : PluginEngine.Events {
                override fun state(id: String, revision: Long, instance: String, state: String, error: String) { if (state == "ERROR") error(error) }
                override fun log(id: String, revision: Long, instance: String, level: String, message: String, error: Throwable?) { reports += "BeanShell: $message" }
            }
            val engine = PluginEngine(driver, loader, applicationContext, events)
            engine.load("probe", 1, StringReader("void onLoad() { result = new io.github.easylsp.demo.Calculator().add(2,3); if (result != 5) throw new RuntimeException(\"错误结果\"); plugin.log(\"目标 ClassLoader 调用 add(2,3) = \" + result); }"))
            engine.unload("probe")
            System.loadLibrary("dexkit")
            DexKitBridge.create(apk).use { bridge ->
                val data = requireNotNull(bridge.getClassData("io.github.easylsp.demo.Calculator")) { "DexKit 未找到 Calculator" }
                reports += "DexKit: ${data.name}"
            }
            val args = JadxArgs().apply { inputFiles = listOf(File(apk)); isSkipResources = true; threadsCount = 1; pluginLoader = BundledJadxPlugins(); security = AndroidJadxSecurity() }
            JadxDecompiler(args).use { jadx ->
                jadx.load()
                val code = jadx.classes.single { it.fullName == "io.github.easylsp.demo.Calculator" }.code
                require(code.contains("add(")) { "JADX 未还原 add 方法" }
                reports += "JADX: Calculator Java (${code.length} chars)"
            }
            response.putBoolean("success", true); response.putString("message", reports.joinToString("\n"))
        } catch (error: Throwable) { response.putBoolean("success", false); response.putString("message", android.util.Log.getStackTraceString(error)) }
        request.replyTo.send(Message.obtain().apply { data = response })
        true
    })
    override fun onBind(intent: Intent): IBinder = messenger.binder
    override fun onDestroy() { thread.quitSafely(); super.onDestroy() }
}
