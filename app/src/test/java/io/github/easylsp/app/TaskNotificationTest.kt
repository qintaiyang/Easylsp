package io.github.easylsp.app

import io.github.easylsp.app.platform.harness.*
import org.junit.Assert.*
import org.junit.Test

class TaskNotificationTest {
    @Test fun waitingAndBackgroundTasksHaveAccurateCountsAndRoutes() {
        val waiting = TaskActivity("archived-chat", "running", true)
        assertEquals(TaskNotification("1 个任务等待你的回复或授权", "chat/archived-chat"), taskNotification(true, listOf(waiting)))
        val multiple = taskNotification(true, listOf(waiting, TaskActivity("background", "running", false), TaskActivity("queued", "queued", false), TaskActivity("idle", "idle", false)))
        assertEquals("1 个任务执行中 · 1 个任务排队 · 1 个任务等待你的回复或授权", multiple.text)
        assertEquals("conversations", multiple.route)
    }
    @Test fun idleAndDisconnectedDoNotClaimExecution() {
        assertEquals(TaskNotification("已连接，当前无任务", "conversations"), taskNotification(true, listOf(TaskActivity("old", "idle", false))))
        assertEquals(TaskNotification("执行服务已断开，任务状态待确认", "conversations"), taskNotification(false, listOf(TaskActivity("unknown", "running", false))))
    }
    @Test fun childrenAggregateUnderTheirUserConversationAndRecoverFromWaiting() {
        val parent = TaskActivity("parent", "running", false)
        val child = TaskActivity("child", "running", true, "parent")
        assertEquals(TaskNotification("1 个任务等待你的回复或授权", "chat/parent"), taskNotification(true, listOf(parent, child)))
        assertEquals(TaskNotification("1 个任务执行中", "chat/parent"), taskNotification(true, listOf(parent, child.copy(waiting = false))))
        assertEquals(TaskNotification("1 个任务排队", "chat/parent"), taskNotification(true, listOf(parent.copy(status = "idle"), child.copy(status = "queued", waiting = false))))
    }
    @Test fun maintenanceIsVisibleWithoutCreatingAnotherUserTask() {
        val maintenance = TaskActivity("memory", "running", false, maintenance = true)
        assertEquals(TaskNotification("后台整理中", "conversations"), taskNotification(true, listOf(maintenance)))
        assertEquals(TaskNotification("1 个任务执行中 · 后台整理中", "chat/user"), taskNotification(true, listOf(maintenance, TaskActivity("user", "running", false))))
        assertEquals(TaskNotification("已连接，当前无任务", "conversations"), taskNotification(true, listOf(maintenance.copy(status = "idle"))))
    }
    @Test fun unmappedSessionsDoNotCollapseIntoOneOrReuseAConversationRoute() {
        assertEquals(TaskNotification("2 个任务执行中", "conversations"), taskNotification(true, listOf(TaskActivity(null, "running", false), TaskActivity(null, "running", false))))
    }
}
