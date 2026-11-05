package io.github.easylsp.app

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.github.easylsp.app.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

/** Uses the developer's configured model, real Harness admission, Android tools and hooked Demo. */
class HarnessChatDeviceTest {
    @get:Rule val visible = VisibleActivityRule()
    @Test fun configuredModelCompletesPluginWorkflowThroughProductionChat() = runBlocking(Dispatchers.IO) {
        val graph = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as EasylspApp
        val dao = graph.store.workflows
        val providerId = "e3838f11-c406-4bab-9f35-edef175a9bd5"
        val provider = requireNotNull(dao.provider(providerId))
        // Explicit developer choice: 1000K capacity, 200K working budget. Credentials stay in SecretStore.
        dao.saveProvider(provider.copy(options = JSONObject(provider.options).put("contextWindow", 1000000).put("contextBudget", 200000).toString()))
        val project = requireNotNull(graph.store.dao.projectByPackage("io.github.easylsp.demo"))
        val activity = ActivityScenario.launch(MainActivity::class.java)
        graph.startActivity(requireNotNull(graph.packageManager.getLaunchIntentForPackage(project.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        graph.sendBroadcast(Intent("io.github.easylsp.RECONNECT").setPackage(project.packageName))
        val target = withTimeout(30000) { graph.bridge.sessions.first { rows -> rows.values.any { it.packageName == project.packageName && it.processName == project.packageName } }.values.first { it.packageName == project.packageName && it.processName == project.packageName } }
        val id = UUID.randomUUID().toString()
        val name = "Harness真机验收-${id.take(8)}"
        dao.insertConversation(ConversationEntity(id, project.id, providerId, provider.model, "Harness 模型与插件闭环", project.sampleRevision, System.currentTimeMillis()))
        val before = graph.store.dao.plugins(project.id).map { it.id }.toSet()
        try {
            graph.chat.submit(id, "在当前 Demo 项目完成一次插件闭环验收：查询 Calculator.add 的真实签名，按 read_development_reference 的 API 和 example 写一个名为 $name 的临时插件，把 add(int,int) 的返回值改为 42。在 onLoad 中实际调用 new io.github.easylsp.demo.Calculator().add(2,3)，用 plugin.log 输出 HARNESS_ACTUAL=<真实返回值>。加载到本轮绑定目标，读取日志确认 HARNESS_ACTUAL=42；然后卸载并删除刚创建的临时插件。只处理本次插件，保留其他插件。直接执行并用中文报告真实结果。", target.id, true, JSONArray()).await()
            assertNotNull(dao.harnessSession(id))
            activity.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
            val turns = withTimeout(600000) { dao.turns(id).first { rows -> rows.isNotEmpty() && rows.last().finishedAt != null } }
            val turn = turns.last()
            assertEquals(turn.errorDetail, "COMPLETE", turn.status)
            val runs = dao.toolRuns(id).first()
            assertTrue("模型必须查询真实签名", runs.any { it.name == "find_method" && it.result.contains("Calculator;->add(II)I") })
            assertTrue("必须读取真实运行日志", runs.any { it.name == "read_plugin_logs" && it.result.contains("HARNESS_ACTUAL=42") })
            assertFalse(graph.store.dao.plugins(project.id).any { it.name == name })
            assertTrue(runs.any { it.name == "delete_plugin" && it.status == "COMPLETE" })
            assertTrue(dao.history(id).all { it.engineMessageId != null })
            File(graph.filesDir, "phase3-production-chat-evidence.json").writeText(JSONObject().put("conversationId", id).put("model", provider.model)
                .put("contextCapacity", 1000000).put("contextBudget", 200000).put("engine", "Harness 0.2.2-alpha.3").put("activityStopped", true)
                .put("requests", turn.requestCount).put("tools", JSONArray(runs.map { JSONObject().put("name", it.name).put("status", it.status).put("millis", (it.finishedAt ?: it.startedAt) - it.startedAt) }))
                .put("actualHookResult", 42).put("pluginRemoved", true).put("status", turn.status).toString(2))
        } finally {
            graph.chat.stop()
            withTimeout(30000) { graph.chat.activeConversation.first { it == null } }
            for (plugin in graph.store.dao.plugins(project.id).filter { it.id !in before && it.name == name }) {
                graph.bridge.commandSession(target.id, "unloadPlugin", plugin.id)
                graph.store.delete(plugin.id)
            }
            activity.close()
        }
    }
}
