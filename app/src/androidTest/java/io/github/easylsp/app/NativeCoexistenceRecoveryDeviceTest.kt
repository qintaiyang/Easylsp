package io.github.easylsp.app

import androidx.test.platform.app.InstrumentationRegistry
import io.github.easylsp.app.data.*
import io.github.easylsp.app.platform.root.RootCommand
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

/** Uses precompiled candidates; only the controlled Demo's publication is touched. */
class NativeCoexistenceRecoveryDeviceTest {
    @get:org.junit.Rule(order = 0) val visible = VisibleActivityRule()
    @get:org.junit.Rule(order = 1) val activity = androidx.test.ext.junit.rules.ActivityScenarioRule(MainActivity::class.java)
    private val graph get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as EasylspApp
    private suspend fun root(script: String): String {
        val text = StringBuilder()
        val result = graph.executions.runner.execute(UUID.randomUUID().toString(), listOf(graph.root.su, "-c", script), graph.filesDir, closeInput = true, timeoutSeconds = 40) { text.append(it) }
        check(result.exitCode == 0) { text.toString() }; return text.toString().trim()
    }
    private suspend fun fixture() {
        root("am force-stop io.github.easylsp.demo")
        root("nsenter -t 1 -m -- run-as io.github.easylsp.demo sh -c " + RootCommand.quote("mkdir -p files; echo native-plugin > files/startup-fault.txt"))
    }
    private suspend fun launch(): String {
        val result = root("am start -W -n io.github.easylsp.demo/.DemoActivity")
        root("am start -W -n io.github.easylsp/.app.MainActivity")
        return result
    }
    private suspend fun logs(): String = root("logcat -d -v raw --pid=${root("pidof io.github.easylsp.demo")} -s EasylspDemoNative:I EasylspDemoFault:I EasylspHost:I EasylspNative:I '*:S'")
    private suspend fun candidate(project: ProjectEntity): PluginEntity = InstrumentationRegistry.getInstrumentation().context.assets.open("fixed-native/early-symbol-A/plugin-v3.zip").use { graph.store.importPlugin(project, it) }

    @Test fun legacyNativeJavaCoexistenceAndBackendRouting() = runBlocking<Unit>(Dispatchers.IO) {
        check(graph.root.useRoot()); withTimeout(15000) { graph.framework.state.first { it.compatible } }
        val existing = graph.store.dao.projectByPackage("io.github.easylsp.demo")
        val project = existing ?: ProjectEntity(UUID.randomUUID().toString(), "io.github.easylsp.demo", "Coexistence fixture", "1").also(graph.store.dao::insertProject)
        val originalBackend = project.nativeBackend; val originalPolicy = project.nativePolicy
        check(graph.store.nativePlugins.published(project.id)?.let { JSONObject(it.snapshot).getJSONArray("plugins").length() == 0 } != false)
        val native = candidate(project)
        val java = graph.store.create(project, "Coexistence Java fixture", graph.assets.open("replace-add/main.java").bufferedReader().use { it.readText() })
        val build = graph.store.nativePlugins.builds(native.id).single()
        val evidence = JSONObject()
        try {
            for (backend in listOf("LIBXPOSED", "ZYGISK")) {
                graph.nativeDeployments.switchBackend(project.id, backend, "NATIVE_AND_JAVA")
                graph.nativeDeployments.enable(native, build.buildId, "NATIVE_AND_JAVA")
                graph.store.enable(java.id, true)
                fixture(); launch()
                val session = withTimeout(20000) { graph.bridge.sessions.first { rows -> rows.values.any { it.packageName == project.packageName && it.processName == project.packageName } } }.values.first { it.packageName == project.packageName && it.processName == project.packageName }
                graph.bridge.commandSession(session.id, "loadPlugin", java.id)
                val log = logs()
                assertTrue(log, log.contains("constructor result=1010"))
                root("am start -W --activity-clear-top -n io.github.easylsp.demo/.DemoActivity --ez verifyAddition true")
                val javaLog = logs()
                assertTrue(javaLog, javaLog.contains("java add(2,3)=42"))
                val status = graph.nativeInstances.status(native)
                val instances = status.getJSONArray("instances")
                assertTrue(status.toString(), instances.length() > 0)
                // The same plugin can have historical processes; only the current PID matters.
                val events = graph.nativeInstances.events(project.id).filter { it.optInt("pid") == session.pid && it.optString("pluginId") == native.id }
                assertTrue(events.toString(), events.any { it.optString("backend") == backend && it.optString("phase") == "plugin_initialized" })
                assertFalse(events.toString(), events.any { it.optString("backend") != backend })
                evidence.put(backend, JSONObject().put("pid", session.pid).put("status", status).put("log", log).put("javaLog", javaLog))
                graph.bridge.commandSession(session.id, "unloadPlugin", java.id)
                graph.nativeDeployments.disableProject(project.id)
            }
            File(graph.filesDir, "native-coexistence-evidence.json").writeText(evidence.toString(2))
        } finally {
            graph.store.enable(java.id, false)
            graph.nativeDeployments.disableProject(project.id)
            root("am force-stop io.github.easylsp.demo")
            graph.store.delete(java.id); graph.store.delete(native.id)
            if (existing == null) graph.localManagement.deleteProject(project.id)
            else graph.nativeDeployments.switchBackend(project.id, originalBackend, originalPolicy)
        }
    }

