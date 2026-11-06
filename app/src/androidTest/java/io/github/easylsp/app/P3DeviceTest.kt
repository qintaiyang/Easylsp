package io.github.easylsp.app

import androidx.test.platform.app.InstrumentationRegistry
import io.github.easylsp.app.data.*
import io.github.easylsp.app.platform.skills.SkillService
import io.github.easylsp.app.platform.tasks.RemoteTaskClient
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

/** Requires a real Android runtime. Does not assert Xposed/Frida injection without a framework. */
class P3DeviceTest {
    private val graph get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as EasylspApp
    @Test fun grantedRootRunsTerminalAndShellSkillsWithUidZero() = runBlocking {
        val granted = graph.root.refresh().await().granted
        org.junit.Assume.assumeTrue("requires granted device su", granted)
        val project = UUID.randomUUID().toString()
        val skill = SkillEntity(UUID.randomUUID().toString(), "root id", "test", runtime = "shell", source = "id")
        withContext(Dispatchers.IO) {
            val previous = graph.root.preferred.value; graph.root.prefer(true)
            graph.store.workflows.saveSkill(skill)
            try {
                val terminal = graph.executions.command(project, "id")
                assertTrue(withTimeout(10000) { graph.executions.await(project, terminal) }.getString("text").contains("uid=0"))
                val script = graph.skills.start(project, skill.id, JSONObject())
                assertTrue(withTimeout(10000) { graph.executions.await(project, script) }.getString("text").contains("uid=0"))
            } finally { graph.root.prefer(previous); graph.store.workflows.deleteSkill(skill.id) }
        }
    }
    @Test fun androidKeystoreSignsGeneratedApkAndPackageManagerParsesOwnPackage() = runBlocking {
        val store = graph.store
        val project = store.select("io.github.easylsp.demo", "Demo", "1")
        val plugin = store.create(project, "Standalone test", "void onLoad() {}")
        try {
            val file = graph.apkExporter.export(plugin.id)
            val parsed = graph.packageManager.getPackageArchiveInfo(file.path, 0)
            assertNotNull(parsed)
            assertEquals(io.github.easylsp.app.platform.export.ApkExporter.packageName(plugin.id), parsed!!.packageName)
            assertEquals(31, parsed.applicationInfo!!.minSdkVersion)
            assertTrue(com.android.apksig.ApkVerifier.Builder(file).setMinCheckedPlatformVersion(31).build().verify().isVerified)
        } finally { store.delete(plugin.id) }
    }
    @Test fun enabledSkillExecutesAndDisabledSkillCannotStart() = runBlocking {
        val project = UUID.randomUUID().toString()
        val skill = SkillEntity(UUID.randomUUID().toString(), "device echo", "test", runtime = "shell", source = "printf '%s' \"\$EASYLSP_ARGUMENTS\"")
        withContext(Dispatchers.IO) {
            graph.store.workflows.saveSkill(skill)
            try {
                val id = graph.skills.start(project, skill.id, JSONObject().put("text", "device"))
                assertTrue(withTimeout(5000) { graph.executions.await(project, id) }.getString("text").contains("device"))
                graph.store.workflows.saveSkill(skill.copy(enabled = false))
                try { graph.skills.start(project, skill.id, JSONObject()); fail("disabled") } catch (_: IllegalArgumentException) {}
            } finally { graph.store.workflows.deleteSkill(skill.id) }
        }
    }
    @Test fun cancellingInfiniteBeanShellKillsDedicatedProcessAndNextCallWorks() = runBlocking {
        val ready = CompletableDeferred<Unit>()
        val task = launch {
            RemoteTaskClient(graph, SkillService::class.java).call(JSONObject().put("source", "print(\"ready\"); while(true) {};").put("arguments", JSONObject()).put("workspace", graph.filesDir.path)) { ready.complete(Unit) }
        }
        withTimeout(10000) { ready.await() }; withTimeout(5000) { task.cancelAndJoin() }
        val result = withTimeout(10000) { RemoteTaskClient(graph, SkillService::class.java).call(JSONObject().put("source", "return 42;").put("arguments", JSONObject()).put("workspace", graph.filesDir.path)) }
        assertEquals(42, result.getInt("result"))
    }
}
