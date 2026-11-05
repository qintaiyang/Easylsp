package io.github.easylsp.app

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.platform.app.InstrumentationRegistry
import io.github.easylsp.app.data.*
import io.github.easylsp.app.feature.chat.ConversationScreen
import io.github.easylsp.app.platform.harness.HarnessSessionClient
import io.github.easylsp.app.ui.theme.EasylspTheme
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import java.io.File
import java.util.UUID

class Phase4ConversationUiDeviceTest {
    private val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(VisibleActivityRule()).around(compose)
    @Test fun recoveredDouyinReadsAndExpandsWithoutNavigationOrDispatch() {
        val graph = compose.activity.application as EasylspApp
        val dao = graph.store.workflows; val id = "e4d9223f-7dee-4a57-9c3d-16680a02a7d1"
        val project = runBlocking(Dispatchers.IO) { requireNotNull(graph.store.dao.projectByPackage("com.ss.android.ugc.aweme")) }
        val before = runBlocking(Dispatchers.IO) { dao.history(id).map { it.engineMessageId } to dao.turnHistory(id).sumOf { it.requestCount } }
        var navigation = 0
        compose.activity.runOnUiThread { compose.activity.setContent {
            EasylspTheme("light") { Surface(Modifier.fillMaxSize()) { Box(Modifier.systemBarsPadding().imePadding()) { ConversationScreen(project, id, null, { _, _, _ -> navigation++ }, { navigation++ }, back = {}) } } }
        } }
        compose.waitForIdle()
        compose.waitUntil(20000) { compose.onAllNodesWithText("本次模型请求超时，可继续已有工作", substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(0)
        capture(graph, "douyin-after.png")
        val group = hasText("项操作", substring = true)
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(group)
        compose.onAllNodes(group).onFirst().performClick()
        capture(graph, "douyin-tools-expanded.png")
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("读取 View 树", substring = true))
        compose.onAllNodesWithText("读取 View 树", substring = true).onLast().performClick()
        compose.onAllNodesWithText("复制完整结果").onFirst().assertExists()
        capture(graph, "douyin-result-inline.png")
        assertEquals(0, navigation)
        val after = runBlocking(Dispatchers.IO) { dao.history(id).map { it.engineMessageId } to dao.turnHistory(id).sumOf { it.requestCount } }
        assertEquals(before, after)
    }
    @Test fun compactBubblesCodeModelsDraftAndThemes() {
        val graph = compose.activity.application as EasylspApp; val dao = graph.store.workflows
        val id = UUID.randomUUID().toString(); val provider = UUID.randomUUID().toString()
        val project = runBlocking(Dispatchers.IO) { graph.store.select("io.github.easylsp.demo", "Easylsp Demo", "1") }
        runBlocking(Dispatchers.IO) {
            // Earlier Activity-destruction failures left these known fixture configurations behind.
            val fixtures = dao.observeProviders().first().filter { it.name == "界面样例" && it.model == "phase4-ui" && it.baseUrl.isEmpty() }.map { it.id }.toSet()
            dao.conversations(project.id).first().filter { it.providerId in fixtures }.forEach { row ->
                dao.deleteMessages(row.id); dao.deleteTurns(row.id); dao.deleteDraft(row.id); dao.deleteReading(row.id); dao.deleteConversation(project.id, row.id)
            }
            fixtures.forEach { dao.deleteProvider(it) }
            dao.saveProvider(ProviderEntity(provider, "界面样例", "openai", "", "phase4-ui"))
            dao.insertConversation(ConversationEntity(id, project.id, provider, "phase4-ui", "界面样例", project.sampleRevision, System.currentTimeMillis()))
            repeat(100) { index -> dao.insertMessage(MessageEntity(conversationId = id, role = "assistant", content = "历史阅读样本 $index", sampleRevision = project.sampleRevision, sessionId = null)) }
            dao.insertMessage(MessageEntity(conversationId = id, role = "user", content = "帮我迁移插件", sampleRevision = project.sampleRevision, sessionId = null))
            dao.insertMessage(MessageEntity(conversationId = id, role = "assistant", content = "已保存迁移版，加载和功能检查仍待继续。\n\n```java\n" + (1..28).joinToString("\n") { "int value$it = $it;" } + "\n```", sampleRevision = project.sampleRevision, sessionId = null))
            dao.saveDraft(ChatDraftEntity(id, "恢复的草稿", "[{\"type\":\"text\",\"text\":\"附件 first.txt\\n第一份内容\"},{\"type\":\"text\",\"text\":\"附件 second.txt\\n第二份内容\"}]"))
        }
        var theme by mutableStateOf("light"); var fontScale by mutableFloatStateOf(1f); var navigation = 0
        fun render() { compose.activity.runOnUiThread { compose.activity.setContent {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                    EasylspTheme(theme) { Surface(Modifier.fillMaxSize()) { Box(Modifier.systemBarsPadding().imePadding()) { ConversationScreen(project, id, null, { _, _, _ -> navigation++ }, {}) } } }
                }
            } }; compose.waitForIdle() }
        val previousStatusConversation = graph.chat.statusConversation.value
        try {
            render()
            compose.waitUntil(10000) { compose.onAllNodesWithText("phase4-ui ▾").fetchSemanticsNodes().isNotEmpty() }
            compose.onNode(hasSetTextAction()).assertTextContains("恢复的草稿")
            capture(graph, "composer-attachments.png")
            compose.onAllNodesWithText("移除").onFirst().performClick()
            compose.onNodeWithText("附件 first.txt").assertDoesNotExist()
            compose.onNodeWithText("附件 second.txt").assertExists()
            compose.onNodeWithText("移除").performClick()
            compose.onNodeWithText("phase4-ui ▾").performClick()
            compose.onNodeWithText("选择模型").assertExists(); capture(graph, "model-picker.png")
            InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
            compose.waitForIdle()
            compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("显示更多（28 行）"))
            compose.onNodeWithText("显示更多（28 行）").performClick()
            compose.onNodeWithText("收起代码").assertExists(); assertEquals(0, navigation)
            compose.onNodeWithText("收起代码").performClick()
            compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("帮我迁移插件"))
            capture(graph, "conversation-light.png")
            val previousReading = runBlocking(Dispatchers.IO) { dao.reading(id) }
            compose.onNode(hasScrollToIndexAction()).performScrollToIndex(57)
            capture(graph, "history-before-stream.png")
            assertTrue(visibleInWindow("历史阅读样本 40"))
            compose.waitUntil(5000) { runBlocking(Dispatchers.IO) { dao.reading(id)?.let { it != previousReading } == true } }
            compose.runOnIdle {
                graph.chat.statusConversation.value = id
                graph.chat.liveAssistant.value = HarnessSessionClient.Assistant("phase4-reading", 1, 1, "新增流式正文")
            }
            capture(graph, "history-during-stream.png")
            assertTrue(visibleInWindow("历史阅读样本 40"))
            compose.runOnIdle { graph.chat.liveAssistant.value = graph.chat.liveAssistant.value!!.copy(text = "新增流式正文与后续内容") }
            assertTrue(visibleInWindow("历史阅读样本 40"))
            compose.runOnIdle { graph.chat.liveAssistant.value = null }
            compose.activity.runOnUiThread { compose.activity.setContent {} }; compose.waitForIdle()
            render()
            compose.waitUntil(10000) { visibleInWindow("历史阅读样本 40") }
            compose.onNode(hasSetTextAction()).assertTextContains("恢复的草稿")
            compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("帮我迁移插件"))
            compose.onNodeWithText("帮我迁移插件").performTouchInput { longClick() }
            compose.onNodeWithText("编辑再发").performClick()
            compose.onNode(hasSetTextAction()).assertTextContains("帮我迁移插件")
            compose.onNode(hasSetTextAction()).performClick().performTextClearance()
            compose.runOnIdle {
                val connection = requireNotNull(compose.activity.currentFocus?.onCreateInputConnection(android.view.inputmethod.EditorInfo()))
                connection.setComposingText("zhong", 1)
            }
            compose.onNode(hasSetTextAction()).assertTextContains("zhong")
            compose.runOnIdle {
                val connection = requireNotNull(compose.activity.currentFocus?.onCreateInputConnection(android.view.inputmethod.EditorInfo()))
                connection.commitText("中文", 1)
            }
            compose.onNode(hasSetTextAction()).assertTextContains("中文")
            compose.runOnIdle {
                val clipboard = graph.getSystemService(android.content.ClipboardManager::class.java)
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("phase4", "粘贴内容"))
                val connection = requireNotNull(compose.activity.currentFocus?.onCreateInputConnection(android.view.inputmethod.EditorInfo()))
                connection.setSelection(0, 2)
            }
            compose.waitForIdle()
            compose.runOnIdle {
                val connection = requireNotNull(compose.activity.currentFocus?.onCreateInputConnection(android.view.inputmethod.EditorInfo()))
                connection.performContextMenuAction(android.R.id.paste)
            }
            compose.onNode(hasSetTextAction()).assertTextContains("粘贴内容")
            compose.onNode(hasSetTextAction()).performTextReplacement("中文草稿测试")
            compose.waitUntil(5000) { runBlocking(Dispatchers.IO) { dao.draft(id)?.text == "中文草稿测试" } }
            capture(graph, "composer-keyboard.png")
            compose.onNode(hasSetTextAction()).performTextClearance()
            InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
            compose.waitUntil(5000) { !compose.activity.window.decorView.rootWindowInsets.isVisible(android.view.WindowInsets.Type.ime()) }
            compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("帮我迁移插件"))
            compose.runOnIdle { theme = "dark" }; capture(graph, "conversation-dark.png")
            compose.runOnIdle { fontScale = 1.3f }; capture(graph, "conversation-large-font.png")
            val raw = "session \"a4d4c081-f27f-4dd8-9cf4-af00ae8963f6\" contains event type \"easylsp/limit\" (seq 236) unknown to this harness and not marked ignorable; refusing to interpret the log — it was likely written by a newer harness (raw log: /var/lib/easylsp-harness/a4d4c081-f27f-4dd8-9cf4-af00ae8963f6/sessions/--var-lib-easylsp-harness-a4d4c081-f27f-4dd8-9cf4-af00ae8963f6--/a4d4c081-f27f-4dd8-9cf4-af00ae8963f6/session.v4.jsonl.zstd)"
            compose.runOnIdle { theme = "light"; fontScale = 1f }
            runBlocking(Dispatchers.IO) {
                val user = dao.history(id).single { it.role == "user" }
                dao.saveTurn(ChatTurnEntity("$id-error", id, user.id, "ERROR", errorSource = "HARNESS", errorDetail = raw, finishedAt = System.currentTimeMillis()))
            }
            compose.onNode(hasScrollToIndexAction()).performScrollToIndex(0)
            compose.waitUntil(5000) { compose.onAllNodesWithText("会话历史加载失败，已有内容仍保留", substring = true).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText(raw, substring = true).assertDoesNotExist()
            capture(graph, "recovery-error-collapsed.png")
            compose.onNodeWithText("详情").performClick()
            compose.onNodeWithText(raw, substring = true).assertExists()
            compose.onNodeWithText("复制详情").performClick()
            assertTrue(graph.getSystemService(android.content.ClipboardManager::class.java).primaryClip!!.getItemAt(0).text.toString().contains(raw))
            capture(graph, "recovery-error-expanded.png")
            compose.onNodeWithText("收起").performClick()
            compose.onNode(hasSetTextAction()).performTextReplacement("第一行\n第二行\n第三行\n第四行\n第五行\n第六行")
            capture(graph, "composer-multiline.png")
        } finally {
            graph.chat.liveAssistant.value = null
            graph.chat.statusConversation.value = previousStatusConversation
            compose.activity.runOnUiThread { compose.activity.setContent {} }; compose.waitForIdle()
            runBlocking(Dispatchers.IO) { dao.deleteMessages(id); dao.deleteTurns(id); dao.deleteDraft(id); dao.deleteReading(id); dao.deleteConversation(project.id, id); dao.deleteProvider(provider) }
        }
    }
    // Markdown is rendered by Android TextView; query the actual window accessibility tree.
    private fun visibleInWindow(text: String): Boolean = InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow
        ?.findAccessibilityNodeInfosByText(text).orEmpty().any { it.isVisibleToUser }
    private fun capture(graph: EasylspApp, name: String) {
        compose.waitForIdle()
        // Whole-device capture also includes IME/window animations presented by the compositor.
        android.os.SystemClock.sleep(250)
        val image = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        File(graph.filesDir, "phase4-ui/$name").apply { parentFile!!.mkdirs() }.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }; image.recycle()
    }
}
