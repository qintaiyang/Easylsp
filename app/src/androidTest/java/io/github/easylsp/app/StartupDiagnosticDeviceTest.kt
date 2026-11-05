package io.github.easylsp.app

import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.github.easylsp.app.data.ProjectEntity
import io.github.easylsp.app.platform.diagnostics.DiagnosticMode
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.UUID

/** Only the controlled demo is launched. Its one-shot marker is consumed before onCreate. */
class StartupDiagnosticDeviceTest {
    @get:Rule val visible = VisibleActivityRule()
    private val graph get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as EasylspApp
    private lateinit var scenario: ActivityScenario<MainActivity>
    private lateinit var project: ProjectEntity
    @Before fun setup() = runBlocking {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        Assume.assumeTrue("requires granted device root", graph.root.useRoot())
        withContext(Dispatchers.IO) {
            val info = graph.packageManager.getPackageInfo("io.github.easylsp.demo", 0)
            project = graph.store.select(info.packageName, "Easylsp Demo", info.versionName.orEmpty())
        }
    }
    @After fun close() = runBlocking {
        if (graph.root.state.value.granted) graph.executions.runner.execute(UUID.randomUUID().toString(), listOf(graph.root.su, "-c", "nsenter -t 1 -m -- rm -f /data/user/0/io.github.easylsp.demo/files/startup-fault.txt"), graph.filesDir, closeInput = true) {}
        scenario.close()
    }
    private suspend fun fixture(mode: String) {
        val text = StringBuilder()
        val app = graph.packageManager.getApplicationInfo("io.github.easylsp.demo", 0)
        val directory = io.github.easylsp.app.platform.root.RootCommand.quote(app.dataDir + "/files")
        val path = io.github.easylsp.app.platform.root.RootCommand.quote(app.dataDir + "/files/startup-fault.txt")
        val script = "mkdir -p $directory && chown ${app.uid}:${app.uid} $directory && echo $mode > $path && chown ${app.uid}:${app.uid} $path && chmod 600 $path && restorecon -F $path && cat $path"
        // Android app-data isolation hides other apps' /data/user mounts, even from an inherited su namespace.
        val command = "nsenter -t 1 -m -- /system/bin/sh -c " + io.github.easylsp.app.platform.root.RootCommand.quote(script)
        val result = graph.executions.runner.execute(UUID.randomUUID().toString(), listOf(graph.root.su, "-c", command), graph.filesDir, closeInput = true) { text.append(it) }
        assertEquals(text.toString(), 0, result.exitCode)
        assertEquals(mode, text.toString().trim().lineSequence().last())
    }
    private suspend fun diagnose(mode: DiagnosticMode = DiagnosticMode.EXTERNAL_ONLY): JSONObject {
        // The demo owns the foreground after a launch. Bring the manager's task back explicitly.
        graph.executions.runner.execute(UUID.randomUUID().toString(), listOf(graph.root.su, "-c", "am start -n io.github.easylsp/io.github.easylsp.app.MainActivity"), graph.filesDir, closeInput = true) {}
        val result = graph.diagnostics.start(project, project.sampleRevision, mode, "*", 8, true)
        val execution = withTimeout(60000) { graph.executions.await(project.id, result.getString("executionId")) }
        val report = graph.diagnostics.store.read(project.id, result.getString("diagnosticRunId"))
        assertEquals(execution.toString() + "\n" + report.toString(2), "COMPLETE", report.getString("state"))
        File(graph.filesDir, "startup-diagnostic-verification.jsonl").appendText(report.toString() + "\n")
        return report
    }
    private fun events(report: JSONObject) = File(graph.diagnostics.store.directory(project.id, report.getString("diagnosticRunId")), "events.jsonl").readText()

