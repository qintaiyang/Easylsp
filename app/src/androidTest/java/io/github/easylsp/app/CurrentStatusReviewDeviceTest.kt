package io.github.easylsp.app

import androidx.test.platform.app.InstrumentationRegistry
import io.github.easylsp.app.platform.tools.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Read the real project through the same tool definitions the conversation receives. */
class CurrentStatusReviewDeviceTest {
    @Test fun currentCoverageAndSyntaxToolsStayCompactWithoutChangingPlugins() = runBlocking<Unit>(Dispatchers.IO) {
        val graph = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as EasylspApp
        withTimeout(15000) { graph.framework.state.first { it.compatible } }
        val project = requireNotNull(graph.store.dao.projectByPackage("com.qidian.QDReader", 0))
        val binding = RunBinding(project, project.sampleRevision, null, "current-status-review")
        val tools = graph.tools.builtins(binding)
        suspend fun call(name: String, args: JSONObject = JSONObject()): JSONObject {
            val tool = tools.single { it.name == name }
            ToolArguments.validate(tool.schema, args)
            return tool.invoke(args)
        }
        val before = graph.store.dao.plugins(project.id)
        val coverage = call("get_plugin_coverage")
        assertTrue(coverage.toString().length.toString(), coverage.toString().length < 7600)
        val native = before.first { it.runtime == "NATIVE" }
        val status = call("get_native_plugin_status", JSONObject().put("pluginId", native.id))
        assertEquals("current", status.getString("scope"))
        assertTrue(status.toString().length < 7600)
        val history = call("get_native_plugin_status", JSONObject().put("pluginId", native.id).put("scope", "history").put("limit", 2))
        val instances = history.getJSONArray("instances")
        assertTrue(history.toString().length < 7600)
        assertTrue(history.getInt("recordedInstances") > 0)
        if (instances.length() > 0) {
            val row = instances.getJSONObject(0)
            assertFalse(row.has("sources"))
            val read = row.getJSONObject("evidenceRead")
            val details = call(read.getString("tool"), read.getJSONObject("arguments").put("limit", 1))
            assertEquals(1, details.getJSONArray("items").length())
        }
        val capabilities = ToolCapabilities(tools)
        val selection = capabilities.select(JSONObject().put("names", JSONArray(listOf("get_plugin_coverage", "get_native_plugin_status", "validate_plugin", "read_plugin_logs"))))
        assertTrue(selection.toString().length < 1000)
        assertEquals(4, selection.getJSONArray("added").length())
        assertTrue(capabilities.search(JSONObject().put("query", "syntax")).getJSONArray("tools").toString().contains("validate_plugin"))
        val marker = File(graph.filesDir, "syntax-must-not-execute")
        assertFalse(marker.exists())
        val syntax = call("validate_plugin", JSONObject().put("source", "new java.io.File(\"" + marker.path + "\").createNewFile();"))
        assertTrue(syntax.getBoolean("valid")); assertFalse(marker.exists())
        val bad = call("validate_plugin", JSONObject().put("source", "void onLoad() {\nint n = ;\n}"))
        assertFalse(bad.getBoolean("valid")); assertEquals(2, bad.getInt("line"))
        assertEquals(before, graph.store.dao.plugins(project.id))
        File(graph.filesDir, "current-status-review-evidence.json").writeText(JSONObject()
            .put("coverageCharacters", coverage.toString().length).put("nativeStatusCharacters", status.toString().length)
            .put("historyCharacters", history.toString().length).put("selectionCharacters", selection.toString().length)
            .put("recordedInstances", history.getInt("recordedInstances")).put("syntaxNoExecution", true)
            .put("pluginConfigurationsUnchanged", true).put("coverage", coverage).put("nativeStatus", status).toString(2))
    }
}
