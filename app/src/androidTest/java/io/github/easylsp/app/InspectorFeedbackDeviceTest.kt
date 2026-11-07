package io.github.easylsp.app

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.github.easylsp.app.data.McpServerEntity
import io.github.easylsp.app.platform.harness.HarnessToolBridge
import io.github.easylsp.app.platform.mcp.McpClient
import io.github.easylsp.app.platform.tools.RunBinding
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

class InspectorFeedbackDeviceTest {
    @get:Rule val visible = VisibleActivityRule()
    @Test fun windowsCompactOperationsTypedResultsAndErrorsCrossTheActualBridge() = runBlocking<Unit>(Dispatchers.IO) {
        val graph = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as EasylspApp
        ActivityScenario.launch(MainActivity::class.java).use {
            withTimeout(15000) { graph.framework.state.first { it.compatible } }
            assertTrue(graph.root.useRoot())
            val project = requireNotNull(graph.store.dao.projectByPackage("io.github.easylsp.demo", 0))
            // Reinstalling the Demo changes sourceDir; refresh its existing fixture snapshots.
            val app = requireNotNull(graph.targetApplications.info(project.packageName, project.userId).applicationInfo)
            val key = app.uid.toString() + ":" + project.packageName
            graph.framework.nativeSnapshot(key)?.let { snapshot ->
                graph.framework.publishNativeSnapshot(key, JSONObject(snapshot).put("targetApkPath", app.sourceDir))
            }
            val output = StringBuilder()
            val launch = graph.executions.runner.execute(UUID.randomUUID().toString(), listOf(graph.root.su, "-c",
                "input keyevent 224; wm dismiss-keyguard; am force-stop io.github.easylsp.demo; am start -W -n io.github.easylsp.demo/.DemoActivity --ez verifyInspector true"), graph.filesDir,
                closeInput = true, timeoutSeconds = 30) { output.append(it) }
            assertEquals(output.toString(), 0, launch.exitCode)
            graph.sendBroadcast(Intent("io.github.easylsp.RECONNECT").setPackage(project.packageName))
            val session = withTimeout(15000) { graph.bridge.sessions.first { sessions -> sessions.values.any { s -> s.packageName == project.packageName && s.processName == project.packageName } }
                .values.first { s -> s.packageName == project.packageName && s.processName == project.packageName } }
            val binding = RunBinding(project, project.sampleRevision, session.id, "inspector-feedback-test")
            val tools = graph.tools.builtins(binding).filter { tool -> tool.name in setOf("inspect_target", "debug_target_once") }
            HarnessToolBridge(tools).use { bridge ->
                McpClient(McpServerEntity("fixture", "fixture", bridge.url), bridge.token).use { client ->
                    client.initialize()
                    suspend fun raw(operation: String, args: JSONObject) = client.call("inspect_target", JSONObject()
                        .put("sessionId", session.id).put("operation", operation).put("arguments", args))
                    suspend fun inspect(operation: String, args: JSONObject): JSONObject {
                        val reply = raw(operation, args)
                        assertFalse(reply.toString(), reply.optBoolean("isError"))
                        return reply.getJSONObject("structuredContent")
                    }
                    suspend fun find(query: String): Pair<JSONObject, JSONObject> {
                        var tree = inspect("viewTree", JSONObject().put("query", query))
                        withTimeout(5000) {
                            while (tree.getJSONArray("items").length() == 0) { delay(100); tree = inspect("viewTree", JSONObject().put("query", query)) }
                        }
                        val rows = tree.getJSONArray("items")
                        return tree to (0 until rows.length()).map { rows.getJSONObject(it) }.first { row -> row.optString("text") == query || row.optString("description") == query }
                    }
                    fun args(found: Pair<JSONObject, JSONObject>) = JSONObject().put("snapshotId", found.first.getString("snapshotId")).put("path", found.second.getString("path"))
                    suspend fun click(query: String): JSONObject = inspect("viewClick", args(find(query)))
                    val dialogOpen = click("INSPECTOR_OPEN_DIALOG")
                    assertFalse(dialogOpen.has("snapshot")); assertTrue(dialogOpen.toString().length < 4000)
                    val dialog = find("INSPECTOR_DIALOG_OK")
                    assertTrue(dialog.first.getInt("windowCount") >= 2)
                    val dialogClicked = inspect("viewClick", args(dialog))
                    assertTrue(dialogClicked.getBoolean("performed"))
                    // Dialog dismissal posts removal on the UI queue; wait for the window to detach.
                    delay(250)
                    val staleNode = raw("viewClick", args(dialog))
                    assertTrue(staleNode.getBoolean("isError"))
                    assertEquals("STALE_NODE", staleNode.getJSONObject("structuredContent").getJSONObject("error").getString("code"))
                    find("INSPECTOR_DIALOG_CLICKED")
                    click("INSPECTOR_OPEN_POPUP")
                    val popup = find("INSPECTOR_POPUP_OK")
                    assertTrue(popup.first.getInt("windowCount") >= 2)
                    assertTrue(inspect("viewClick", args(popup)).getBoolean("performed"))
                    find("INSPECTOR_POPUP_CLICKED")
                    val scroll = find("INSPECTOR_SCROLL")
                    val moved = inspect("viewScroll", args(scroll))
                    assertTrue(moved.getBoolean("performed")); assertFalse(moved.has("snapshot")); assertTrue(moved.toString().length < 4000)
                    val bounded = inspect("viewNode", args(scroll).put("includeSnapshot", true).put("snapshotLimit", 3))
                    assertEquals(3, bounded.getJSONArray("snapshot").length()); assertTrue(bounded.getBoolean("snapshotTruncated"))
                    find("INSPECTOR_SCROLL")
                    val stale = raw("viewScroll", args(scroll))
                    assertTrue(stale.getBoolean("isError"))
                    assertEquals("STALE_SNAPSHOT", stale.getJSONObject("structuredContent").getJSONObject("error").getString("code"))
                    suspend fun debug(source: String, timeout: Int = 3000) = client.call("debug_target_once", JSONObject().put("sessionId", session.id).put("source", source).put("timeoutMs", timeout))
                    val typed = debug("Map out = new java.util.LinkedHashMap(); out.put(\"number\", 42); out.put(\"flag\", true); out.put(\"items\", java.util.Arrays.asList(new String[]{\"a\",\"b\"})); out;")
                    assertFalse(typed.toString(), typed.optBoolean("isError"))
                    val typedResult = typed.getJSONObject("structuredContent")
                    assertEquals(42, typedResult.getJSONObject("result").getInt("number"))
                    assertTrue(typedResult.getJSONObject("result").getBoolean("flag"))
                    assertEquals("b", typedResult.getJSONObject("result").getJSONArray("items").getString(1))
                    assertEquals("Easylsp-debug-once", typedResult.getString("thread"))
                    val reflected = debug("reflect.inspect(new java.util.ArrayList());").getJSONObject("structuredContent").getJSONObject("result")
                    assertEquals("java.util.ArrayList", reflected.getString("class"))
                    val timeout = debug("Thread.sleep(1000);", 100)
                    assertTrue(timeout.getBoolean("isError"))
                    assertEquals("SCRIPT_TIMEOUT", timeout.getJSONObject("structuredContent").getJSONObject("error").getString("code"))
                    assertEquals(42, debug("6 * 7;").getJSONObject("structuredContent").getInt("result"))
                    File(graph.filesDir, "inspector-feedback-evidence.json").writeText(JSONObject()
                        .put("dialogWindowFoundAndClicked", true).put("popupWindowFoundAndClicked", true)
                        .put("scrollCharacters", moved.toString().length).put("clickCharacters", dialogOpen.toString().length)
                        .put("boundedSnapshotNodes", 3).put("structuredResult", typedResult).put("reflectionResult", reflected)
                        .put("errorCodes", JSONArray(listOf("STALE_NODE", "STALE_SNAPSHOT", "SCRIPT_TIMEOUT")))
                        .put("afterTimeoutResult", 42).toString(2))
                }
            }
            graph.bridge.commandSession(session.id, "disconnectDebug")
        }
    }
}
