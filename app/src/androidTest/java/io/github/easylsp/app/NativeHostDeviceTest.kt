package io.github.easylsp.app

import androidx.test.platform.app.InstrumentationRegistry
import io.github.easylsp.app.data.ProjectEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

/** Runs only against the controlled Demo; exercises the real app → su → hostctl boundary. */
class NativeHostDeviceTest {
    @get:org.junit.Rule(order = 0) val visible = VisibleActivityRule()
    @get:org.junit.Rule(order = 1) val activity = androidx.test.ext.junit.rules.ActivityScenarioRule(MainActivity::class.java)
    @Test fun rootMigrationPublicationReconcileAndDirectRuntimeIdentity() = runBlocking(Dispatchers.IO) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val graph = instrumentation.targetContext.applicationContext as EasylspApp
        val packageName = "io.github.easylsp.demo"
        val info = graph.packageManager.getPackageInfo(packageName, 0)
        val uid = info.applicationInfo!!.uid
        assertNull("Demo publication must be clear before test", graph.nativeHost.current(uid, packageName))
        graph.store.dao.projectByPackage(packageName)?.let { interrupted ->
            check(interrupted.name == "Root integration fixture") { "Preserve existing Demo project before acceptance" }
            graph.nativeDeployments.disableProject(interrupted.id)
            graph.store.dao.plugins(interrupted.id).forEach { graph.store.delete(it.id) }
            graph.store.nativePlugins.deleteProjectBuilds(interrupted.id)
            graph.store.nativePlugins.deleteProjectDeployments(interrupted.id)
            graph.store.dao.deleteProject(interrupted.id)
        }
        val project = ProjectEntity(UUID.randomUUID().toString(), packageName, "Root integration fixture", "1")
        graph.store.dao.insertProject(project)
        fun shell(command: String): String {
            val pipes = instrumentation.uiAutomation.executeShellCommandRw("sh")
            android.os.ParcelFileDescriptor.AutoCloseOutputStream(pipes[1]).use { it.write((command + "\n").toByteArray()) }
            return android.os.ParcelFileDescriptor.AutoCloseInputStream(pipes[0]).bufferedReader().use { it.readText() }
        }
        try {
            graph.nativeDeployments.switchBackend(project.id, "ZYGISK", "NATIVE_ONLY")
            assertEquals("ZYGISK", graph.store.dao.project(project.id)!!.nativeBackend)
            // OnlineMarketDeviceTest has received and verified this fixed release already.
            // Root publication/recovery must work independently of network availability.
            val plugin = graph.store.installCachedRelease(project.id, "qintaiyang.demo.early", "demo-early-1")
            val build = graph.store.nativePlugins.builds(plugin.id).single()
            val snapshot = graph.nativeDeployments.enable(plugin, build.buildId, "NATIVE_ONLY")
            val generation = snapshot.getString("generation")
            assertTrue(graph.store.dao.plugin(plugin.id)!!.desiredEnabled)
            assertEquals(generation, graph.nativeHost.current(uid, packageName)!!.getString("generation"))
            graph.store.dao.updatePlugin(graph.store.dao.plugin(plugin.id)!!.copy(desiredEnabled = false))
            graph.nativeDeployments.reconcile(project.id)
            assertTrue(graph.store.dao.plugin(plugin.id)!!.desiredEnabled)
            graph.nativeDeployments.reorder(project.id, listOf(plugin.id))
            val activeGeneration = graph.nativeHost.current(uid, packageName)!!.getString("generation")
            shell("am force-stop $packageName")
            shell("run-as $packageName sh -c 'mkdir -p files; echo native-plugin > files/startup-fault.txt'")
            shell("am start -W -n $packageName/.DemoActivity")
            val events = graph.nativeHost.call("events", uid.toString()).getJSONArray("events")
            File(graph.filesDir, "native-host-device-events.json").writeText(events.toString(2))
            val rows = (0 until events.length()).map { events.getJSONObject(it) }.filter { it.optString("pluginId") == plugin.id }
            assertTrue("No direct event for published plugin", rows.isNotEmpty())
            assertTrue(rows.any { it.getString("phase") == "hook_hit" })
            rows.forEach { row ->
                assertEquals(activeGeneration, row.getString("generation"))
                assertEquals(build.buildId, row.getString("buildId"))
                assertEquals("demo-early-1", row.getString("releaseId"))
                assertEquals("ZYGISK", row.getString("backend"))
                assertTrue(row.getString("coreVersion").contains("2.0.1"))
            }
            // Restore a persisted OLD_DISABLED migration record, as after a process interruption.
            graph.nativeHost.disable(uid, packageName)
            val row = graph.store.nativePlugins.published(project.id)!!
            val resumed = org.json.JSONObject(row.snapshot).put("generation", UUID.randomUUID().toString()).put("migrationFrom", "LIBXPOSED")
            graph.store.nativePlugins.saveDeployment(row.copy(generation = resumed.getString("generation"), snapshot = resumed.toString(), state = "OLD_DISABLED"))
            graph.nativeDeployments.resumeMigration(project.id)
            assertEquals(resumed.getString("generation"), graph.nativeHost.current(uid, packageName)!!.getString("generation"))
            graph.nativeDeployments.disableProject(project.id)
            assertNull(graph.nativeHost.current(uid, packageName))
            assertFalse(graph.store.dao.plugin(plugin.id)!!.desiredEnabled)
            File(graph.filesDir, "native-host-device-evidence.json").writeText(org.json.JSONObject().put("events", events).put("migrationResumed", true).toString(2))
        } finally {
            graph.nativeDeployments.disableProject(project.id)
            shell("am force-stop $packageName")
            graph.store.dao.plugins(project.id).forEach { graph.store.delete(it.id) }
            graph.store.nativePlugins.deleteProjectBuilds(project.id)
            graph.store.nativePlugins.deleteProjectDeployments(project.id)
            graph.store.dao.deleteProject(project.id)
            File(graph.filesDir, "workspaces/${project.id}").deleteRecursively()
        }
    }
}
