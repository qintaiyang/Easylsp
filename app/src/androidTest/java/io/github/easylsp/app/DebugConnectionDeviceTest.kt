package io.github.easylsp.app

import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.github.easylsp.app.platform.tools.RunBinding
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class DebugConnectionDeviceTest {
    @get:Rule val visible = VisibleActivityRule()

    @Test fun targetWithoutManagerVisibilityCanRestartReconnectAndExecute() = runBlocking<Unit>(Dispatchers.IO) {
        val graph = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as EasylspApp
        ActivityScenario.launch(MainActivity::class.java).use {
            withTimeout(15000) { graph.framework.state.first { it.compatible } }
            val project = requireNotNull(graph.store.dao.projectByPackage("com.chaoxing.mobile", 0))
            val tools = graph.tools.builtins(RunBinding(project, project.sampleRevision, null, "debug-connection-test"))
            suspend fun call(name: String, args: JSONObject = JSONObject()) = tools.first { it.name == name }.invoke(args)
            val started = call("start_target", JSONObject().put("restart", true))
            assertTrue(started.toString(), started.getJSONArray("sessions").length() > 0)
            val first = graph.bridge.sessions.value.values.first { it.processName == project.packageName }
            val tree = call("inspect_target", JSONObject().put("sessionId", first.id).put("operation", "viewTree").put("arguments", JSONObject().put("limit", 1)))
            assertTrue(tree.toString(), tree.getInt("windowCount") > 0)
            val script = call("debug_target_once", JSONObject().put("sessionId", first.id).put("source", "6 * 7;"))
            assertEquals(42, script.getInt("result"))
            call("reconnect_target")
            val second = withTimeout(10000) {
                graph.bridge.sessions.first { rows -> rows.values.any { it.processName == project.packageName && it.id != first.id && it.startTicks != null } }
                    .values.first { it.processName == project.packageName }
            }
            assertEquals(first.pid, second.pid)
            assertEquals(42, call("debug_target_once", JSONObject().put("sessionId", second.id).put("source", "6 * 7;")).getInt("result"))
            val visible = call("debug_target_once", JSONObject().put("sessionId", second.id).put("source",
                "boolean visible; try { context.getPackageManager().getApplicationInfo(\"io.github.easylsp\", 0); visible = true; } catch (android.content.pm.PackageManager.NameNotFoundException e) { visible = false; } visible;"))
            File(graph.filesDir, "debug-connection-evidence.json").writeText(JSONObject().put("start", started)
                .put("target", graph.bridge.identity(second)).put("sameProcessReconnected", true).put("windowCount", tree.getInt("windowCount"))
                .put("scriptResult", 42).put("managerVisibleToTarget", visible.getBoolean("result")).toString(2))
        }
    }
}
