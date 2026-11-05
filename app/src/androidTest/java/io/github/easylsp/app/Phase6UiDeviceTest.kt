package io.github.easylsp.app

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.github.easylsp.app.data.*
import kotlinx.coroutines.*
import org.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Real Android UI dispatch: no Compose test continuation interceptor around Room IO or navigation. */
class Phase6UiDeviceTest {
    @get:Rule val visible = VisibleActivityRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val automation get() = instrumentation.uiAutomation
    private fun nodes(): List<AccessibilityNodeInfo> {
        val root = automation.rootInActiveWindow ?: return emptyList()
        val result = mutableListOf<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo) {
            result += node
            for (index in 0 until node.childCount) node.getChild(index)?.let(::visit)
        }
        visit(root)
        return result
    }
    private fun matches(node: AccessibilityNodeInfo, text: String): Boolean = node.text?.toString()?.split('\n')?.contains(text) == true || node.contentDescription?.toString() == text || node.hintText?.toString() == text ||
        (node.isEditable && (0 until node.childCount).any { node.getChild(it)?.let { child -> matches(child, text) } == true })
    private fun await(description: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10000
        while (!condition()) { check(SystemClock.uptimeMillis() < deadline) { "等待界面超时：$description" }; SystemClock.sleep(50) }
    }
    private fun show(text: String) = await(text) { nodes().any { it.isVisibleToUser && matches(it, text) } }
    private fun click(text: String) {
        lateinit var target: AccessibilityNodeInfo
        await("可点击：$text") {
            var node = nodes().firstOrNull { it.isVisibleToUser && matches(it, text) } ?: return@await false
            while (!node.isClickable) node = node.parent ?: return@await false
            target = node
            node.isEnabled
        }
        assertTrue("点击：$text", target.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        automation.waitForIdle(500, 5000)
    }
    private fun replace(text: String, label: String? = null) {
        automation.waitForIdle(500, 5000)
        await(label ?: "输入框") { nodes().any { it.isVisibleToUser && it.isEditable && (label == null || matches(it, label)) } }
        val node = nodes().single { it.isVisibleToUser && it.isEditable && (label == null || matches(it, label)) }
        assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }))
        await("输入内容更新") { nodes().any { it.isEditable && matches(it, text) } }
    }
    private fun command(text: String) { show("快速对话 · AI 助手"); replace(text); click("发送") }
    private fun showConversation(graph: EasylspApp, id: String) {
        await("会话 $id") { graph.chat.statusConversation.value == id }
        show("快速对话 · AI 助手")
    }
    private fun scrollClick(text: String) {
        await(text) {
            val current = nodes()
            if (current.any { it.isVisibleToUser && matches(it, text) }) true
            else { current.lastOrNull { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD); false }
        }
        click(text)
    }
    private fun capture(graph: EasylspApp, name: String) {
        automation.waitForIdle(500, 5000)
        val bitmap = requireNotNull(automation.takeScreenshot())
        val file = File(graph.filesDir, "phase6-ui/$name.png").apply { parentFile!!.mkdirs() }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
    }
    @Test fun globalModelPickerCommandsMemoryAndTerminal() {
        val graph = instrumentation.targetContext.applicationContext as EasylspApp; val dao = graph.store.workflows
        File(graph.filesDir, "phase6-ui-failure.txt").delete()
        File(graph.filesDir, "phase6-ui-progress.txt").writeText("model-picker")
        lateinit var a: ProviderEntity; lateinit var b: ProviderEntity; lateinit var first: ProviderModelEntity; lateinit var second: ProviderModelEntity; lateinit var conversation: ConversationEntity
        val created = mutableListOf<String>()
        runBlocking(Dispatchers.IO) {
            dao.providers().filter { it.name in setOf("Phase6 UI connection A", "Phase6 UI connection B") }.forEach { graph.models.configureProvider(it.id, JSONObject().put("enabled", false)) }
            dao.memories(null).filter { it.title == "Phase6 UI memory" }.forEach { graph.memories.removeFromSettings(it.id) }
            a = graph.models.configureProvider(null, JSONObject().put("name", "Phase6 UI connection A").put("protocol", "openai").put("baseUrl", "http://127.0.0.1:1/v1"))
            b = graph.models.configureProvider(null, JSONObject().put("name", "Phase6 UI connection B").put("protocol", "openai").put("baseUrl", "http://127.0.0.1:2/v1"))
            first = graph.models.configureModel(null, a.id, "ui-fixture-one", JSONObject().put("displayName", "Phase6 模型一"))
            second = graph.models.configureModel(null, a.id, "ui-fixture-two", JSONObject().put("displayName", "Phase6 模型二"))
            graph.models.configureModel(null, b.id, "ui-fixture-other", JSONObject())
            conversation = graph.conversations.create(modelId = first.id, title = "Phase6 UI quick chat")
            created += conversation.id
        }
        val id = conversation.id
        val activity = ActivityScenario.launch<MainActivity>(Intent(graph, MainActivity::class.java).putExtra("route", "chat/$id"))
        try {
            showConversation(graph, id); click("ui-fixture-one ▾"); show("选择供应商")
            click("Phase6 UI connection A"); show("Phase6 模型二"); capture(graph, "models")
            click("Phase6 模型二"); show("ui-fixture-two ▾")
            assertEquals(second.id, runBlocking(Dispatchers.IO) { dao.conversation(id)!!.modelConfigId })
            command("/compact extra"); show("命令或参数无法识别"); click("返回编辑")
            await("未知命令草稿保留") { nodes().any { it.isEditable && matches(it, "/compact extra") } }
            command("/context"); show("关闭"); capture(graph, "context"); click("关闭")
            command("/tools"); show("工具选择"); replace("search_memory", "搜索")
            await("search_memory 工具条目") { nodes().any { !it.isEditable && matches(it, "search_memory") } }
            capture(graph, "tools"); click("关闭")
            command("/memory"); show("记忆与规则"); click("新增记忆")
            replace("Phase6 UI memory", "标题"); replace("界面实际保存的全局记忆", "正文")
            click("保存"); await("记忆保存后关闭编辑框") { nodes().none { matches(it, "保存") } }
            show("Phase6 UI memory"); capture(graph, "memory")
            File(graph.filesDir, "phase6-ui-progress.txt").writeText("delete-memory")
            click("Phase6 UI memory"); click("删除")
            await("删除弹窗关闭") { nodes().none { matches(it, "删除") } }
            click("关闭"); await("记忆面板关闭") { nodes().none { matches(it, "记忆与规则") } }
            File(graph.filesDir, "phase6-ui-progress.txt").writeText("agents-and-fork")
            val child = runBlocking(Dispatchers.IO) { graph.conversations.create(modelId = second.id, title = "Phase6 UI child", parent = conversation) }
            created += child.id
            command("/agents"); show("Phase6 UI child · IDLE"); capture(graph, "agents")
            click("Phase6 UI child · IDLE"); showConversation(graph, child.id)
            click("返回"); showConversation(graph, id)
            command("/fork")
            await("分支创建") { runBlocking(Dispatchers.IO) { dao.allConversations().any { it.title == "Phase6 UI quick chat · 分支" && it.createdAt >= conversation.createdAt } } }
            val branch = runBlocking(Dispatchers.IO) { dao.allConversations().last { it.title == "Phase6 UI quick chat · 分支" && it.createdAt >= conversation.createdAt } }
            created += branch.id
            showConversation(graph, branch.id)
            assertNull(runBlocking(Dispatchers.IO) { dao.harnessSession(branch.id) })
            click("返回"); showConversation(graph, id)
            File(graph.filesDir, "phase6-ui-progress.txt").writeText("open-terminal")
            click("会话用量与设置"); scrollClick("打开会话终端"); click("新建终端")
            await("终端启动") { graph.terminals.entries.value.any { it.workspaceId == id && it.session.isRunning } }
            val terminal = graph.terminals.entries.value.first { it.workspaceId == id }
            activity.onActivity { terminal.session.write("printf 'GLOBAL_UI_OK' > phase6-ui.txt\n") }
            val output = graph.executions.directory(id).resolve("phase6-ui.txt")
            await("终端实际写入") { output.exists() && output.readText() == "GLOBAL_UI_OK" }
            capture(graph, "global-terminal")
            activity.onActivity { graph.terminals.stop(id, terminal.id) }
            activity.onActivity { it.startActivity(Intent(it, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("route", "conversations")) }
            replace("Phase6 UI quick chat", "搜索会话标题或模型")
            show("Phase6 UI quick chat · 分支"); capture(graph, "branches")
            runBlocking(Dispatchers.IO) {
                assertNull(dao.harnessSession(id)); assertTrue(dao.history(id).isEmpty())
                assertTrue(dao.memories(null).none { it.title == "Phase6 UI memory" })
            }
            File(graph.filesDir, "phase6-ui-progress.txt").writeText("complete")
        } catch (error: Throwable) {
            File(graph.filesDir, "phase6-ui-failure.txt").writeText(error.stackTraceToString())
            File(graph.filesDir, "phase6-ui-tree.txt").writeText(nodes().joinToString("\n") { node ->
                val bounds = Rect(); node.getBoundsInScreen(bounds)
                "${node.className} text=${node.text} description=${node.contentDescription} hint=${node.hintText} editable=${node.isEditable} visible=${node.isVisibleToUser} bounds=$bounds"
            })
            File(graph.filesDir, "phase6-ui-agents.json").writeText(runBlocking(Dispatchers.IO) { graph.agents.list(id).toString(2) })
            capture(graph, "failure"); throw error
        } finally {
            activity.close()
            runBlocking(Dispatchers.IO) {
                for (createdId in created) { dao.deleteDraft(createdId); dao.deleteReading(createdId); dao.deleteGlobalConversation(createdId) }
                graph.models.configureProvider(a.id, JSONObject().put("enabled", false)); graph.models.configureProvider(b.id, JSONObject().put("enabled", false))
            }
        }
    }
}
