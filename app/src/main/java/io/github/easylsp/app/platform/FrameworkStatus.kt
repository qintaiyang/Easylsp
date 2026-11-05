package io.github.easylsp.app.platform

import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class FrameworkState(val active: Boolean = false, val description: String = "未检测到框架")

/** Modern modules receive framework status through the service, without hooking their own app. */
class FrameworkStatus {
    @Volatile private var service: XposedService? = null
    private val current = MutableStateFlow(FrameworkState())
    val state = current.asStateFlow()

    fun connect() {
        XposedServiceHelper.registerListener(object : XposedServiceHelper.OnServiceListener {
            override fun onServiceBind(service: XposedService) {
                this@FrameworkStatus.service = service
                current.value = FrameworkState(true, "${service.frameworkName} ${service.frameworkVersion} / API ${service.apiVersion}")
            }

            override fun onServiceDied(service: XposedService) {
                this@FrameworkStatus.service = null
                current.value = FrameworkState()
            }
        })
    }
    fun publishDiagnostic(key: String, config: org.json.JSONObject) {
        val preferences = requireNotNull(service) { "框架服务未连接，无法发布入口对照模式；可选择仅进程外采集" }.getRemotePreferences("startup-diagnostics")
        check(preferences.edit().putString(key, config.toString()).commit()) { "诊断配置发布失败" }
    }
    fun clearDiagnostic(key: String, runId: String) {
        val preferences = requireNotNull(service) { "框架服务已断开，诊断配置将在时限到达后失效" }.getRemotePreferences("startup-diagnostics")
        val value = preferences.getString(key, null) ?: return
        if (org.json.JSONObject(value).getString("diagnosticRunId") == runId) check(preferences.edit().remove(key).commit()) { "清除诊断配置失败" }
    }
    fun nativeSnapshot(key: String): String? = requireNotNull(service) { "框架服务未连接" }
        .getRemotePreferences("native-plugins").getString(key, null)
    /** A unique artifact is closed completely before any snapshot can reference it. */
    fun writeNativeArtifact(name: String, file: java.io.File) {
        val framework = requireNotNull(service) { "框架服务未连接" }
        check(name !in framework.listRemoteFiles()) { "远程产物已存在，不能覆盖：$name" }
        android.os.ParcelFileDescriptor.AutoCloseOutputStream(framework.openRemoteFile(name)).use { output ->
            file.inputStream().use { it.copyTo(output) }
        }
    }
    fun publishNativeSnapshot(key: String, snapshot: org.json.JSONObject) {
        val preferences = requireNotNull(service) { "框架服务未连接" }.getRemotePreferences("native-plugins")
        check(preferences.edit().putString(key, snapshot.toString()).commit()) { "native 快照提交失败" }
        check(org.json.JSONObject(requireNotNull(nativeSnapshot(key))).getString("generation") == snapshot.getString("generation")) { "框架当前 generation 与发布不一致" }
    }
}