    @Test fun phoneCpp17StaticStlPairBuildsAndExecutes() = runBlocking<Unit>(Dispatchers.IO) {
        check(graph.root.useRoot())
        val existing = graph.store.dao.projectByPackage("io.github.easylsp.demo")
        val project = existing ?: ProjectEntity(UUID.randomUUID().toString(), "io.github.easylsp.demo", "C++ fixture", "1").also(graph.store.dao::insertProject)
        val originalBackend = project.nativeBackend; val originalPolicy = project.nativePolicy
        val plugins = mutableListOf<PluginEntity>()
        val evidence = JSONArray()
        try {
            val preparation = graph.nativeBuilds.prepare(project.id)
            assertEquals(0, graph.executions.await(project.id, preparation.getString("executionId")).getInt("exitCode"))
            graph.nativeDeployments.switchBackend(project.id, "ZYGISK", "NATIVE_ONLY")
            for (name in listOf("cpp-a", "cpp-b")) {
                val source = graph.assets.open("$name/src/main.cpp").bufferedReader().use { it.readText() }
                val plugin = graph.store.create(project, "Phone C++17 $name", source, runtime = "NATIVE", nativeEntry = "src/main.cpp")
                plugins += plugin
                val build = graph.nativeBuilds.build(plugin, plugin.revision, project.sampleRevision)
                val result = graph.executions.await(project.id, build.getString("executionId"))
                evidence.put(JSONObject().put("build", build).put("execution", result))
                assertEquals(result.toString(), 0, result.getInt("exitCode"))
                graph.nativeDeployments.enable(plugin, build.getString("buildId"), "NATIVE_ONLY")
            }
            fixture(); launch(); val log = logs()
            assertTrue(log, log.contains("constructor result=7008")); assertTrue(log, log.contains("static STL"))
            File(graph.filesDir, "native-phone-cpp-evidence.json").writeText(JSONObject().put("builds", evidence).put("log", log).toString(2))
        } finally {
            graph.nativeDeployments.disableProject(project.id)
            plugins.forEach { graph.store.delete(it.id) }
            graph.nativeDeployments.switchBackend(project.id, originalBackend, originalPolicy)
            root("am force-stop io.github.easylsp.demo")
            if (existing == null) graph.localManagement.deleteProject(project.id)
        }
    }

