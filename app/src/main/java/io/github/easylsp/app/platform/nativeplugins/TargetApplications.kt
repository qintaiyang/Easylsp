package io.github.easylsp.app.platform.nativeplugins

import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.os.Process
import io.github.easylsp.app.EasylspApp
import io.github.easylsp.app.platform.root.RootCommand
import kotlinx.coroutines.runBlocking
import java.util.UUID

/** PackageManager sees the manager's user; root queries preserve other users' UIDs. */
class TargetApplications(private val graph: EasylspApp) {
    fun info(packageName: String, userId: Int): PackageInfo {
        require(packageName.matches(Regex("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+")) && userId in 0..19999)
        if (userId == Process.myUid() / 100000) return graph.packageManager.getPackageInfo(packageName, 0)
        check(runBlocking { graph.root.useRoot() }) { "查询工作资料或其他用户应用需要root" }
        val quoted = RootCommand.quote(packageName)
        val output = StringBuilder()
        val script = "pm list packages -U --user $userId $quoted\npm path --user $userId $quoted\ndumpsys package $quoted"
        val result = runBlocking { graph.executions.runner.execute(UUID.randomUUID().toString(), listOf(graph.root.su, "-c", script), graph.filesDir, closeInput = true, timeoutSeconds = 15) { output.append(it) } }
        check(result.exitCode == 0) { "读取目标用户应用失败：${result.exitCode}" }
        val text = output.toString()
        val uidMatch = Regex("(?m)^package:${Regex.escape(packageName)} uid:(\\d+)").find(text) ?: throw android.content.pm.PackageManager.NameNotFoundException("$packageName / user $userId")
        val uid = uidMatch.groupValues[1].toInt()
        require(uid / 100000 == userId) { "目标用户UID不匹配" }
        val path = requireNotNull(Regex("(?m)^package:(/[^\\r\\n]+/base\\.apk)$").find(text)) { "目标APK路径不存在" }.groupValues[1]
        val version = requireNotNull(Regex("\\bversionCode=(\\d+)").find(text)) { "无法读取目标版本" }.groupValues[1].toLong()
        val apks = Regex("(?m)^package:(/[^\\r\\n]+\\.apk)$").findAll(text).map { it.groupValues[1] }.toList()
        return PackageInfo().apply {
            this.packageName = packageName; setLongVersionCode(version)
            versionName = Regex("(?m)^\\s*versionName=(.*)$").find(text)?.groupValues?.get(1)?.trim().orEmpty()
            applicationInfo = ApplicationInfo().apply { this.packageName = packageName; this.uid = uid; sourceDir = path; publicSourceDir = path; splitSourceDirs = apks.filter { it != path }.toTypedArray(); splitPublicSourceDirs = splitSourceDirs }
        }
    }
}
