package io.github.easylsp.app.platform.frida

import io.github.easylsp.app.EasylspApp
import io.github.easylsp.app.platform.root.RootCommand
import org.json.JSONObject
import java.io.File
import java.util.UUID

object FridaCommand {
    fun arguments(binary: String, pid: Int, script: File, root: Boolean): List<String> {
        require(binary.startsWith("/") && pid > 0) { "需要 frida-inject 绝对路径及有效 PID" }
        val command = listOf(binary, "-p", pid.toString(), "-s", script.absolutePath)
        return if (root) listOf("su", "-c", command.joinToString(" ", transform = RootCommand::quote)) else command
    }
}
class FridaBackend(private val graph: EasylspApp) {
    private val prefs = graph.getSharedPreferences("frida", 0)
    fun config() = JSONObject().put("binary", prefs.getString("binary", "/data/local/tmp/frida-inject")).put("root", prefs.getBoolean("root", true))
    fun save(binary: String, root: Boolean) { require(binary.startsWith("/")); prefs.edit().putString("binary", binary).putBoolean("root", root).apply() }
    fun start(projectId: String, pid: Int, source: String, binary: String, root: Boolean, timeout: Int = 3600): String {
        require(source.isNotBlank() && timeout in 1..3600)
        val cwd = graph.executions.directory(projectId)
        val file = File(cwd, "frida-${UUID.randomUUID()}.js").apply { writeText(source) }
        save(binary, root)
        return graph.executions.start(projectId, "frida", "PID $pid · $binary") { id, output ->
            try {
                val privileged = graph.root.useRoot() || root
                val command = FridaCommand.arguments(binary, pid, file, privileged)
                graph.executions.runner.execute(id, command, cwd, closeInput = true, timeoutSeconds = timeout, output = output).exitCode
            } finally { file.delete() }
        }
    }
}
