package io.github.easylsp.app

import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import io.github.easylsp.app.feature.editor.HookTemplate
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.Test
import org.junit.Assert.*
import java.io.File

class UiHookWorkflowDeviceTest {
    @Test fun realDemoAnalysisSaveReloadUnloadWorkflow() = runBlocking(Dispatchers.IO) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val graph = instrumentation.targetContext.applicationContext as EasylspApp
        val store = graph.store
        val project = store.select("io.github.easylsp.demo", "Easylsp Demo", "1")
        val previouslyEnabled = store.dao.plugins(project.id).filter { it.desiredEnabled }
        val evidence = JSONObject()
        val results = org.json.JSONArray()
        var pluginId: String? = null; var probeId: String? = null
        try {
            previouslyEnabled.forEach { store.enable(it.id, false) }
            instrumentation.uiAutomation.executeShellCommand("am force-stop io.github.easylsp.demo").use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes() }
            instrumentation.uiAutomation.executeShellCommand("am start -n io.github.easylsp.demo/.DemoActivity").use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes() }
            graph.sendBroadcast(Intent("io.github.easylsp.RECONNECT").setPackage(project.packageName))
            val host = withTimeout(15000) { graph.bridge.sessions.first { rows -> rows.values.any { it.packageName == project.packageName && it.processName == project.packageName } }.values.first { it.packageName == project.packageName && it.processName == project.packageName } }
            val sampled = graph.samples.capture(project)
            val found = graph.analysis.call(project.id, sampled.sampleRevision, "methods", JSONObject().put("query", "Calculator;->add(II)I")) {}
            val method = found.getJSONArray("items").getJSONObject(0)
            assertEquals("Lio/github/easylsp/demo/Calculator;->add(II)I", method.getString("signature"))
            val java = graph.analysis.call(project.id, sampled.sampleRevision, "java", JSONObject().put("className", method.getString("owner")).put("source", method.getString("source")).put("signature", method.getString("signature")).put("limit", 200)) {}
            assertTrue(java.getString("text").contains("add"))
            evidence.put("signature", method.getString("signature")).put("source", method.getString("source")).put("sampleRevision", sampled.sampleRevision).put("template", HookTemplate.generate(HookTemplate.parse(method.getString("signature")), "before"))
            val before = graph.assets.open("before-add/main.java").bufferedReader().use { it.readText() }
            var plugin = store.create(sampled, "UI 工作流实际 Hook 验收", before)
            pluginId = plugin.id
            val probe = store.create(sampled, "UI 实际返回值探针", "void onLoad() { plugin.log(\"UI_ACTUAL=\" + new io.github.easylsp.demo.Calculator().add(2, 3)); }\nvoid onUnload() {}")
            probeId = probe.id; store.enable(probe.id, true)
            suspend fun actual(expected: Int) {
                graph.bridge.commandSession(host.id, "reloadPlugin", probe.id)
                val rows = graph.logs.lines.value.map { JSONObject(it) }
                val value = rows.last { it.optString("pluginId") == probe.id && it.optString("message").startsWith("UI_ACTUAL=") }.getString("message").substringAfter('=').toInt()
                assertEquals(expected, value)
                results.put(value)
            }
            actual(5)
            store.enable(plugin.id, true); graph.bridge.commandSession(host.id, "loadPlugin", plugin.id); actual(7)
            val after = graph.assets.open("after-add/main.java").bufferedReader().use { it.readText() }
            store.save(plugin.id, plugin.name, "", "", "1", after)
            assertEquals(1L, graph.bridge.instances.value["${host.id}/${plugin.id}"]!!.revision); actual(7)
            graph.bridge.commandSession(host.id, "reloadPlugin", plugin.id); actual(11)
            plugin = requireNotNull(store.dao.plugin(plugin.id))
            val replace = graph.assets.open("replace-add/main.java").bufferedReader().use { it.readText() }
            store.save(plugin.id, plugin.name, "", "", "1", replace)
            assertEquals(2L, graph.bridge.instances.value["${host.id}/${plugin.id}"]!!.revision)
            graph.bridge.commandSession(host.id, "reloadPlugin", plugin.id); actual(42)
            graph.bridge.commandSession(host.id, "unloadPlugin", plugin.id); actual(5)
            graph.bridge.commandSession(host.id, "unloadPlugin", probe.id)
            val libraryBytes = instrumentation.context.assets.open("library-fixture.dex").use { it.readBytes() }
            store.addLibrary(probe.id, "fixture.dex", libraryBytes)
            val probeCurrent = requireNotNull(store.dao.plugin(probe.id))
            store.save(probe.id, probe.name, "", "", "1", "void onLoad() { plugin.log(\"DEPENDENCY_ACTUAL=\" + fixture.LibraryFixture.answer()); }")
            graph.bridge.commandSession(host.id, "reloadPlugin", probe.id)
            assertTrue(graph.logs.lines.value.any { val row = JSONObject(it); row.optString("pluginId") == probe.id && row.optString("message") == "DEPENDENCY_ACTUAL=73" })
            graph.bridge.commandSession(host.id, "unloadPlugin", probe.id)
            evidence.put("dependencyActual", 73).put("actualResults", results).put("framework", graph.framework.state.value.description).put("process", host.processName).put("pid", host.pid)
            File(graph.filesDir, "phase2-hook-evidence.json").writeText(evidence.toString(2))
        } finally {
            val host = graph.bridge.session(project.packageName)
            listOfNotNull(pluginId, probeId).forEach { id -> if (host != null) graph.bridge.commandSession(host.id, "unloadPlugin", id); store.delete(id) }
            previouslyEnabled.forEach { store.enable(it.id, true) }
        }
    }
}
