package io.github.easylsp.app.platform.skills

import io.github.easylsp.app.EasylspApp
import io.github.easylsp.app.platform.tasks.RemoteTaskClient
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.withPermit
import org.json.JSONObject

class SkillRunner(private val graph: EasylspApp) {
    private val beanshell = kotlinx.coroutines.sync.Semaphore(1)
    fun start(projectId: String, skillId: String, arguments: JSONObject): String {
        val skill = graph.store.workflows.skill(skillId) ?: error("Skill 不存在")
        require(skill.source.isNotBlank()) { "Skill 源码为空" }
        require(skill.enabled && skill.runtime in setOf("shell", "beanshell")) { "Skill 未启用或不可执行" }
        val cwd = graph.executions.directory(projectId)
        return graph.executions.start(projectId, "skill", "${skill.name} · ${skill.runtime}") { id, output ->
            withTimeout(skill.timeoutSeconds * 1000L) {
                if (skill.runtime == "shell") {
                    // Pass arguments as shell positional values so su cannot strip the environment.
                    val script = "export EASYLSP_ARGUMENTS=\"\$1\" EASYLSP_PROJECT=\"\$2\"; " + skill.source
                    val command = graph.root.wrap(listOf("/system/bin/sh", "-c", script, "easylsp-skill", arguments.toString(), projectId))
                    graph.executions.runner.execute(id, command, cwd, input = arguments.toString(), closeInput = true, timeoutSeconds = skill.timeoutSeconds, output = output).exitCode
                }
                else {
                    beanshell.withPermit {
                        val result = RemoteTaskClient(graph, SkillService::class.java).call(JSONObject().put("source", skill.source).put("arguments", arguments).put("workspace", cwd.absolutePath), output)
                        output("\n结果：" + result.toString()); 0
                    }
                }
            }
        }
    }
}
