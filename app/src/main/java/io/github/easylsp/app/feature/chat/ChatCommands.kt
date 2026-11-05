package io.github.easylsp.app.feature.chat

object ChatCommands {
    val entries = linkedMapOf(
        "help" to "查看命令", "compact" to "压缩当前上下文", "context" to "查看上下文与能力", "new" to "新建会话", "model" to "切换模型",
        "skills" to "选择或释放 Skill", "tools" to "搜索与选择工具", "memory" to "记忆与规则", "agents" to "子代理任务", "fork" to "创建独立分支", "stop" to "停止当前执行树"
    )
    data class Parsed(val name: String, val argument: String, val known: Boolean)
    fun parse(text: String): Parsed? {
        if (!text.startsWith('/') || text.startsWith("//")) return null
        val name = text.substring(1).substringBefore(' ').substringBefore('\n')
        val argument = text.drop(name.length + 1).trim()
        val valid = '\n' !in text && name in entries && (argument.isEmpty() ||
            (name == "skills" && argument.matches(Regex("(use|release) [^\\s]+"))))
        return Parsed(name, argument, valid)
    }
    fun literal(text: String) = if (text.startsWith("//")) text.drop(1) else text
}
