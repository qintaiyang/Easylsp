package io.github.easylsp.app

import androidx.test.platform.app.InstrumentationRegistry
import io.github.easylsp.app.data.ProjectEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

/** Uses the public signed catalog with no author account; leaves no test project/publication. */
class OnlineMarketDeviceTest {
    @get:org.junit.Rule(order = 0) val visible = VisibleActivityRule()
    @get:org.junit.Rule(order = 1) val activity = androidx.test.ext.junit.rules.ActivityScenarioRule(MainActivity::class.java)
    @Test fun anonymousSignedReleaseInstallsAsFixedPrecompiledBuild(): Unit = runBlocking(Dispatchers.IO) {
        val graph = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as EasylspApp
        val releaseId = InstrumentationRegistry.getArguments().getString("releaseId") ?: "demo-early-1"
        // The ROM killed the previous instrumentation before its finally block.
        graph.store.dao.projectByPackage("io.github.easylsp.demo")?.let { interrupted ->
            check(interrupted.name == "Market device fixture" && graph.store.dao.plugins(interrupted.id).isEmpty()) { "Preserve existing Demo project before acceptance" }
            graph.store.dao.deleteProject(interrupted.id)
        }
        val catalog = graph.market.refresh()
        val records = catalog.getJSONArray("releases")
        val release = (0 until records.length()).map { records.getJSONObject(it) }
            .single { it.getString("catalogPluginId") == "qintaiyang.demo.early" && it.getString("releaseId") == releaseId }
        val project = ProjectEntity(UUID.randomUUID().toString(), "io.github.easylsp.demo", "Market device fixture", "1")
        graph.store.dao.insertProject(project)
        try {
            val plugin = graph.market.install(project.id, release)
            assertEquals("MARKET", plugin.source)
            assertEquals(releaseId, plugin.releaseId)
            assertFalse(plugin.desiredEnabled)
            val build = graph.store.nativePlugins.builds(plugin.id).single()
            assertEquals("SUCCEEDED", build.state)
            assertEquals(plugin.releaseId, build.releaseId)
            assertTrue(java.io.File(build.artifactPath).isFile)
            assertEquals(plugin.id, graph.market.install(project.id, release).id)
            assertEquals(catalog.getString("catalogRevision"), graph.market.cached()!!.getString("catalogRevision"))
            InstrumentationRegistry.getArguments().getString("preparationId")?.let { id ->
                assertEquals("INDEXED", graph.releasePublishing.status(id).getString("status"))
            }
        } finally {
            graph.store.dao.plugins(project.id).forEach { graph.store.delete(it.id) }
            graph.store.nativePlugins.deleteProjectBuilds(project.id)
            graph.store.dao.deleteProject(project.id)
            java.io.File(graph.filesDir, "workspaces/${project.id}").deleteRecursively()
        }
    }
}
