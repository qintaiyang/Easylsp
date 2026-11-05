package io.github.easylsp.app

import android.graphics.Rect
import android.os.Handler
import android.os.HandlerThread
import android.view.*
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.TextView
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.github.easylsp.app.data.*
import io.github.easylsp.app.feature.chat.ConversationScreen
import io.github.easylsp.app.platform.harness.HarnessSessionClient
import io.github.easylsp.app.ui.theme.EasylspTheme
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicInteger

/** Uses the real Compose/Choreographer clock, with no Compose test clock or model call. */
class ConversationPerformanceDeviceTest {
    @get:Rule val visible = VisibleActivityRule()
    @Test fun realClockStreamFramesAndChineseComposition() = runBlocking(Dispatchers.IO) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val graph = instrumentation.targetContext.applicationContext as EasylspApp
        val dao = graph.store.workflows
        val activity = ActivityScenario.launch(MainActivity::class.java)
        val id = "performance-${UUID.randomUUID()}"
        val project = graph.store.select("io.github.easylsp.demo", "Easylsp Demo", "1")
        graph.chat.recovered.await()
        val provider = dao.observeProviders().first().first()
        dao.insertConversation(ConversationEntity(id, project.id, provider.id, "UI fixture", "真实时钟 UI 验收", project.sampleRevision, System.currentTimeMillis()))
        graph.store.transaction {
            repeat(1000) { index -> dao.insertMessage(MessageEntity(conversationId = id, role = if (index % 2 == 0) "user" else "assistant",
                content = "历史 $index：查询真实签名后验证插件。\n\n这是长历史阅读与输入的设备样本。", sampleRevision = project.sampleRevision, sessionId = null, createdAt = index.toLong())) }
        }
        var page: MainActivity? = null
        activity.onActivity { page = it; it.setContent { EasylspTheme("light") { Surface(Modifier.fillMaxSize()) {
            Box(Modifier.systemBarsPadding()) { ConversationScreen(project, id, null, { _, _, _ -> }, {}) }
        } } } }
        val screen = requireNotNull(page)
        val decor = screen.window.decorView
        fun nodes(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> = buildList {
            add(root); repeat(root.childCount) { root.getChild(it)?.let { addAll(nodes(it)) } }
        }
        suspend fun awaitCondition(predicate: () -> Boolean) { withTimeout(10000) { while (!predicate()) delay(20) } }
        val pending = AtomicReference<Pair<String, Long>?>(null)
        val latencies = ConcurrentLinkedQueue<Double>()
        fun containsText(view: View, text: String): Boolean {
            if (view is TextView && view.text.toString().contains(text) && view.getGlobalVisibleRect(Rect())) return true
            return view is ViewGroup && (0 until view.childCount).any { containsText(view.getChildAt(it), text) }
        }
        val draw = ViewTreeObserver.OnDrawListener {
            pending.get()?.let { expected -> if (containsText(decor, expected.first) && pending.compareAndSet(expected, null)) latencies.add((System.nanoTime() - expected.second) / 1_000_000.0) }
        }
        val frameThread = HandlerThread("phase3-frame-metrics").apply { start() }
        val framePhase = AtomicReference("stream")
        val frames = ConcurrentLinkedQueue<Pair<String, Long>>()
        val droppedMetrics = AtomicInteger()
        val frameListener = Window.OnFrameMetricsAvailableListener { _, metrics, dropped ->
            frames.add(framePhase.get() to metrics.getMetric(FrameMetrics.TOTAL_DURATION))
            droppedMetrics.addAndGet(dropped)
        }
        var metricsAttached = false
        var refresh = 0f
        try {
            awaitCondition { instrumentation.uiAutomation.rootInActiveWindow?.let { root -> nodes(root).any { it.isEditable } } == true }
            withContext(Dispatchers.Main) {
                val mode = screen.display!!.supportedModes.first { kotlin.math.abs(it.refreshRate - 60f) < 1f }
                screen.window.attributes = screen.window.attributes.apply { preferredDisplayModeId = mode.modeId; preferredRefreshRate = 60f }
            }
            delay(500)
            withContext(Dispatchers.Main) { refresh = screen.display!!.refreshRate }
            val editor = nodes(instrumentation.uiAutomation.rootInActiveWindow).first { it.isEditable }
            assertTrue(editor.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            delay(300)
            val connection = withContext(Dispatchers.Main) { requireNotNull(screen.currentFocus!!.onCreateInputConnection(EditorInfo())) }
            withContext(Dispatchers.Main) { assertTrue(connection.setComposingText("zhong", 1)) }
            awaitCondition { dao.draft(id)?.text == "zhong" }
            withContext(Dispatchers.Main) { assertTrue(connection.setComposingText("中文", 1)) }
            awaitCondition { dao.draft(id)?.text == "中文" }
            withContext(Dispatchers.Main) { assertTrue(connection.commitText("中文输入", 1)); connection.finishComposingText() }
            awaitCondition { dao.draft(id)?.text == "中文输入" }
            withContext(Dispatchers.Main) { assertTrue(connection.setSelection(0, 2)); assertTrue(connection.commitText("测试", 1)) }
            awaitCondition { dao.draft(id)?.text == "测试输入" }
            withContext(Dispatchers.Main) {
                screen.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(decor.windowToken, 0)
                graph.chat.statusConversation.value = id
            }
            delay(500)
            withContext(Dispatchers.Main) {
                decor.viewTreeObserver.addOnDrawListener(draw)
                screen.window.addOnFrameMetricsAvailableListener(frameListener, Handler(frameThread.looper))
                metricsAttached = true
            }
            repeat(20) { index ->
                val text = "真实时钟流式样本 $index"
                withContext(Dispatchers.Main) {
                    pending.set(text to System.nanoTime())
                    graph.chat.liveAssistant.value = HarnessSessionClient.Assistant("ui-latency", 1, 1, text)
                }
                awaitCondition { pending.get() == null }
                delay(40)
            }
            framePhase.set("scroll")
            repeat(8) { index ->
                nodes(instrumentation.uiAutomation.rootInActiveWindow).first { it.isScrollable }.performAction(
                    if (index < 4) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
                delay(350)
            }
            withContext(Dispatchers.Main) {
                screen.window.removeOnFrameMetricsAvailableListener(frameListener)
                metricsAttached = false
                decor.viewTreeObserver.removeOnDrawListener(draw)
            }
            val times = latencies.sorted()
            val frameBudget = 1_000_000_000.0 / refresh
            val slow = frames.count { it.second > frameBudget }
            fun frameSummary(samples: List<Long>): JSONObject {
                val sorted = samples.sorted()
                return JSONObject().put("frames", sorted.size).put("slowFrames", sorted.count { it > frameBudget })
                    .put("medianMillis", sorted[(sorted.size - 1) / 2] / 1_000_000.0)
                    .put("p95Millis", sorted[kotlin.math.ceil(sorted.size * 0.95).toInt() - 1] / 1_000_000.0)
            }
            assertEquals(20, times.size); assertTrue(frames.isNotEmpty())
            File(graph.filesDir, "phase3-ui-metrics.json").writeText(JSONObject().put("historyMessages", 1000).put("initialMessageWindow", 80)
                .put("chineseComposingAndCommit", true).put("selectionReplacement", true).put("persistedDraft", "测试输入")
                .put("requestedRefreshRateHz", 60).put("observedRefreshRateHz", refresh).put("buildType", "debug")
                .put("streamToVisibleDrawSamples", 20).put("streamToVisibleDrawMedianMillis", times[9]).put("streamToVisibleDrawP95Millis", times[18])
                .put("measuredFrames", frames.size).put("slowFrames", slow).put("slowFramePercent", slow * 100.0 / frames.size)
                .put("droppedFrameMetrics", droppedMetrics.get())
                .put("streamFrames", frameSummary(frames.filter { it.first == "stream" }.map { it.second }))
                .put("scrollFrames", frameSummary(frames.filter { it.first == "scroll" }.map { it.second }))
                .put("measurement", "Real clock; Harness live state to visible Markdown TextView OnDraw. FrameMetrics TOTAL_DURATION during 20 stream updates and 8 accessibility scrolls, excluding screenshot capture and setup.")
                .toString(2))
        } finally {
            withContext(Dispatchers.Main) {
                graph.chat.liveAssistant.value = null
                decor.viewTreeObserver.removeOnDrawListener(draw)
                if (metricsAttached) screen.window.removeOnFrameMetricsAvailableListener(frameListener)
            }
            frameThread.quitSafely(); activity.close()
            graph.store.transaction { dao.deleteMessages(id); dao.deleteDraft(id); dao.deleteReading(id); dao.deleteConversation(project.id, id) }
        }
    }
}
