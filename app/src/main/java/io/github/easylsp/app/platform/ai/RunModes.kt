package io.github.easylsp.app.platform.ai
import io.github.easylsp.app.platform.tools.ToolDefinition
object RunModes {
    val names = linkedMapOf("normal" to "普通", "plan" to "计划", "minimal" to "极简")
    private val terminal = setOf("run_command", "read_execution", "write_stdin", "stop_execution")
    fun filter(mode: String, tools: List<ToolDefinition>): List<ToolDefinition> = when (mode) {
        "minimal" -> tools.filter { it.name in terminal }
        "plan" -> tools.filter { !it.writes && it.source == "内置" }
        else -> tools
    }
    fun instruction(mode: String) = when (mode) {
        "plan" -> "Plan mode: inspect and ask questions, then propose a concrete plan. Do not change files or execute commands."
        "minimal" -> "Minimal mode: only terminal execution tools are available. Use the project workspace and inspect real output."
        else -> "Normal mode: use enabled project tools to complete the user's task."
    }
}
