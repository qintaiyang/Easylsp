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
import androidx.compose.ui.unit.dp
import io.github.easylsp.app.data.*
import io.github.easylsp.app.feature.chat.ConversationScreen
import io.github.easylsp.app.platform.harness.HarnessSessionClient
import io.github.easylsp.app.ui.theme.EasylspTheme
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import java.io.File
import java.util.UUID

class Phase5ConversationUiDeviceTest {
    private val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(VisibleActivityRule()).around(compose)
    private fun capture(graph: EasylspApp, name: String) {
        compose.waitForIdle()
        Thread.sleep(350) // Dialog/IME windows animate outside Compose's test clock.
        val bitmap = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(graph.filesDir, "phase5-ui").mkdirs()
        File(graph.filesDir, "phase5-ui/$name").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
    }
    private fun readingAnchor(expected: String? = null): Pair<String, Int> {
        var found: Pair<String, Int>? = null
        fun visit(view: android.view.View) {
            if (view is android.widget.TextView && view.text.startsWith("阅读锚点") && (expected == null || view.text.toString() == expected)) {
                val location = IntArray(2); view.getLocationOnScreen(location)
                if (location[1] >= 0 && view.getGlobalVisibleRect(android.graphics.Rect())) found = view.text.toString() to location[1]
            }
            if (view is android.view.ViewGroup) for (i in 0 until view.childCount) visit(view.getChildAt(i))
        }
        visit(compose.activity.window.decorView)
        return requireNotNull(found) { "屏幕上应有可见的阅读锚点" }
    }
    @Test fun tablesStreamingReadingUsageEditAndWindowChanges() {
        val graph = compose.activity.application as EasylspApp; val dao = graph.store.workflows
        val project = runBlocking(Dispatchers.IO) { graph.store.select("io.github.easylsp.demo", "Easylsp Demo", "1") }
        val id = UUID.randomUUID().toString(); val p = UUID.randomUUID().toString(); val engine = UUID.randomUUID().toString()
        val fixture = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().context.assets.open("phase5/rendering.md").bufferedReader().use { it.readText() }
        runBlocking(Dispatchers.IO) {
            dao.saveProvider(ProviderEntity(p, "Phase5 UI", "openai", "http://127.0.0.1:1/v1", "phase5-fixture", options = "{\"contextWindow\":32768}"))
            dao.insertConversation(ConversationEntity(id, project.id, p, "phase5-fixture", "第五阶段界面验证", project.sampleRevision, System.currentTimeMillis(), authorizationMode = "ALLOW"))
            graph.secrets.put("provider:$p", "fixture"); dao.saveHarnessSession(HarnessSessionEntity(id, engine))
            repeat(120) { i -> dao.insertMessage(MessageEntity(conversationId = id, role = "assistant", content = "阅读锚点 $i", sampleRevision = project.sampleRevision, sessionId = null)) }
            val user = dao.insertMessage(MessageEntity(conversationId = id, role = "user", content = "核对表格和启动证据", blocks = "{\"attachments\":[{\"type\":\"text\",\"text\":\"附件 notes.txt\\n测试内容\"}]}", sampleRevision = project.sampleRevision, sessionId = null))
            dao.insertMessage(MessageEntity(conversationId = id, role = "assistant", content = fixture, sampleRevision = project.sampleRevision, sessionId = null, displayKey = "assistant:ui:9"))
            dao.saveTurn(ChatTurnEntity("$engine:1", id, user, "COMPLETE", requestCount = 35, startedAt = 1000, finishedAt = 573000))
            fun part(total: Long, i: Int) = total / 35 + if (i < total % 35) 1 else 0
            repeat(35) { i -> dao.saveUsage(ChatUsageEntity(engine, i.toLong(), id, "ui:$i", "$engine:1", i + 1, JSONObject().put("inputTokens", part(734569, i)).put("cacheReadTokens", part(3939584, i)).put("outputTokens", part(6826, i)).put("totalTokens", part(4680979, i)).toString())) }
        }
        var theme by mutableStateOf("light"); var fontScale by mutableFloatStateOf(1f); var narrow by mutableStateOf(false)
        var navigation = 0
        compose.activity.runOnUiThread { compose.activity.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                EasylspTheme(theme) { Surface(Modifier.fillMaxSize()) { Box(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
                    Box(if (narrow) Modifier.width(300.dp).fillMaxHeight() else Modifier.fillMaxSize()) {
                        ConversationScreen(project, id, null, { _, _, _ -> navigation++ }, {})
                    }
                } } }
            }
        } }
        val oldStatus = graph.chat.statusConversation.value
        try {
            compose.waitUntil(10000) { compose.onAllNodesWithText("phase5-fixture ▾").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("会话用量与设置").performClick()
            compose.onNodeWithText("会话用量与设置").assertExists(); compose.onAllNodesWithText("总计 4,680,979", substring = true).onFirst().assertExists(); capture(graph, "usage-popup.png")
            assertEquals(0, navigation)
            compose.onNodeWithText("关闭").performClick()
            compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("核对表格和启动证据"))
            compose.onNodeWithText("核对表格和启动证据").performTouchInput { longClick() }
            compose.onNodeWithText("编辑并从此处重发").performClick()
            compose.onNodeWithText("正在修改较早的消息").assertExists(); compose.onNodeWithText("将从这条消息重新继续；已执行的文件和插件操作不会撤销。").assertExists()
            compose.onNode(hasSetTextAction()).assertTextContains("核对表格和启动证据"); capture(graph, "edit-message.png")
            compose.onNode(hasSetTextAction()).performClick()
            compose.onNode(hasSetTextAction()).performTextInput(" · 修改草稿")
            capture(graph, "edit-keyboard.png")
            compose.runOnIdle { androidx.core.view.WindowCompat.getInsetsController(compose.activity.window, compose.activity.window.decorView).hide(androidx.core.view.WindowInsetsCompat.Type.ime()) }
            compose.onNodeWithText("取消编辑").performClick(); compose.onNodeWithText("正在修改较早的消息").assertDoesNotExist()
            compose.onNode(hasScrollToIndexAction()).performScrollToIndex(0)
            capture(graph, "table-light.png")
            compose.onAllNodes(SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.HorizontalScrollAxisRange)).onFirst().performTouchInput { swipeLeft() }
            capture(graph, "horizontal-scroll.png")
            compose.runOnIdle { theme = "dark" }; capture(graph, "table-dark.png")
            compose.runOnIdle { fontScale = 1.3f; narrow = true }; capture(graph, "table-narrow-large-font.png")
            compose.runOnIdle { fontScale = 1f; narrow = false; theme = "light" }
            compose.onNode(hasScrollToIndexAction()).performScrollToIndex(35)
            val anchorBefore = compose.runOnIdle { readingAnchor() }
            capture(graph, "reading-before-stream.png")
            compose.runOnIdle { graph.chat.statusConversation.value = id; graph.chat.liveAssistant.value = HarnessSessionClient.Assistant("ui-attempt", 2, 1, fixture.take(150), displayKey = "assistant:ui:99") }
            for (length in 200..fixture.length step 80) {
                compose.runOnIdle { graph.chat.liveAssistant.value = graph.chat.liveAssistant.value!!.copy(text = fixture.take(length)) }
                Thread.sleep(100)
            }
            compose.waitForIdle()
            val anchorAfter = compose.runOnIdle { readingAnchor(anchorBefore.first).second }
            assertEquals(anchorBefore.second, anchorAfter)
            capture(graph, "reading-after-stream.png")
            runBlocking(Dispatchers.IO) { dao.insertMessage(MessageEntity(conversationId = id, role = "assistant", content = fixture, sampleRevision = project.sampleRevision, sessionId = null, displayKey = "assistant:ui:99")) }
            compose.runOnIdle { graph.chat.liveAssistant.value = HarnessSessionClient.Assistant("visible-attempt", 3, 1, fixture.take(150), displayKey = "assistant:ui:100") }
            compose.onNode(hasScrollToIndexAction()).performScrollToIndex(0)
            for (length in 200..fixture.length step 80) {
                compose.runOnIdle { graph.chat.liveAssistant.value = graph.chat.liveAssistant.value!!.copy(text = fixture.take(length)) }
                Thread.sleep(100)
            }
            compose.runOnIdle { graph.chat.liveAssistant.value = graph.chat.liveAssistant.value!!.copy(text = fixture + "\n\n```text\n未闭合围栏\n└─ 最后块") }
            Thread.sleep(200); capture(graph, "streaming-table-code.png")
            runBlocking(Dispatchers.IO) { dao.insertMessage(MessageEntity(conversationId = id, role = "assistant", content = fixture + "\n\n```text\n未闭合围栏\n└─ 最后块", sampleRevision = project.sampleRevision, sessionId = null, displayKey = "assistant:ui:100")) }
            Thread.sleep(250); capture(graph, "stream-committed.png")
            assertEquals(1, compose.onAllNodesWithText("未闭合围栏", substring = true).fetchSemanticsNodes().size)
            File(graph.filesDir, "phase5-ui-evidence.json").writeText(JSONObject().put("anchorText", anchorBefore.first).put("anchorBeforeY", anchorBefore.second).put("anchorAfterY", anchorAfter).put("inlineUsageNavigation", navigation).put("stableCommittedIdentity", true).toString(2))
        } finally {
            compose.runOnIdle { graph.chat.liveAssistant.value = null; graph.chat.statusConversation.value = oldStatus }
            compose.activity.runOnUiThread { compose.activity.setContent {} }; compose.waitForIdle()
            runBlocking(Dispatchers.IO) { dao.deleteMessages(id); dao.deleteTurns(id); dao.deleteUsage(id); dao.deleteDraft(id); dao.deleteReading(id); dao.deleteConversation(project.id, id); dao.deleteProvider(p); graph.secrets.delete("provider:$p") }
        }
    }
}
