package io.github.easylsp.app

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.platform.app.InstrumentationRegistry
import io.github.easylsp.app.data.*
import io.github.easylsp.app.feature.chat.ConversationScreen
import io.github.easylsp.app.ui.theme.EasylspTheme
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import java.io.File
import java.util.UUID

/** Captures the actual conversation composable with a 1,000-message fixture. */
class ConversationUiDeviceTest {
    private val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(VisibleActivityRule()).around(compose)

    @Test fun captureHistoryThemesQuestionsAndKeyboard() {
        val graph = compose.activity.application as EasylspApp
        val dao = graph.store.workflows
        val id = "ui-${UUID.randomUUID()}"
        val project = runBlocking(Dispatchers.IO) {
            graph.store.select("io.github.easylsp.demo", "Easylsp Demo", "1")
        }
        runBlocking(Dispatchers.IO) {
            graph.chat.recovered.await()
            val provider = dao.observeProviders().first().first()
            dao.insertConversation(ConversationEntity(id, project.id, provider.id, "界面验收样例", "第三阶段界面验收", project.sampleRevision, System.currentTimeMillis()))
            graph.store.transaction {
                repeat(996) { index -> dao.insertMessage(MessageEntity(conversationId = id, role = if (index % 2 == 0) "user" else "assistant", content = "历史条目 ${index + 1}：保留查询结果与插件修改记录。", sampleRevision = project.sampleRevision, sessionId = null, createdAt = index.toLong())) }
                val user = dao.insertMessage(MessageEntity(conversationId = id, role = "user", content = "查询真实方法，并说明如何验证插件效果。", sampleRevision = project.sampleRevision, sessionId = null, createdAt = 1000))
                dao.insertMessage(MessageEntity(conversationId = id, role = "assistant", content = "已找到真实签名，正在读取工具结果。", blocks = JSONObject().put("tool_calls", JSONArray().put(JSONObject().put("id", "fixture-read").put("function", JSONObject().put("name", "find_method").put("arguments", "{\"query\":\"add\"}")))).toString(), sampleRevision = project.sampleRevision, sessionId = null, createdAt = 1001))
                dao.saveToolRun(ToolRunEntity("$id-read", id, "fixture-read", "find_method", "{\"query\":\"add\"}", "{\"signature\":\"Lfixture/Calculator;->add(II)I\"}", "COMPLETE", 1001, 1002))
                dao.insertMessage(MessageEntity(conversationId = id, role = "tool", content = "真实签名已返回", blocks = "{\"tool_call_id\":\"fixture-read\"}", sampleRevision = project.sampleRevision, sessionId = null, createdAt = 1002))
                dao.insertMessage(MessageEntity(conversationId = id, role = "assistant", content = "已保存修改，接下来加载插件并读取实际结果。\n\n| 项目 | 状态 |\n| --- | --- |\n| 方法签名 | 已确认 |\n| 目标结果 | 等待验证 |\n\n```java\nint result = calculator.add(1, 2);\nassertEquals(42, result);\n```\n\n[插件 API 资料](https://github.com/Xposed-Modules-Repo)", blocks = "{\"reasoning\":\"先确认真实签名，再根据实际结果验证；此段为界面测试样例。\"}", sampleRevision = project.sampleRevision, sessionId = null, createdAt = 1003))
                dao.saveInteraction(ChatInteractionEntity("$id-question", id, "$id-turn", "QUESTION", "{\"question\":\"接下来验证哪一种输入？\",\"options\":[\"正数加法\",\"负数加法\"]}", createdAt = 1004))
                dao.saveTurn(ChatTurnEntity("$id-turn", id, user, status = "LIMIT_REACHED", phase = "MODEL", errorSource = "LIMIT", errorType = "RequestLimit", errorDetail = "已达到配置的请求上限，可继续已完成的工作。", requestCount = 20, startedAt = 1000, finishedAt = 1005))
            }
        }
        var appearance by mutableStateOf("light")
        var fontScale by mutableFloatStateOf(1f)
        try {
            compose.activity.runOnUiThread {
                compose.activity.setContent {
                    val density = LocalDensity.current
                    CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                        EasylspTheme(appearance) { Surface(Modifier.fillMaxSize()) {
                            Box(Modifier.systemBarsPadding()) { ConversationScreen(project, id, null, { _, _, _ -> }, {}) }
                        } }
                    }
                }
            }
            compose.waitUntil(10000) { compose.onAllNodesWithText("提交回答").fetchSemanticsNodes().isNotEmpty() }
            capture(graph, "conversation-light.png")
            compose.runOnIdle { appearance = "dark" }
            compose.waitForIdle(); capture(graph, "conversation-dark.png")
            compose.onNode(hasScrollToIndexAction()).performScrollToIndex(5)
            compose.waitForIdle(); capture(graph, "conversation-history-dark.png")
            compose.onNode(hasScrollToIndexAction()).performScrollToIndex(2)
            compose.waitForIdle(); capture(graph, "conversation-code-dark.png")
            compose.onNodeWithText("回到底部").performClick()
            compose.runOnIdle { fontScale = 1.3f }
            compose.waitForIdle(); capture(graph, "conversation-large-font.png")
            compose.onAllNodes(hasSetTextAction()).onLast().performClick().performTextInput("中文输入、选择与草稿测试")
            compose.waitForIdle(); capture(graph, "conversation-keyboard.png")
        } finally {
            compose.activity.runOnUiThread { compose.activity.setContent {} }
            compose.waitForIdle()
            runBlocking(Dispatchers.IO) { graph.store.transaction {
                dao.deleteMessages(id); dao.deleteToolRuns(id); dao.deleteTurns(id); dao.deleteInputs(id); dao.deleteInteractions(id); dao.deleteDraft(id); dao.deleteReading(id); dao.deleteConversation(project.id, id)
            } }
        }
    }
    private fun capture(graph: EasylspApp, name: String) {
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(graph.filesDir, "phase3-ui/$name").apply { parentFile!!.mkdirs() }.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
