package io.github.easylsp.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.semantics.SemanticsActions
import io.github.easylsp.app.data.ProjectEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.util.UUID

/** Real navigation and archive/recovery, using only an isolated local project. */
class MarketplaceUiDeviceTest {
    @get:Rule(order = 0) val fixtureModel = FixtureModelRule()
    @get:Rule(order = 1) val visible = VisibleActivityRule()
    @get:Rule(order = 2) val ui = createAndroidComposeRule<MainActivity>()
    private val graph get() = ui.activity.application as EasylspApp
    private fun SemanticsNodeInteraction.activate() {
        var target = this
        while (!target.fetchSemanticsNode().config.contains(SemanticsActions.OnClick)) target = target.onParent()
        ui.waitUntil(10000) { isEnabled().matches(target.fetchSemanticsNode()) }
        target.assertIsEnabled()
        target.performSemanticsAction(SemanticsActions.OnClick) { it() }
    }
    @Test fun conversationLongPressArchiveRenameDeleteAndOldRoute() {
        val previous = runBlocking { graph.settings.projectFilter.first() }
        val row = runBlocking(Dispatchers.IO) { graph.conversations.create(modelId = fixtureModel.id, title = "会话长按验收") }
        try {
            ui.activityRule.scenario.onActivity { it.startActivity(android.content.Intent(it.intent).setFlags(android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("route", "conversations")) }
            ui.waitUntil(10000) { ui.onAllNodesWithText(row.title).fetchSemanticsNodes().isNotEmpty() }
            ui.onNodeWithText(row.title).performTouchInput { longClick() }
            ui.onNodeWithText("名称").performTextReplacement("已重命名验收")
            ui.onNodeWithText("保存").activate()
            ui.waitUntil(10000) { ui.onAllNodesWithText("已重命名验收").fetchSemanticsNodes().isNotEmpty() }
            ui.onNodeWithText("已重命名验收").performTouchInput { longClick() }
            ui.onNodeWithText("归档").activate()
            ui.waitUntil(10000) { ui.onAllNodesWithText("已重命名验收").fetchSemanticsNodes().isEmpty() }
            ui.onNodeWithText("已归档").activate()
            ui.waitUntil(10000) { ui.onAllNodesWithText("已重命名验收").fetchSemanticsNodes().isNotEmpty() }
            ui.onNodeWithText("已重命名验收").performTouchInput { longClick() }
            ui.onNodeWithText("取消归档").activate()
            ui.waitUntil(10000) { ui.onAllNodesWithText("已重命名验收").fetchSemanticsNodes().isEmpty() }
            ui.onNodeWithText("已归档").activate()
            ui.waitUntil(10000) { ui.onAllNodesWithText("已重命名验收").fetchSemanticsNodes().isNotEmpty() }
            ui.onNodeWithText("已重命名验收").performTouchInput { longClick() }
            ui.onNodeWithText("删除…").activate()
            ui.onNodeWithText("停止并删除").activate()
            ui.waitUntil(15000) { ui.onAllNodesWithText("已重命名验收").fetchSemanticsNodes().isEmpty() }
            runBlocking(Dispatchers.IO) { org.junit.Assert.assertNull(graph.store.workflows.conversation(row.id)) }
            ui.activityRule.scenario.onActivity { it.startActivity(android.content.Intent(it.intent).setFlags(android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("route", "chat/${row.id}")) }
            ui.waitUntil(10000) { ui.onAllNodesWithText("会话不存在或已删除").fetchSemanticsNodes().isNotEmpty() }
            ui.onNodeWithText("返回会话列表").activate()
            ui.waitUntil(10000) { ui.onAllNodesWithText("搜索会话标题或模型").fetchSemanticsNodes().isNotEmpty() }
        } finally {
            runBlocking(Dispatchers.IO) { graph.localManagement.deleteConversation(row.id); graph.settings.projectFilter(previous) }
        }
    }
    @Test fun statusStaticEntryChatCategoryArchiveRecoveryAndSourceLink() {
        val id = UUID.randomUUID().toString()
        val project = ProjectEntity(id, "fixture.ui.$id", "Marketplace UI fixture", "1", kind = "IMPORTED")
        val previous = runBlocking { graph.settings.projectFilter.first() }
        runBlocking(Dispatchers.IO) { graph.store.dao.insertProject(project); graph.settings.projectFilter("all") }
        try {
            ui.onNodeWithText("Java 模块与框架").assertIsDisplayed()
            ui.onNode(hasText("项目") and isSelectable()).activate()
            ui.waitUntil(10000) { ui.onAllNodesWithText(project.name).fetchSemanticsNodes().isNotEmpty() }
            ui.onAllNodesWithText("导入 APK（静态分析）").onLast().assertIsDisplayed()
            ui.onNodeWithText(project.name).performScrollTo().performTouchInput { longClick() }
            ui.waitUntil(10000) { ui.onAllNodes(hasText("归档") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            ui.onNodeWithText("归档").activate()
            ui.waitUntil(10000) { ui.onAllNodesWithText(project.name).fetchSemanticsNodes().isEmpty() }
            ui.onNodeWithText("已归档").activate()
            ui.onNodeWithText(project.name).performScrollTo().performTouchInput { longClick() }
            ui.waitUntil(10000) { ui.onAllNodes(hasText("取消归档") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            ui.onNodeWithText("取消归档").activate()
            ui.waitUntil(10000) { ui.onAllNodesWithText(project.name).fetchSemanticsNodes().isEmpty() }
            ui.onNodeWithText("已归档").activate()
            ui.waitUntil(10000) { ui.onAllNodesWithText(project.name).fetchSemanticsNodes().isNotEmpty() }
            ui.onNodeWithText(project.name).assertExists()
            ui.onNodeWithText("聊天会话").performScrollTo().activate()
            ui.waitUntil(10000) { ui.onNodeWithText("搜索会话标题或模型").isDisplayed() }
            ui.onNodeWithText("搜索会话标题或模型").assertIsDisplayed()
            ui.onNodeWithText("新建会话").assertIsDisplayed()
            ui.onNode(hasText("设置") and isSelectable()).activate()
            ui.waitUntil(10000) { ui.onNodeWithText("关于").isDisplayed() }
            ui.onNodeWithText("关于").activate()
            ui.waitUntil(10000) { ui.onNodeWithText("GitHub 开源仓库").isDisplayed() }
            ui.onNodeWithText("GitHub 开源仓库").assertIsDisplayed()
            assertEquals("https://github.com/qintaiyang/Easylsp", io.github.easylsp.app.platform.AppLinks.SOURCE)
        } finally {
            runBlocking(Dispatchers.IO) { graph.localManagement.deleteProject(id); graph.settings.projectFilter(previous) }
        }
    }
}