    @Test fun targetApkUpdateRequiresExplicitRepublish() = runBlocking<Unit>(Dispatchers.IO) {
        check(graph.root.useRoot())
        withTimeout(15000) { graph.framework.state.first { it.compatible } }
        val arguments = InstrumentationRegistry.getArguments()
        val updatedApk = requireNotNull(arguments.getString("updatedDemoApk"))
        val restoreApk = requireNotNull(arguments.getString("restoreDemoApk"))
        val existing = graph.store.dao.projectByPackage("io.github.easylsp.demo")
        val project = existing ?: ProjectEntity(UUID.randomUUID().toString(), "io.github.easylsp.demo", "APK update fixture", "1").also(graph.store.dao::insertProject)
        check(graph.nativeHost.current(graph.packageManager.getApplicationInfo(project.packageName, 0).uid, project.packageName) == null)
        val plugin = candidate(project)
        val build = graph.store.nativePlugins.builds(plugin.id).single()
        val beforeInfo = graph.packageManager.getPackageInfo(project.packageName, 0)
        val evidence = JSONObject()
        var installedUpdate = false
        try {
            graph.nativeDeployments.switchBackend(project.id, "ZYGISK", "NATIVE_ONLY")
            val before = graph.nativeDeployments.enable(plugin, build.buildId, "NATIVE_ONLY")
            fixture(); launch(); assertTrue(logs().contains("constructor result=1010"))
            root("am force-stop io.github.easylsp.demo")
            assertTrue(root("pm install -r " + RootCommand.quote(updatedApk)).contains("Success"))
            installedUpdate = true
            val afterInfo = graph.packageManager.getPackageInfo(project.packageName, 0)
            assertTrue(afterInfo.longVersionCode > beforeInfo.longVersionCode)
            assertNotEquals(beforeInfo.applicationInfo!!.sourceDir, afterInfo.applicationInfo!!.sourceDir)
            fixture(); launch()
            val rejected = logs()
            assertTrue(rejected, rejected.contains("constructor result=8"))
            val error = runCatching { graph.nativeDeployments.enable(plugin, build.buildId, "NATIVE_ONLY") }.exceptionOrNull()
            assertTrue(error.toString(), error?.message.orEmpty().contains("目标已升级"))
            evidence.put("oldPublication", before).put("rejectedLog", rejected).put("republishError", error.toString())
            graph.nativeDeployments.disableProject(project.id)
            val sampled = graph.samples.capture(requireNotNull(graph.store.dao.project(project.id)))
            val after = graph.nativeDeployments.enable(plugin, build.buildId, "NATIVE_ONLY")
            assertNotEquals(before.getString("generation"), after.getString("generation"))
            fixture(); launch(); val accepted = logs()
            assertTrue(accepted, accepted.contains("constructor result=1010"))
            evidence.put("newPublication", after).put("newSampleRevision", sampled.sampleRevision).put("acceptedLog", accepted)
            File(graph.filesDir, "native-target-update-evidence.json").writeText(evidence.toString(2))
        } finally {
            graph.nativeDeployments.disableProject(project.id)
            root("am force-stop io.github.easylsp.demo")
            if (installedUpdate) assertTrue(root("pm install -r -d " + RootCommand.quote(restoreApk)).contains("Success"))
            graph.store.delete(plugin.id)
            graph.nativeDeployments.switchBackend(project.id, project.nativeBackend, project.nativePolicy)
            if (existing == null) graph.localManagement.deleteProject(project.id)
        }
    }

