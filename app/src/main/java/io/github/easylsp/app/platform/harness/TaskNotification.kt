package io.github.easylsp.app.platform.harness

/** Includes background and archived sessions; the engine snapshot is authoritative. */
data class TaskNotification(val text: String, val route: String)
internal data class TaskActivity(
    val conversationId: String?, val status: String, val waiting: Boolean,
    val ownerConversationId: String? = conversationId, val maintenance: Boolean = false
)
internal fun taskNotification(connected: Boolean, sessions: List<TaskActivity>): TaskNotification {
    if (!connected) return TaskNotification("执行服务已断开，任务状态待确认", "conversations")
    val active = sessions.filter { it.status in setOf("running", "queued") || it.waiting }
    val users = active.filterNot { it.maintenance }.withIndex().groupBy { it.value.ownerConversationId ?: "unknown-${it.index}" }
        .values.map { rows ->
            val tasks = rows.map { it.value }
            tasks.first().copy(status = if (tasks.any { it.status == "running" }) "running" else "queued", waiting = tasks.any { it.waiting })
        }
    val maintenance = active.any { it.maintenance }
    if (users.isEmpty()) return TaskNotification(if (maintenance) "后台整理中" else "已连接，当前无任务", "conversations")
    val running = users.count { it.status == "running" && !it.waiting }
    val queued = users.count { it.status == "queued" && !it.waiting }
    val waiting = users.count { it.waiting }
    val text = listOfNotNull(
        running.takeIf { it > 0 }?.let { "$it 个任务执行中" },
        queued.takeIf { it > 0 }?.let { "$it 个任务排队" },
        waiting.takeIf { it > 0 }?.let { "$it 个任务等待你的回复或授权" },
        "后台整理中".takeIf { maintenance }
    ).joinToString(" · ")
    return TaskNotification(text, users.singleOrNull()?.ownerConversationId?.let { "chat/$it" } ?: "conversations")
}
