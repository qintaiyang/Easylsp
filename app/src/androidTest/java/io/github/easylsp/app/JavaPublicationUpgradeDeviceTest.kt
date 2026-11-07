package io.github.easylsp.app

import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Reproduce an upgrade: enabled rows exist before Java startup publications were introduced. */
class JavaPublicationUpgradeDeviceTest {
    @get:Rule val visible = VisibleActivityRule()

    @Test fun openingManagementPublishesLegacyEnabledPluginsWithoutRepublishingSavedEdits() = runBlocking<Unit>(Dispatchers.IO) {
        val graph = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as EasylspApp
        withTimeout(15000) { graph.framework.state.first { it.compatible } }
        val project = requireNotNull(graph.store.dao.projectByPackage("io.github.easylsp.demo", 0))
        val app = requireNotNull(graph.targetApplications.info(project.packageName, project.userId).applicationInfo)
        val key = "${app.uid}:${project.packageName}"
        val saved = File(graph.filesDir, "java-publications/${project.id}.json")
        val original = saved.takeIf { it.exists() }?.readText()
        val oldRemote = graph.framework.javaSnapshot(key)
        val enabled = graph.store.create(project, "Upgrade publication fixture", "void onLoad() {}")
        val disabled = graph.store.create(project, "Disabled upgrade fixture", "void onLoad() {}")
        try {
            // The old application persisted this flag without ever publishing a startup set.
            graph.store.dao.updatePlugin(enabled.copy(desiredEnabled = true))
            saved.delete()
            ActivityScenario.launch(MainActivity::class.java).use {
                withTimeout(15000) { while (!saved.exists()) delay(25) }
                val first = JSONObject(saved.readText())
                val items = first.getJSONArray("plugins")
                val ids = (0 until items.length()).map { items.getJSONObject(it).getString("pluginId") }
                assertTrue(enabled.id in ids)
                assertFalse(disabled.id in ids)
                assertEquals(first.getString("generation"), JSONObject(graph.framework.javaSnapshot(key)!!).getString("generation"))

                graph.store.save(enabled.id, enabled.name, enabled.author, enabled.description, enabled.version, "void onLoad() { plugin.log(\"saved only\"); }")
                assertEquals(2L, graph.store.dao.plugin(enabled.id)!!.revision)
                assertTrue(graph.javaDeployments.migrateEnabled().isEmpty())
                val unchanged = JSONObject(saved.readText())
                assertEquals(first.getString("generation"), unchanged.getString("generation"))
                val pinned = (0 until unchanged.getJSONArray("plugins").length()).map { unchanged.getJSONArray("plugins").getJSONObject(it) }
                    .first { it.getString("pluginId") == enabled.id }
                assertEquals(1L, pinned.getLong("revision"))
                File(graph.filesDir, "java-publication-upgrade-evidence.json").writeText(JSONObject()
                    .put("managementPublishedLegacyEnabled", true).put("disabledExcluded", true)
                    .put("frameworkSnapshotCommitted", true).put("savedRevision", 2).put("publishedRevision", 1)
                    .put("existingGenerationPreserved", true).toString(2))
            }
        } finally {
            withContext(NonCancellable) {
                for (id in listOf(enabled.id, disabled.id)) {
                    graph.store.dao.plugin(id)?.let { graph.store.dao.updatePlugin(it.copy(desiredEnabled = false)) }
                    graph.store.delete(id)
                }
                if (oldRemote != null) graph.framework.publishJavaSnapshot(key, JSONObject(oldRemote))
                else graph.javaDeployments.publish(project.id)
                if (original != null) saved.writeText(original) else saved.delete()
            }
        }
    }
}