    @Test fun nativeConstructorAndJniFailuresRetainEvidenceWithoutSession() = runBlocking(Dispatchers.IO) {
        for (mode in listOf("constructor", "jni", "segv", "abort")) {
            fixture(mode)
            val report = diagnose()
            assertTrue(report.toString(2), report.getJSONArray("tombstones").length() > 0)
            assertEquals("NATIVE_FATAL_SIGNAL", report.getJSONObject("conclusion").getString("classification"))
            assertTrue(report.getJSONArray("tombstones").getJSONObject(0).getJSONArray("frames").length() > 0)
            assertFalse(events(report).contains("session_connected"))
        }
    }
    @Test fun exitAndSigkillHaveExitRecordsWithoutInventedTombstones() = runBlocking(Dispatchers.IO) {
        for ((mode, expected) in listOf("exit" to "SELF_EXIT", "kill" to "SIGNALLED", "java" to "JAVA_EXCEPTION")) {
            fixture(mode)
            val report = diagnose()
            assertEquals(report.toString(2), expected, report.getJSONObject("conclusion").getString("classification"))
            assertEquals(0, report.getJSONArray("tombstones").length())
        }
    }
    @Test fun entryModesAreObservedAndSavedPluginEnablementIsPreserved() = runBlocking(Dispatchers.IO) {
        withTimeout(5000) { while (!graph.framework.state.value.active) delay(50) }
        val plugin = graph.store.create(project, "Startup auto-load fixture", "void onLoad() { plugin.log(\"DIAGNOSTIC_AUTOLOAD_UNEXPECTED\"); }")
        graph.store.enable(plugin.id, true)
        try {
            val entry = diagnose(DiagnosticMode.ENTRY_ONLY)
            assertTrue(events(entry), events(entry).contains("module_loaded"))
            assertFalse(events(entry).contains("java_hook_installed"))
            assertFalse(events(entry).contains("session_connected"))
            val java = diagnose(DiagnosticMode.JAVA_NO_PLUGINS)
            assertTrue(events(java), events(java).contains("plugin_auto_load_suppressed"))
            assertTrue(events(java).contains("session_connected"))
            assertTrue(graph.store.dao.plugin(plugin.id)!!.desiredEnabled)
            assertEquals(plugin.revision, graph.store.dao.plugin(plugin.id)!!.revision)
            assertFalse(graph.bridge.instances.value.keys.any { it.endsWith("/${plugin.id}") })
        } finally { graph.store.delete(plugin.id) }
    }
    @Test fun earlyNativeObservationSurvivesConstructorAndSelfTermination() = runBlocking(Dispatchers.IO) {
        for (mode in listOf("constructor", "jni", "exit", "kill")) {
            fixture(mode)
            val report = diagnose(DiagnosticMode.NATIVE_OBSERVE)
            assertEquals(report.toString(2), "OBSERVED", report.getString("nativeEngine"))
            assertEquals("OBSERVED", report.getString("modeVerification"))
            assertFalse(events(report).contains("java_hook_installed"))
            val native = report.getJSONObject("nativeObservation")
            val calls = native.getJSONArray("exitCalls")
            when (mode) {
                "constructor", "jni" -> {
                    val all = events(report).lineSequence().filter { it.isNotBlank() }.map { JSONObject(it) }.toList()
                    val name = if (mode == "constructor") "libdemo_constructor_fault.so" else "libdemo_jni_fault.so"
                    assertTrue(all.any { it.optString("phase") == "library_before_init" && it.optString("name").endsWith(name) })
                    assertEquals(mode == "jni", all.any { it.optString("phase") == "library_after_init" && it.optString("name").endsWith(name) })
                    assertTrue(report.getJSONArray("tombstones").length() > 0)
                }
                else -> {
                    val expected = if (mode == "exit") "_exit" else "kill"
                    val call = (0 until calls.length()).map { calls.getJSONObject(it) }.first { it.getString("name") == expected }
                    assertTrue(call.toString(), call.getString("callerModule").endsWith("libdemo_fault.so"))
                    assertTrue(call.getLong("startTicks") > 0)
                    assertEquals(if (mode == "exit") 42 else 9, call.getInt(if (mode == "exit") "argument0" else "argument1"))
                }
            }
        }
    }
    @Test fun readDoesNotLaunchAndStopStillPersistsReport() = runBlocking(Dispatchers.IO) {
        val result = graph.diagnostics.start(project, project.sampleRevision, DiagnosticMode.EXTERNAL_ONLY, "*", 120, false)
        val id = result.getString("diagnosticRunId")
        graph.diagnostics.stop(project.id, id)
        withTimeout(30000) { graph.executions.await(project.id, result.getString("executionId")) }
        assertEquals("STOPPED", graph.diagnostics.store.read(project.id, id).getString("state"))
        val file = File(graph.diagnostics.store.directory(project.id, id), "events.jsonl")
        val before = file.readText()
        graph.diagnostics.store.read(project.id, id)
        assertEquals(before, file.readText())
    }
}
