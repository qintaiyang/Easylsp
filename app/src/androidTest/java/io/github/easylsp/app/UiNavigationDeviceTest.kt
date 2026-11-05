package io.github.easylsp.app

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.semantics.SemanticsActions
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.easylsp.app.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Runs against the real Activity and Android editor; only the reading fixture uses local messages. */
class UiNavigationDeviceTest {
    @get:Rule(order = 0) val visible = VisibleActivityRule()
    @get:Rule(order = 1) val ui = createAndroidComposeRule<MainActivity>()
    private val graph get() = ui.activity.application as EasylspApp
    @Before fun clearInterruptedReadingFixtures(): Unit = runBlocking(Dispatchers.IO) {
        val dao = graph.store.workflows
        val fixtures = dao.observeProviders().first().filter { it.name == "UI 阅读夹具" && it.model == "fixture-no-request" && it.baseUrl == "http://127.0.0.1:9/v1" }.map { it.id }.toSet()
        graph.store.transaction {
            dao.allConversations().filter { it.providerId in fixtures }.forEach {
                dao.deleteMessages(it.id); dao.deleteToolRuns(it.id); dao.clearPlan(it.id); if (it.projectId == null) dao.deleteGlobalConversation(it.id) else dao.deleteConversation(it.projectId, it.id)
            }
            fixtures.forEach(dao::deleteProvider)
        }
        graph.store.dao.projectByPackage("io.github.easylsp.demo")?.let { demo ->
            graph.store.dao.plugins(demo.id).filter { it.name == "UI 模板验收" && it.revision == 1L && graph.store.read(it.id) == "void onLoad() {\n    \n}\n" }.forEach { graph.store.delete(it.id) }
        }
    }
    private fun editor(view: View = ui.activity.window.decorView): CodeEditor? {
        if (view is CodeEditor) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) editor(view.getChildAt(i))?.let { return it }
        return null
    }
    private fun SemanticsNodeInteraction.activate() {
        var clickable = this
        while (!clickable.fetchSemanticsNode().config.contains(SemanticsActions.OnClick)) clickable = clickable.onParent()
        clickable.performSemanticsAction(SemanticsActions.OnClick) { it() }
    }
    private fun text(): String { var value = ""; ui.runOnUiThread { value = requireNotNull(editor()).text.toString() }; return value }
    private fun screenshot(name: String) {
        ui.waitForIdle()
        val window = ui.activity.window
        val image = Bitmap.createBitmap(window.decorView.width, window.decorView.height, Bitmap.Config.ARGB_8888)
        val done = CountDownLatch(1); var result = -1
        PixelCopy.request(window, image, { result = it; done.countDown() }, Handler(Looper.getMainLooper()))
        assertTrue(done.await(5, TimeUnit.SECONDS)); assertEquals(PixelCopy.SUCCESS, result)
        File(graph.filesDir, "phase2-screenshots/$name.png").apply { parentFile!!.mkdirs(); outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        image.recycle()
    }
    private fun project(): ProjectEntity = runBlocking { graph.store.select("io.github.easylsp.demo", "Easylsp Demo", "1") }
    private fun openProject(project: ProjectEntity) {
        runBlocking { graph.settings.projectFilter("all") }
        ui.onNode(hasText("项目") and isSelectable()).activate()
        ui.waitUntil(10000) { ui.onAllNodesWithText(project.name).fetchSemanticsNodes().isNotEmpty() }
        ui.onNode(hasText(project.name) and hasText("设备应用", substring = true)).performScrollTo().activate()
        ui.waitForIdle()
        if (ui.onAllNodesWithText("输入需求").fetchSemanticsNodes().isNotEmpty()) ui.onNodeWithContentDescription("返回").activate()
        screenshot("project-ai-default")
        ui.onNodeWithText("高级工具").activate()
        ui.onNodeWithText("插件").activate()
    }
    @Test fun navigationDraftImeUndoAndRecreation() {
        val project = project()
        val plugin = runBlocking { graph.store.create(project, "UI 草稿验收", "void onLoad() {\n    plugin.log(\"ready\");\n}\n", "Easylsp", "设备草稿验证") }
        try {
            runBlocking { graph.settings.appearance("light") }
            screenshot("home-light")
            openProject(project)
            screenshot("plugins-light")
            ui.onNodeWithText(plugin.name).performScrollTo().activate()
            ui.waitUntil(10000) { var found = false; ui.runOnUiThread { found = editor() != null }; found }
            val before = text()
            ui.runOnUiThread {
                val view = requireNotNull(editor()); view.requestFocus(); view.setSelection(view.text.lineCount - 1, 0)
                val ime = requireNotNull(view.onCreateInputConnection(EditorInfo()))
                ime.beginBatchEdit(); ime.setComposingText("// 中文草稿", 1); ime.commitText("// 中文草稿\n", 1); ime.endBatchEdit()
            }
            assertTrue(text().contains("中文草稿"))
            ui.onNodeWithContentDescription("撤销").activate()
            ui.waitUntil(10000) { text() == before }
            ui.onNodeWithContentDescription("重做").activate()
            ui.waitUntil(10000) { text().contains("中文草稿") }
            ui.onNodeWithContentDescription("返回").activate()
            ui.onNodeWithText("保留草稿").activate()
            ui.waitUntil(10000) { ui.onAllNodesWithText("新建插件").fetchSemanticsNodes().isNotEmpty() }
            ui.onNodeWithText(plugin.name).performScrollTo().activate()
            ui.waitUntil(10000) { var restored = false; ui.runOnUiThread { restored = editor()?.text?.contains("中文草稿") == true }; restored }
            ui.activityRule.scenario.recreate()
            ui.waitUntil(10000) { var restored = false; ui.runOnUiThread { restored = editor()?.text?.contains("中文草稿") == true }; restored }
            screenshot("editor-light")
            ui.onNodeWithText("保存").activate()
            ui.waitUntil(10000) { runBlocking(Dispatchers.IO) { graph.store.dao.plugin(plugin.id)!!.revision > 1 } }
            assertEquals(2L, runBlocking(Dispatchers.IO) { graph.store.dao.plugin(plugin.id)!!.revision })
            assertTrue(runBlocking { graph.store.read(plugin.id) }.contains("中文草稿"))
        } finally { runBlocking { graph.store.delete(plugin.id) } }
    }
    @Test fun themesAndMarkdownToolOutputNavigation() {
        val project = project()
        val id = UUID.randomUUID().toString()
        val dao = graph.store.workflows
        val previousAppearance = runBlocking { graph.settings.appearance.first() }
        runBlocking(Dispatchers.IO) {
            dao.saveProvider(ProviderEntity(id, "UI 阅读夹具", "openai", "http://127.0.0.1:9/v1", "fixture-no-request"))
            dao.insertConversation(ConversationEntity(id, project.id, id, "fixture-no-request", "Markdown 阅读验收", project.sampleRevision, System.currentTimeMillis()))
            dao.insertMessage(MessageEntity(conversationId = id, role = "user", content = "展示代码、表格与工具过程（本地阅读夹具）", sampleRevision = project.sampleRevision, sessionId = null))
            dao.insertMessage(MessageEntity(conversationId = id, role = "assistant", content = "# 方法分析\n\n- 查询真实签名\n- 编辑后显式重载\n\n| 输入 | 结果 |\n| --- | --- |\n| 2 + 3 | 5 |\n\n```java\nvoid onLoad() {\n    plugin.log(\"中文输出\");\n}\n```\n\n[插件文档](https://github.com/)", blocks = "{\"tool_calls\":[{\"id\":\"fixture-call\",\"function\":{\"name\":\"find_method\",\"arguments\":\"{\\\"query\\\":\\\"Calculator.add\\\"}\"}}]}", sampleRevision = project.sampleRevision, sessionId = null, usage = "{\"prompt_tokens\":120,\"completion_tokens\":60,\"total_tokens\":180}"))
            dao.saveToolRun(ToolRunEntity(id, id, "fixture-call", "find_method", "{}", "{\"signature\":\"Lio/github/easylsp/demo/Calculator;->add(II)I\"}", "COMPLETE"))
        }
        try {
            runBlocking { graph.settings.appearance("light") }
            ui.onNodeWithText("设置").activate(); screenshot("settings-light")
            ui.onNodeWithText("外观").activate(); ui.onNodeWithText("深色").activate()
            ui.activityRule.scenario.recreate()
            ui.onNodeWithText("深色").assertIsDisplayed(); assertEquals("dark", runBlocking { graph.settings.appearance.first() }); screenshot("appearance-dark")
            ui.onNodeWithContentDescription("返回").activate()
            ui.onNodeWithText("首页").activate(); screenshot("home-dark")
            openProject(project)
            ui.onNodeWithText("聊天").activate()
            ui.onNodeWithText("Markdown 阅读验收").performScrollTo().activate()
            ui.onNodeWithText("java").performScrollTo().assertExists()
            screenshot("chat-dark")
            ui.onNodeWithText("展开工作过程 · 1 次工具调用").performScrollTo().activate()
            ui.onNodeWithText("查看完整参数与结果").performScrollTo().activate()
            ui.waitUntil(10000) { ui.onAllNodesWithText("查找内容").fetchSemanticsNodes().isNotEmpty() }
            ui.onNodeWithText("查找内容").assertIsDisplayed(); screenshot("tool-output-dark")
            ui.onNodeWithContentDescription("返回").activate()
            ui.onNodeWithText("展开工作过程 · 1 次工具调用").assertDoesNotExist()
        } finally {
            runBlocking(Dispatchers.IO) { graph.store.transaction { dao.deleteMessages(id); dao.deleteToolRuns(id); dao.clearPlan(id); dao.deleteConversation(project.id, id); dao.deleteProvider(id) }; graph.settings.appearance(previousAppearance) }
        }
    }
    @Test fun realStaticAnalysisTemplateAndImportedProjectNavigation() {
        var project = project()
        val plugin = runBlocking { graph.store.create(project, "UI 模板验收", "void onLoad() {\n    \n}\n") }
        try {
            project = runBlocking { if (project.sampleRevision == 0L) graph.samples.capture(project) else project }
            runBlocking { graph.settings.appearance("light") }
            openProject(project)
            ui.onNodeWithText("分析").activate()
            ui.onNodeWithText("方法").activate()
            ui.onNodeWithText("搜索名称或签名").performTextReplacement("Calculator;->add(II)I")
            ui.onNodeWithText("查询").activate()
            ui.waitUntil(30000) { ui.onAllNodesWithText("io.github.easylsp.demo.Calculator#add(int,int):int").fetchSemanticsNodes().isNotEmpty() }
            screenshot("analysis-light")
            ui.onNodeWithText("io.github.easylsp.demo.Calculator#add(int,int):int").activate()
            ui.waitUntil(10000) { ui.onAllNodesWithText("Java").fetchSemanticsNodes().isNotEmpty() }
            ui.onNodeWithText("Java").activate()
            ui.waitUntil(30000) { ui.onAllNodesWithText("查找内容").fetchSemanticsNodes().isNotEmpty() }
            screenshot("code-light")
            ui.onNodeWithContentDescription("返回").activate()
            ui.onNodeWithText("生成 Hook 模板").activate()
            ui.onNodeWithText(plugin.name).activate()
            ui.waitUntil(10000) { var ready = false; ui.runOnUiThread { ready = editor() != null }; ready }
            ui.runOnUiThread { requireNotNull(editor()).setSelection(1, 4) }
            ui.onNodeWithText("从分析签名生成模板").activate()
            ui.onNodeWithText("解析并预览").activate()
            ui.onNodeWithText("插入当前光标").assertIsDisplayed()
            screenshot("template-light")
            ui.onNodeWithText("插入当前光标").activate()
            ui.waitUntil(10000) { text().contains("io.github.easylsp.demo.Calculator") }
            ui.onNodeWithContentDescription("返回").activate()
            ui.onNodeWithText("放弃修改").activate()
            ui.waitUntil(10000) { ui.onAllNodesWithText("将模板插入插件").fetchSemanticsNodes().isNotEmpty() }
            ui.onNodeWithContentDescription("返回").activate()
            ui.onNodeWithText("关闭").activate()
            ui.onNodeWithText("检视").activate()
            screenshot("inspect-light")
            ui.onNodeWithText("工作台").activate()
            screenshot("workspace-light")
            val imported = runBlocking(Dispatchers.IO) {
                val sample = requireNotNull(graph.store.dao.sample(project.id, project.sampleRevision))
                graph.samples.importApk(android.net.Uri.fromFile(File(org.json.JSONArray(sample.filesJson).getString(0))))
            }
            ui.onNodeWithContentDescription("返回").activate()
            ui.onAllNodesWithText("导入 APK").onFirst().activate()
            ui.onNodeWithText(imported.name).performScrollTo().activate()
            if (runBlocking { graph.store.workflows.observeProviders().first().isNotEmpty() }) {
                ui.waitUntil(10000) { ui.onAllNodesWithText("输入需求").fetchSemanticsNodes().isNotEmpty() }
                ui.onNodeWithContentDescription("返回").activate()
            }
            ui.onNodeWithText("静态项目").assertIsDisplayed()
            screenshot("imported-project-light")
            ui.onAllNodesWithText("启动").assertCountEquals(0)
        } finally { runBlocking { graph.store.delete(plugin.id) } }
    }

}