    @Test fun projectDeletionKeepsJavaTargetAvailable() = runBlocking<Unit>(Dispatchers.IO) {
        check(graph.root.useRoot())
        withTimeout(15000) { graph.framework.state.first { it.compatible } }
        check(graph.store.dao.projectByPackage("io.github.easylsp.demo") == null)
        val project = graph.store.select("io.github.easylsp.demo", "Deletion Java recovery fixture", "1")
        try {
            graph.nativeDeployments.switchBackend(project.id, "ZYGISK", "NATIVE_ONLY")
            val plugin = candidate(project)
            graph.nativeDeployments.enable(plugin, graph.store.nativePlugins.builds(plugin.id).single().buildId, "NATIVE_ONLY")
            graph.localManagement.deleteProject(project.id)
            fixture(); launch()
            val deletedLog = logs()
            assertTrue(deletedLog, deletedLog.contains("constructor result=8"))
            assertFalse(deletedLog, deletedLog.contains("native_config_failed"))
            // A deleted project must reject registration. Selecting it again restores Java access.
            graph.store.select("io.github.easylsp.demo", "Recreated Java recovery fixture", "1")
            fixture(); launch()
            val pid = root("pidof io.github.easylsp.demo").toInt()
            withTimeout(20000) { graph.bridge.sessions.first { rows -> rows.values.any { it.pid == pid && it.packageName == project.packageName } } }
            val log = logs()
            assertTrue(log, log.contains("constructor result=8"))
            assertFalse(log, log.contains("native_config_failed"))
            File(graph.filesDir, "native-delete-java-recovery.json").writeText(JSONObject().put("pid", pid).put("javaConnected", true).put("deletedLog", deletedLog).put("log", log).toString(2))
        } finally {
            graph.store.dao.projectByPackage("io.github.easylsp.demo")?.let { graph.localManagement.deleteProject(it.id) }
            root("am force-stop io.github.easylsp.demo")
        }
    }

    @Test fun prepareRootPersistence() = runBlocking<Unit>(Dispatchers.IO) {
        check(graph.root.useRoot()); withTimeout(15000) { graph.framework.state.first { it.compatible } }
        val existing = graph.store.dao.projectByPackage("io.github.easylsp.demo")
        val project = existing ?: ProjectEntity(UUID.randomUUID().toString(), "io.github.easylsp.demo", "Persistence fixture", "1").also(graph.store.dao::insertProject)
        check(graph.nativeHost.current(graph.packageManager.getApplicationInfo(project.packageName, 0).uid, project.packageName) == null)
        val plugin = candidate(project); val build = graph.store.nativePlugins.builds(plugin.id).single()
        graph.nativeDeployments.switchBackend(project.id, "ZYGISK", "NATIVE_ONLY")
        val publication = graph.nativeDeployments.enable(plugin, build.buildId, "NATIVE_ONLY")
        val record = publication.put("createdProject", existing == null).put("originalBackend", project.nativeBackend).put("originalPolicy", project.nativePolicy).put("projectId", project.id).put("pluginId", plugin.id).put("bootId", root("cat /proc/sys/kernel/random/boot_id"))
        InstrumentationRegistry.getArguments().getString("nativeModelId")?.let { modelId ->
            record.put("modelId", modelId).put("credentialReadableBefore", graph.models.secret(modelId).isNotEmpty())
            assertTrue(record.getBoolean("credentialReadableBefore"))
        }
        File(graph.filesDir, "native-root-persistence.json").writeText(record.toString(2))
    }
    @Test fun finishRootPersistence() = runBlocking<Unit>(Dispatchers.IO) {
        val file = File(graph.filesDir, "native-root-persistence.json"); val record = JSONObject(file.readText())
        val plugin = requireNotNull(graph.store.dao.plugin(record.getString("pluginId")))
        try {
            if (record.has("modelId")) {
                record.put("credentialReadableAfter", graph.models.secret(record.getString("modelId")).isNotEmpty())
                assertTrue(record.getBoolean("credentialReadableAfter"))
            }
            assertNotEquals(record.getString("bootId"), root("cat /proc/sys/kernel/random/boot_id"))
            val current = graph.nativeHost.current(graph.packageManager.getApplicationInfo("io.github.easylsp.demo", 0).uid, "io.github.easylsp.demo")!!
            assertEquals(record.getString("generation"), current.getString("generation"))
            fixture(); launch(); val log = logs(); assertTrue(log, log.contains("constructor result=1010"))
            record.put("afterReboot", graph.nativeHost.refresh()).put("log", log).put("passed", true)
            file.writeText(record.toString(2))
        } finally { graph.nativeDeployments.disableProject(plugin.projectId); graph.store.delete(plugin.id); graph.nativeDeployments.switchBackend(plugin.projectId, record.getString("originalBackend"), record.getString("originalPolicy")); root("am force-stop io.github.easylsp.demo"); if (record.getBoolean("createdProject")) graph.localManagement.deleteProject(plugin.projectId) }
    }
}
