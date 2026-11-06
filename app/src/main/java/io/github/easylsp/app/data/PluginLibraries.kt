package io.github.easylsp.app.data
import io.github.easylsp.app.util.readUpTo
import java.util.zip.ZipInputStream
object PluginLibraries {
    fun validate(path: String, bytes: ByteArray) {
        require(path.startsWith("lib/") && path.removePrefix("lib/").isNotEmpty() && !path.removePrefix("lib/").contains('/') && !path.contains('\\')) { "依赖必须放在 lib/ 根目录" }
        require(bytes.size in 8..16 * 1024 * 1024) { "单个依赖上限 16 MiB" }
        when {
            path.endsWith(".dex") -> require(bytes.take(4).toByteArray().contentEquals(byteArrayOf(100, 101, 120, 10))) { "无效 DEX 文件" }
            path.endsWith(".apk") -> {
                var dex = false; var total = 0
                ZipInputStream(bytes.inputStream()).use { zip -> while (true) { val entry = zip.nextEntry ?: break; if (entry.name.matches(Regex("classes(?:[0-9]+)?\\.dex"))) { total += zip.readUpTo(32 * 1024 * 1024 + 1).size; require(total <= 32 * 1024 * 1024) { "依赖 DEX 解压大小超过 32 MiB" }; dex = true } } }
                require(dex) { "依赖 APK 没有 classes.dex" }
            }
            else -> error("当前只支持 DEX/APK 依赖；JAR/AAR 需要先转换")
        }
    }
}
object PluginProcesses {
    fun matches(plugin: PluginEntity, packageName: String, processName: String): Boolean = when (plugin.processScope) {
        "all" -> processName == packageName || processName.startsWith("$packageName:")
        "main" -> processName == packageName
        "children" -> processName.startsWith("$packageName:")
        else -> processName == plugin.processName.ifEmpty { packageName }
    }
}
