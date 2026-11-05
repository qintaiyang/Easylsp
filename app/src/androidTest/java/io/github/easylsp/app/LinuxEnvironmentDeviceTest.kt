package io.github.easylsp.app

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class LinuxEnvironmentDeviceTest {
    @get:org.junit.Rule val visible = VisibleActivityRule()
    @Test fun installOfficialUbuntuAndExecuteInActualChroot() = runBlocking(Dispatchers.IO) {
        val graph = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as EasylspApp
        val activity = androidx.test.core.app.ActivityScenario.launch(MainActivity::class.java)
        try {
            val project = graph.store.select("io.github.easylsp.demo", "Easylsp Demo", "1")
            assertTrue("设备须已授权应用 su", graph.root.useRoot())
            if (!graph.linux.installed) graph.linux.install()
            val command = graph.linux.command(project.id, "id; cat /etc/os-release; test -x /usr/bin/apt-get; printf LINUX_ACTUAL_OK > linux-validation.txt; cat linux-validation.txt")
            val id = graph.executions.start(project.id, "linux", "Ubuntu 真机验收") { execution, out -> graph.executions.runner.execute(execution, command, graph.executions.directory(project.id), closeInput = true, timeoutSeconds = 60, output = out).exitCode }
            val result = graph.executions.await(project.id, id)
            assertEquals(result.toString(), "COMPLETE", result.getString("state")); assertTrue(result.getString("text").contains("LINUX_ACTUAL_OK")); assertTrue(result.getString("text").contains("Ubuntu"))
            assertEquals("LINUX_ACTUAL_OK", File(graph.executions.directory(project.id), "linux-validation.txt").readText())
            File(graph.filesDir, "phase2-linux-evidence.json").writeText(JSONObject().put("installed", graph.linux.installed).put("deviceRootGranted", graph.root.state.value.granted).put("execution", result).toString(2))
        } finally { activity.close() }
    }
}
